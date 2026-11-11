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

// Thin, predictable Gemini generateContent client.
//
// Design rules (these are what keep tool calling reliable):
// - One API surface only (generateContent). No silent fallback to a different
//   API that flattens tool results into plain text.
// - Google Search and custom functions are sent together with
//   toolConfig.includeServerSideToolInvocations, which Gemini 3 requires for
//   the combination. Without the flag every tool request fails.
// - Exactly one model: the user's GEMINI_MODEL setting (or the default). No
//   fallback chain and no dropping of tools. If Google rejects the request the
//   user sees Google's message and can report it.
// - Retries: ONE retry of the same model for network-level errors and HTTP 503,
//   and ONE re-ask when the answer is completely empty. Nothing else.
// - Model content is returned untouched so thought signatures survive.

var config = require('../config');

var BASE_URL = 'https://generativelanguage.googleapis.com/v1beta/models/';
var REQUEST_TIMEOUT_MS = 25000;
var MAX_OUTPUT_TOKENS = 4096;
var TRANSIENT_RETRY_DELAY_MS = 900;
var REPORT_HINT = ' You can report this from the menu.';

function isGemini3(model) {
    return /^gemini-3/.test(String(model || ''));
}

function buildBody(model, contents, options) {
    var body = {
        contents: contents,
        generationConfig: {
            candidateCount: 1,
            maxOutputTokens: options.maxOutputTokens || MAX_OUTPUT_TOKENS
        }
    };
    if (isGemini3(model)) {
        body.generationConfig.thinkingConfig = {thinkingLevel: options.thinkingLevel || config.getGeminiThinking()};
    }
    if (options.systemInstruction) {
        body.systemInstruction = {parts: [{text: options.systemInstruction}]};
    }
    var declarations = (options.functions || []).map(function(fn) {
        return {
            name: fn.name,
            description: fn.description,
            parameters: fn.parameters || {type: 'object', properties: {}}
        };
    });
    var tools = [];
    // Search, Maps and URL reading always ride along (Google refuses Maps
    // together with code execution, so that one is left out). Combining them with
    // functions needs Gemini 3; an older model answers with Google's own error,
    // which the user sees.
    var useSearch = !!options.search;
    var extras = useSearch && declarations.length > 0;
    if (useSearch) {
        tools.push({googleSearch: {}});
    }
    if (extras) {
        tools.push({googleMaps: {}});
        tools.push({urlContext: {}});
    }
    if (declarations.length > 0) {
        tools.push({functionDeclarations: declarations});
    }
    if (tools.length > 0) {
        body.tools = tools;
    }
    if (useSearch && declarations.length > 0) {
        body.toolConfig = {includeServerSideToolInvocations: true};
    }
    if (extras && options.latLng) {
        body.toolConfig.retrievalConfig = {latLng: {latitude: options.latLng.lat, longitude: options.latLng.lon}};
    }
    if (options.forceText && declarations.length > 0) {
        body.toolConfig = body.toolConfig || {};
        body.toolConfig.functionCallingConfig = {mode: 'NONE'};
    }
    return body;
}

function post(apiKey, model, body, callback) {
    var finished = false;
    var req = new XMLHttpRequest();
    function finish(err, value) {
        if (finished) {
            return;
        }
        finished = true;
        callback(err, value);
    }
    req.open('POST', BASE_URL + encodeURIComponent(model) + ':generateContent', true);
    req.timeout = REQUEST_TIMEOUT_MS;
    req.setRequestHeader('Content-Type', 'application/json');
    req.setRequestHeader('x-goog-api-key', apiKey);
    req.onload = function() {
        if (req.readyState !== 4) {
            return;
        }
        if (req.status < 200 || req.status >= 300) {
            finish(parseError(req.status, req.responseText, model));
            return;
        }
        try {
            finish(null, JSON.parse(req.responseText));
        } catch (e) {
            finish(e);
        }
    };
    req.onerror = function() {
        var err = new Error('No internet connection on your phone.' + REPORT_HINT);
        err.kind = 'network';
        err.status = 0;
        finish(err);
    };
    req.ontimeout = function() {
        var err = new Error('Gemini (' + model + ') didn\'t respond within ' + Math.round(REQUEST_TIMEOUT_MS / 1000) + ' seconds.' + REPORT_HINT);
        err.kind = 'timeout';
        err.status = 0;
        finish(err);
    };
    req.send(JSON.stringify(body));
}

function parseResponse(model, raw) {
    var candidate = raw && raw.candidates && raw.candidates[0];
    var content = candidate && candidate.content ? candidate.content : {role: 'model', parts: []};
    if (!content.role) {
        content.role = 'model';
    }
    var parts = content.parts || [];
    var text = [];
    var calls = [];
    parts.forEach(function(part) {
        if (part.thought) {
            return;
        }
        if (part.functionCall) {
            var args = part.functionCall.args || {};
            if (typeof args === 'string') {
                try {
                    args = JSON.parse(args);
                } catch (e) {
                    args = {};
                }
            }
            calls.push({
                id: part.functionCall.id,
                name: part.functionCall.name,
                args: args
            });
        } else if (typeof part.text === 'string') {
            text.push(part.text);
        }
    });
    return {
        model: model,
        text: text.join('').trim(),
        functionCalls: calls,
        modelContent: content,
        finishReason: candidate ? candidate.finishReason : 'NO_CANDIDATE',
        usedSearch: !!(candidate && candidate.groundingMetadata),
        raw: raw
    };
}

function isUsable(response) {
    return response.functionCalls.length > 0 || response.text.length > 0;
}

function usageOf(raw) {
    var u = (raw && raw.usageMetadata) || {};
    return {
        prompt: typeof u.promptTokenCount === 'number' ? u.promptTokenCount : null,
        thoughts: typeof u.thoughtsTokenCount === 'number' ? u.thoughtsTokenCount : null,
        candidates: typeof u.candidatesTokenCount === 'number' ? u.candidatesTokenCount : null
    };
}

// Sends one request to the user's chosen model. options.onAttempt (optional)
// is called once per HTTP attempt with timing facts for diagnostics (never
// prompt text).
exports.generate = function(contents, options, callback) {
    var model = config.getGeminiModel();
    var apiKey = config.getGeminiApiKey();
    if (!apiKey) {
        callback(new Error('Billy needs a free Gemini key. In the Pebble app, open Billy\'s settings and follow the steps under Gemini API key.'));
        return;
    }
    var attempt = 0;
    var errorRetried = false;
    var emptyRetried = false;
    function note(start, status, extra) {
        if (typeof options.onAttempt === 'function') {
            var rec = {model: model, ms: Date.now() - start, status: status, retry: attempt - 1};
            Object.keys(extra || {}).forEach(function(k) {
                rec[k] = extra[k];
            });
            try {
                options.onAttempt(rec);
            } catch (e) {
                // diagnostics must never break an answer
            }
        }
    }
    function run() {
        attempt++;
        var started = Date.now();
        post(apiKey, model, buildBody(model, contents, options), function(err, raw) {
            if (err) {
                note(started, err.status || 0, {error: err.kind || ('http' + err.status)});
                if (!errorRetried && (err.kind === 'network' || err.status === 503)) {
                    errorRetried = true;
                    setTimeout(run, TRANSIENT_RETRY_DELAY_MS);
                    return;
                }
                callback(err);
                return;
            }
            var response = parseResponse(model, raw);
            var u = usageOf(raw);
            note(started, 200, {finishReason: response.finishReason, prompt: u.prompt, thoughts: u.thoughts, candidates: u.candidates});
            if (!isUsable(response) && !emptyRetried && response.finishReason !== 'SAFETY') {
                emptyRetried = true;
                setTimeout(run, TRANSIENT_RETRY_DELAY_MS);
                return;
            }
            callback(null, response);
        });
    }
    run();
};

/** Plain words for Gemini's 429s: a model not on the free tier, the daily or the per-minute limit. */
function quotaMessage(body, model) {
    if (/limit:\s*0\b/.test(body)) {
        return model + ' isn\'t included with a free Gemini key. Pick another model in Billy\'s settings (on the watch: Settings, Model), or turn on billing in Google AI Studio.';
    }
    if (/PerDay/i.test(body) || /per day/i.test(body)) {
        return 'You\'ve used today\'s free Gemini requests for ' + model + '. Switch to 3.5 Flash-Lite (bigger free allowance) in Settings, wait until tomorrow, or turn on billing in Google AI Studio.';
    }
    if (/PerMinute/i.test(body) || /per minute/i.test(body)) {
        return 'Too many Gemini requests this minute for a free key. Wait a minute and ask again.';
    }
    return 'Gemini says this key is over its quota (429). Check your limits in Google AI Studio.';
}
exports._quotaMessage = quotaMessage;

function parseError(status, responseText, model) {
    var message = responseText;
    var code = '';
    try {
        var parsed = JSON.parse(responseText);
        if (parsed.error) {
            message = parsed.error.message || message;
            code = parsed.error.status || parsed.error.code || '';
        }
    } catch (e) {
        // keep raw text
    }
    message = String(message || 'unknown error');
    var lower = message.toLowerCase();
    var friendly = null;
    var shortMessage = message.replace(/\s+/g, ' ').substring(0, 200);
    if (status === 403 && lower.indexOf('blocked') !== -1) {
        friendly = 'Gemini API key is blocked. Allow the Generative Language API for this key.';
    } else if ((status === 400 || status === 401 || status === 403) &&
        (lower.indexOf('api key not valid') !== -1 || lower.indexOf('invalid authentication') !== -1)) {
        friendly = 'Gemini did not accept the API key. Use a key from Google AI Studio.';
    } else if (status === 429) {
        friendly = quotaMessage(String(responseText || message), model);
    } else if ((status === 400 || status === 403) && /grounding|google_?search|google_?maps/.test(lower) &&
        /free|billing|not available|not supported|not enabled/.test(lower)) {
        friendly = 'Your Gemini key can\'t use Google Search or Maps with ' + model + '. Free keys may not include them; turn on billing in Google AI Studio. Google said: ' + shortMessage;
    } else if (status === 503) {
        friendly = 'Gemini is overloaded right now (503).';
    } else if (status === 404) {
        friendly = 'Gemini does not know the model "' + model + '" (404). Check the model name in Billy\'s settings. Google said: ' + shortMessage;
    }
    var err = new Error((friendly || ('Gemini (' + model + ') returned error ' + status + ': ' + shortMessage)) + REPORT_HINT);
    err.status = status;
    err.code = code;
    err.messageText = message;
    return err;
}

exports.REPORT_HINT = REPORT_HINT;
exports.REQUEST_TIMEOUT_MS = REQUEST_TIMEOUT_MS;
exports.isGemini3 = isGemini3;
exports._buildBody = buildBody;
exports._parseResponse = parseResponse;
