/**
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// The phone-side agent loop: prompt -> Gemini -> tools -> Gemini -> answer.

var gemini = require('./gemini');
var formatting = require('./formatting');
var localHistory = require('./local_history');
var promptBuilder = require('./prompt');
var registry = require('./tool_registry');
var usage = require('./usage');
var config = require('../config');
var location = require('../location');

var MAX_STEPS = 8;
var CHUNK_LENGTH = 80;

function CompanionlessRuntime(session) {
    this.session = session;
}

CompanionlessRuntime.prototype.run = function() {
    new Turn(this.session).start();
};

// "BILLY_CLARIFICATION_ANSWER\ncontext=..\nquestion=..\nanswer=.." from the
// watch picker becomes a natural sentence in the same thread.
function normalizePrompt(prompt) {
    prompt = String(prompt || '').trim();
    if (prompt.indexOf('BILLY_CLARIFICATION_ANSWER') !== 0) {
        return prompt;
    }
    var fields = {};
    prompt.split('\n').slice(1).forEach(function(line) {
        var eq = line.indexOf('=');
        if (eq > 0) {
            fields[line.substring(0, eq)] = line.substring(eq + 1);
        }
    });
    var answer = String(fields.answer || '').split('|')[0].trim();
    if (fields.question) {
        return 'My answer to your question "' + fields.question + '": ' + answer;
    }
    return answer || prompt;
}

function describeAction(name, args, result) {
    var status = result && result.status ? result.status : 'done';
    var brief = JSON.stringify(args || {});
    if (brief.length > 140) {
        brief = brief.substring(0, 137) + '...';
    }
    return name + ' ' + brief + ' -> ' + status;
}

function currentLatLng() {
    try {
        if (config.isLocationEnabled() && location.isReady()) {
            return location.getPos();
        }
    } catch (e) {
        // no location
    }
    return null;
}

function Turn(session) {
    this.session = session;
    this.userText = normalizePrompt(session.prompt);
    this.actions = [];
    this.executed = {};
    this.lastSummary = '';
    this.cardShown = false;
    this.searchCounted = false;
    this.progressTimers = [];
    this.finished = false;
}

Turn.prototype.start = function() {
    this.threadId = localHistory.ensureThreadId(this.session);
    this.contents = localHistory.contents(this.threadId).concat([{
        role: 'user',
        parts: [{text: this.userText}]
    }]);
    this.options = {
        functions: registry.declarations(),
        systemInstruction: promptBuilder.buildSystemInstruction(),
        search: true,
        latLng: currentLatLng()
    };
    this.progress('Thinking');
    var self = this;
    this.progressTimers.push(setTimeout(function() {
        self.progress('Still thinking');
    }, 8000));
    this.step(0, null);
};

Turn.prototype.standingDown = function() {
    var s = this.session;
    return !!(s && s.shouldStandDown && s.shouldStandDown());
};

Turn.prototype.progress = function(text) {
    if (!this.finished && !this.standingDown()) {
        this.session.handleMessage({data: 'f' + text});
    }
};

Turn.prototype.stopProgress = function() {
    this.progressTimers.forEach(clearTimeout);
    this.progressTimers = [];
};

// Gemini sometimes writes a tool call out as text ("call:default_api:show_image{...}",
// "tool_code ...", "request: api_call: ...") instead of making it.
function looksLikeLeakedToolCall(text) {
    var t = String(text || '').trim();
    if (!t) {
        return false;
    }
    var lower = t.toLowerCase();
    if (lower.indexOf('default_api') !== -1 || lower.indexOf('tool_code') === 0 || lower.indexOf('call:') === 0 || lower.indexOf('```tool') === 0) {
        return true;
    }
    if (/^[\w ]{1,24}:\s*[\w ]{0,24}(api|call|tool)\w*\s*:/i.test(t)) {
        return true;
    }
    var names = registry.declarations().map(function(d) {
        return d.name;
    });
    for (var i = 0; i < names.length; i++) {
        if (new RegExp('(^|[\\s:`])' + names[i] + '\\s*[({]').test(t)) {
            return true;
        }
    }
    var colons = (t.match(/:/g) || []).length;
    var words = (t.match(/[A-Za-z]{3,}/g) || []).length;
    return t.length >= 12 && colons >= 3 && words < colons * 2;
}
exports.looksLikeLeakedToolCall = looksLikeLeakedToolCall;

Turn.prototype.step = function(index, model) {
    var self = this;
    var options = this.options;
    if (index >= MAX_STEPS - 1) {
        // Last round: force a text answer from what we have.
        options = {};
        Object.keys(this.options).forEach(function(key) {
            options[key] = self.options[key];
        });
        options.forceText = true;
    }
    var handle = function(err, response) {
        if (self.standingDown()) {
            self.finish(null, true);
            return;
        }
        if (err) {
            self.fail(err.message || String(err));
            return;
        }
        usage.recordGeminiResponse(response);
        if (response.usedSearch && !self.searchCounted) {
            self.searchCounted = true;
            usage.recordGroundedSearch();
        }
        self.contents.push(response.modelContent);
        if (response.functionCalls.length > 0 && !options.forceText) {
            self.runTools(response.functionCalls, function(stopForUser) {
                if (stopForUser) {
                    self.finish('', false);
                    return;
                }
                self.progress('Writing the answer');
                self.step(index + 1, response.model);
            });
            return;
        }
        var leaked = response.functionCalls.length === 0 && looksLikeLeakedToolCall(response.text);
        if (leaked && !self.nudgedLeak && !options.forceText) {
            // The model wrote a tool request as text instead of calling it. Ask once more.
            self.nudgedLeak = true;
            self.contents.push({role: 'user', parts: [{text: '(Billy system note: your last reply came out as a raw tool request in text. Call the tool properly, or answer the user\'s question in plain words.)'}]});
            self.step(index + 1, response.model);
            return;
        }
        self.finish(leaked ? '' : response.text, false);
    };
    if (model) {
        gemini.generateWithModel(model, this.contents, options, handle);
    } else {
        gemini.generateFirst(this.contents, options, handle);
    }
};

Turn.prototype.runTools = function(calls, done) {
    var self = this;
    var parts = [];
    var stopForUser = false;
    var i = 0;
    function next() {
        if (i >= calls.length || self.standingDown()) {
            if (parts.length > 0) {
                self.contents.push({role: 'user', parts: parts});
            }
            done(stopForUser);
            return;
        }
        var call = calls[i++];
        var key = call.name + ':' + JSON.stringify(call.args || {});
        var respond = function(result) {
            result = result || {};
            if (result.stop_for_user) {
                stopForUser = true;
                self.actions.push('asked the user: ' + ((call.args && call.args.question) || 'a question'));
            } else {
                self.actions.push(describeAction(call.name, call.args, result));
            }
            if (result.status === 'ok' && result.summary) {
                self.lastSummary = result.summary;
            }
            if (result.watch_card) {
                self.cardShown = true;
            }
            var response = {};
            Object.keys(result).forEach(function(k) {
                if (k !== 'stop_for_user') {
                    response[k] = result[k];
                }
            });
            parts.push({functionResponse: {id: call.id, name: call.name, response: response}});
            next();
        };
        if (registry.isMutating(call.name) && self.executed[key]) {
            var previous = self.executed[key];
            respond({status: previous.status, summary: 'Already done earlier in this turn; not repeated.', original: previous});
            return;
        }
        registry.execute(self.session, call.name, call.args, function(result) {
            if (registry.isMutating(call.name)) {
                self.executed[key] = result;
            }
            respond(result);
        });
    }
    next();
};

Turn.prototype.fail = function(message) {
    if (this.finished) {
        return;
    }
    this.finished = true;
    this.stopProgress();
    this.session.handleMessage({data: 'w' + message});
    this.close();
};

Turn.prototype.finish = function(text, silent) {
    if (this.finished) {
        return;
    }
    this.finished = true;
    this.stopProgress();
    if (silent) {
        return;
    }
    text = formatting.forWatch(text || '');
    if (!text && this.lastSummary && !this.actions.some(isQuestion)) {
        text = this.lastSummary;
    }
    if (!text && !this.cardShown && !this.actions.some(isQuestion)) {
        text = 'Sorry, I did not get an answer. Please try again.';
    }
    localHistory.recordTurn(this.threadId, this.userText, text, this.actions);
    if (!text && this.actions.some(isQuestion)) {
        // The picker already ended the response on the watch.
        return;
    }
    if (text) {
        streamText(this.session, text);
    }
    this.close();
};

function isQuestion(action) {
    return action.indexOf('asked the user') === 0;
}

Turn.prototype.close = function() {
    this.session.handleMessage({data: 'd'});
    this.session.handleClose({code: 1000, reason: '', wasClean: true});
};

function streamText(session, text) {
    text = text.replace(/ /g, ' ');
    for (var i = 0; i < text.length; i += CHUNK_LENGTH) {
        session.handleMessage({data: 'c' + text.substring(i, i + CHUNK_LENGTH)});
    }
}

exports.CompanionlessRuntime = CompanionlessRuntime;
exports._normalizePrompt = normalizePrompt;
