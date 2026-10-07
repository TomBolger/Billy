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
// - The model is chosen once per turn (see pickModel) and then pinned, because
//   thought signatures from one model are not valid for another.
// - Model content is returned untouched so thought signatures survive.

var config = require('../config');

var BASE_URL = 'https://generativelanguage.googleapis.com/v1beta/models/';
var REQUEST_TIMEOUT_MS = 30000;
var MAX_OUTPUT_TOKENS = 4096;
var TRANSIENT_RETRY_DELAY_MS = 900;

function isGemini3(model) {
    return /^gemini-3/.test(String(model || ''));
}

exports.candidateModels = function() {
    var models = [config.getGeminiModel()];
    config.FALLBACK_MODELS.forEach(function(model) {
        if (models.indexOf(model) === -1) {
            models.push(model);
        }
    });
    return models;
};

function buildBody(model, contents, options) {
    var body = {
        contents: contents,
        generationConfig: {
            candidateCount: 1,
            maxOutputTokens: options.maxOutputTokens || MAX_OUTPUT_TOKENS
        }
    };
    if (isGemini3(model)) {
        body.generationConfig.thinkingConfig = {thinkingLevel: options.thinkingLevel || 'low'};
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
    // tier 0: Search + Maps + URL reading + code; tier 1: Search only; tier 2: none.
    var tier = options.builtInTier || 0;
    var builtIns = tier < 2 && (declarations.length === 0 || isGemini3(model));
    var useSearch = !!options.search && builtIns;
    var extras = useSearch && tier === 0 && declarations.length > 0;
    if (useSearch) {
        tools.push({googleSearch: {}});
    }
    if (extras) {
        tools.push({googleMaps: {}});
        tools.push({urlContext: {}});
        tools.push({codeExecution: {}});
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
        var err = new Error('Could not reach Gemini. Check the phone connection.');
        err.transient = true;
        finish(err);
    };
    req.ontimeout = function() {
        var err = new Error('Gemini took too long to answer.');
        err.transient = true;
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

// Sends one request to a specific model, retrying once on transient errors,
// and once without Google Search if this model rejects the tool combination.
exports.generateWithModel = function(model, contents, options, callback) {
    var apiKey = config.getGeminiApiKey();
    if (!apiKey) {
        callback(new Error('Add a Gemini API key in Billy settings.'));
        return;
    }
    var attempt = 0;
    var tier = 0;
    function run() {
        attempt++;
        var effective = {};
        Object.keys(options).forEach(function(key) {
            effective[key] = options[key];
        });
        effective.builtInTier = tier;
        post(apiKey, model, buildBody(model, contents, effective), function(err, raw) {
            if (err) {
                if (options.search && tier < 2 && err.status === 400 &&
                    /search|tool|server.?side|combination|function|maps|url|code/i.test(err.messageText || '')) {
                    tier++;
                    console.log('Gemini rejected built-in tools on ' + model + '; retrying with tier ' + tier + '.');
                    run();
                    return;
                }
                if (attempt < 2 && isTransient(err)) {
                    setTimeout(run, TRANSIENT_RETRY_DELAY_MS);
                    return;
                }
                callback(err);
                return;
            }
            var response = parseResponse(model, raw);
            if (!isUsable(response) && attempt < 2 && response.finishReason !== 'SAFETY') {
                setTimeout(run, TRANSIENT_RETRY_DELAY_MS);
                return;
            }
            callback(null, response);
        });
    }
    run();
};

// First call of a turn: try the configured model, then fall back to the next
// model only for availability problems. The caller must pin response.model for
// every later call in the same turn.
exports.generateFirst = function(contents, options, callback) {
    var models = exports.candidateModels();
    var lastErr = null;
    function next() {
        var model = models.shift();
        if (!model) {
            callback(lastErr || new Error('Gemini did not answer. Try again.'));
            return;
        }
        exports.generateWithModel(model, contents, options, function(err, response) {
            if (!err && isUsable(response)) {
                callback(null, response);
                return;
            }
            lastErr = err || new Error('Gemini returned an empty answer.');
            if (err && !shouldTryNextModel(err)) {
                callback(err);
                return;
            }
            console.log('Falling back from ' + model + ': ' + lastErr.message);
            next();
        });
    }
    next();
};

function isTransient(err) {
    return !!err.transient || err.status === 429 || err.status === 500 || err.status === 502 ||
        err.status === 503 || err.status === 504;
}

function shouldTryNextModel(err) {
    return isTransient(err) || err.status === 404 ||
        /overloaded|unavailable|high demand|not found|not supported/i.test(err.messageText || err.message || '');
}

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
    if (status === 403 && lower.indexOf('blocked') !== -1) {
        friendly = 'Gemini API key is blocked. Allow the Generative Language API for this key.';
    } else if ((status === 400 || status === 401 || status === 403) &&
        (lower.indexOf('api key not valid') !== -1 || lower.indexOf('invalid authentication') !== -1)) {
        friendly = 'Gemini did not accept the API key. Use a key from Google AI Studio.';
    } else if (status === 429) {
        friendly = 'Gemini rate limit or quota reached. Try again shortly.';
    }
    var err = new Error(friendly || ('Gemini error (' + model + '): ' + message.substring(0, 160)));
    err.status = status;
    err.code = code;
    err.messageText = message;
    return err;
}

exports.isGemini3 = isGemini3;
exports._buildBody = buildBody;
exports._parseResponse = parseResponse;
