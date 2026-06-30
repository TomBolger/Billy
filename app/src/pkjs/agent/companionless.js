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

var gemini = require('./gemini');
var formatting = require('./formatting');
var localHistory = require('./local_history');
var profileTools = require('./profile_tools');
var promptBuilder = require('./prompt');
var usage = require('./usage');
var watchTools = require('./watch_tools');
var uiTools = require('./ui_tools');

function CompanionlessRuntime(session) {
    this.session = session;
}

CompanionlessRuntime.prototype.run = function() {
    runCompanionlessModel(this.session);
}

function runCompanionlessModel(session) {
    var threadId = localHistory.ensureThreadId(session);
    var searchGrounding = true;
    var tools = uiTools.getDeclarations();
    if (profileTools.shouldExpose(session.prompt)) {
        tools = tools.concat(profileTools.getDeclarations());
    }
    if (watchTools.shouldExpose(session.prompt)) {
        tools = tools.concat(watchTools.getDeclarations());
    }
    var progress = startProgress(session, searchGrounding, tools && tools.length > 0);
    runModelLoop(session, threadId, [{
        type: 'user_input',
        content: [{
            type: 'text',
            text: localHistory.buildInput(threadId, session.prompt)
        }]
    }], 0, progress, searchGrounding, tools);
}

function runModelLoop(session, threadId, history, iteration, progress, searchGrounding, tools) {
    gemini.generate(history, {
        enableSearchGrounding: searchGrounding,
        tools: tools,
        systemInstruction: promptBuilder.buildSystemInstruction()
    }, function(err, response) {
        if (err) {
            progress.done();
            session.handleMessage({data: 'w' + err.message});
            session.handleMessage({data: 'd'});
            session.handleClose({
                code: 1000,
                reason: 'Local assistant request failed.',
                wasClean: true
            });
            return;
        }
        if (response.functionCalls && response.functionCalls.length > 0 && iteration < 3) {
            usage.recordGeminiResponse(response);
            appendHistoryItems(history, response.historyItems);
            executeFunctionCalls(session, history, response.functionCalls, function(stoppedForUser) {
                if (stoppedForUser) {
                    progress.done();
                    return;
                }
                progress.update('Writing the answer');
                runModelLoop(session, threadId, history, iteration + 1, progress, searchGrounding, tools);
            });
            return;
        }
        var text = response.text || 'I did not receive a usable answer.';
        usage.recordGeminiResponse(response);
        if (searchGrounding && iteration === 0) {
            usage.recordGroundedSearch();
        }
        localHistory.recordTurn(threadId, session.prompt, text);
        progress.done();
        streamText(session, text);
        session.handleMessage({data: 'd'});
        session.handleClose({
            code: 1000,
            reason: '',
            wasClean: true
        });
    });
}

function startProgress(session, searchGrounding, hasTools) {
    var active = true;
    var timers = [];

    function update(text) {
        if (!active) {
            return;
        }
        session.handleMessage({data: 'f' + text});
    }

    if (searchGrounding) {
        update('Searching the web');
        timers.push(setTimeout(function() {
            update('Reading results');
        }, 5000));
        timers.push(setTimeout(function() {
            update('Still working');
        }, 15000));
    } else if (hasTools) {
        update('Understanding the request');
    } else {
        update('Thinking');
        timers.push(setTimeout(function() {
            update('Still thinking');
        }, 5000));
    }

    return {
        update: update,
        done: function() {
            active = false;
            timers.forEach(function(timer) {
                clearTimeout(timer);
            });
            timers = [];
        }
    };
}

function appendHistoryItems(history, items) {
    if (!items || !Array.isArray(items)) {
        return;
    }
    items.forEach(function(item) {
        history.push(item);
    });
}

function executeFunctionCalls(session, history, calls, callback) {
    var index = 0;
    function next() {
        if (index >= calls.length) {
            callback();
            return;
        }
        var call = calls[index++];
        var handledByUi = uiTools.execute(session, call, function(result) {
            if (result && result.stop_for_user) {
                callback(true);
                return;
            }
            history.push({
                type: 'function_result',
                name: call.name,
                call_id: call.id,
                result: [{
                    type: 'text',
                    text: JSON.stringify(result)
                }]
            });
            next();
        });
        if (handledByUi) {
            return;
        }
        var handledByProfile = profileTools.execute(session, call, function(result) {
            history.push({
                type: 'function_result',
                name: call.name,
                call_id: call.id,
                result: [{
                    type: 'text',
                    text: JSON.stringify(result)
                }]
            });
            next();
        });
        if (handledByProfile) {
            return;
        }
        watchTools.execute(session, call, function(result) {
            history.push({
                type: 'function_result',
                name: call.name,
                call_id: call.id,
                result: [{
                    type: 'text',
                    text: JSON.stringify(result)
                }]
            });
            next();
        });
    }
    next();
}

function streamText(session, text) {
    text = formatting.forWatch(text).replace(/\u202f/g, '\u00a0');
    var chunk = '';
    for (var i = 0; i < text.length; i++) {
        var next = text[i];
        if (chunk.length > 0 && (chunk + next).length > 80) {
            session.handleMessage({data: 'c' + chunk});
            chunk = '';
        }
        chunk += next;
    }
    if (chunk.length > 0) {
        session.handleMessage({data: 'c' + chunk});
    }
}

exports.CompanionlessRuntime = CompanionlessRuntime;
