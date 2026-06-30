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

var config = require('../config');

var INTERACTIONS_URL = 'https://generativelanguage.googleapis.com/v1beta/interactions';
var GENERATE_CONTENT_BASE_URL = 'https://generativelanguage.googleapis.com/v1beta/models/';
var FALLBACK_MODELS = ['gemini-3.1-flash-lite', 'gemini-3.5-flash', 'gemini-2.5-flash'];
var GEMINI_REQUEST_TIMEOUT_MS = 45000;

function extractText(response) {
    if (response.output_text) {
        return response.output_text;
    }
    if (response.output && Array.isArray(response.output)) {
        return response.output.map(function(item) {
            return item.text || '';
        }).join('');
    }
    if (response.steps && Array.isArray(response.steps)) {
        var stepParts = [];
        response.steps.forEach(function(step) {
            var output = step;
            if (step.model_output) {
                output = step.model_output;
            } else if (step.modelOutput) {
                output = step.modelOutput;
            } else if (step.type !== 'model_output') {
                output = null;
            }
            if (!output) {
                return;
            }
            if (output.text) {
                stepParts.push(output.text);
                return;
            }
            if (!output.content) {
                return;
            }
            output.content.forEach(function(content) {
                if (content.type === 'text' && content.text) {
                    stepParts.push(content.text);
                }
            });
        });
        if (stepParts.length > 0) {
            return stepParts.join('');
        }
    }
    var parts = [];
    if (!response.candidates) {
        return '';
    }
    response.candidates.forEach(function(candidate) {
        if (!candidate.content || !candidate.content.parts) {
            return;
        }
        candidate.content.parts.forEach(function(part) {
            if (part.text) {
                parts.push(part.text);
            }
        });
    });
    return parts.join('');
}

function buildGenerateContentContents(input) {
    if (typeof input === 'string') {
        return [{
            role: 'user',
            parts: [{text: input}]
        }];
    }
    if (!input || !Array.isArray(input)) {
        return [{
            role: 'user',
            parts: [{text: String(input || '')}]
        }];
    }
    var contents = [];
    input.forEach(function(item) {
        if (item.role && item.parts) {
            contents.push(item);
            return;
        }
        if (item.type === 'user_input') {
            contents.push({
                role: 'user',
                parts: buildTextParts(item.content)
            });
            return;
        }
        if (item.type === 'function_result') {
            contents.push({
                role: 'user',
                parts: [{
                    functionResponse: {
                        id: item.call_id || undefined,
                        name: item.name,
                        response: buildFunctionResponse(item.result)
                    }
                }]
            });
        }
    });
    return contents;
}

function buildInteractionText(input) {
    if (typeof input === 'string') {
        return input;
    }
    if (!input || !Array.isArray(input)) {
        return String(input || '');
    }
    var textParts = [];
    input.forEach(function(item) {
        if (!item) {
            return;
        }
        if (item.type === 'text' && item.text) {
            textParts.push(item.text);
            return;
        }
        if (item.type === 'user_input') {
            textParts.push(extractPlainText(item.content));
            return;
        }
        if (item.type === 'function_result') {
            textParts.push('Tool ' + item.name + ' result: ' + JSON.stringify(buildFunctionResponse(item.result)));
            return;
        }
        if (item.parts) {
            textParts.push(extractPlainText(item.parts));
            return;
        }
        if (item.content) {
            textParts.push(extractPlainText(item.content));
        }
    });
    return textParts.join('\n\n');
}

function buildInteractionContentList(input) {
    return [{type: 'text', text: buildInteractionText(input)}];
}

function extractPlainText(content) {
    if (typeof content === 'string') {
        return content;
    }
    if (!Array.isArray(content)) {
        return String(content || '');
    }
    var parts = [];
    content.forEach(function(part) {
        if (!part) {
            return;
        }
        if (part.text) {
            parts.push(part.text);
        } else if (part.type === 'text' && part.text) {
            parts.push(part.text);
        }
    });
    return parts.join('\n');
}

function buildTextParts(content) {
    var parts = [];
    if (!Array.isArray(content)) {
        return [{text: String(content || '')}];
    }
    content.forEach(function(part) {
        if (part.type === 'text' && part.text) {
            parts.push({text: part.text});
        }
    });
    if (parts.length === 0) {
        parts.push({text: ''});
    }
    return parts;
}

function buildFunctionResponse(result) {
    if (!Array.isArray(result) || result.length === 0) {
        return {};
    }
    var first = result[0];
    if (first.type === 'text') {
        try {
            return JSON.parse(first.text);
        } catch (e) {
            return {result: first.text || ''};
        }
    }
    return {result: result};
}

function buildGenerateContentTools(options) {
    var tools = [];
    if (options.enableSearchGrounding) {
        tools.push({google_search: {}});
    }
    if (options.tools && options.tools.length > 0) {
        var declarations = [];
        options.tools.forEach(function(tool) {
            if (tool.type === 'function') {
                declarations.push({
                    name: tool.name,
                    description: tool.description,
                    parameters: tool.parameters
                });
            }
        });
        if (declarations.length > 0) {
            tools.push({function_declarations: declarations});
        }
    }
    return tools;
}

function extractFunctionCalls(response) {
    var calls = [];
    if (response.steps && Array.isArray(response.steps)) {
        response.steps.forEach(function(step) {
            var call = step.function_call || step.functionCall || (step.type === 'function_call' ? step : null);
            if (call && call.name) {
                calls.push({
                    id: call.id,
                    name: call.name,
                    arguments: call.arguments || call.args || {}
                });
            }
        });
    }
    if (!response.candidates || !Array.isArray(response.candidates)) {
        return calls;
    }
    response.candidates.forEach(function(candidate) {
        if (!candidate.content || !candidate.content.parts) {
            return;
        }
        candidate.content.parts.forEach(function(part) {
            var call = part.functionCall || part.function_call;
            if (call) {
                calls.push({
                    id: call.id,
                    name: call.name,
                    arguments: call.args || call.arguments || {}
                });
            }
        });
    });
    return calls;
}

function extractHistoryItems(response) {
    if (response.steps && Array.isArray(response.steps)) {
        return response.steps;
    }
    var items = [];
    if (!response.candidates || !Array.isArray(response.candidates)) {
        return items;
    }
    response.candidates.forEach(function(candidate) {
        if (candidate.content) {
            items.push(candidate.content);
        }
    });
    return items;
}

function hasGenerateContentToolError(error) {
    var text = (error.message + ' ' + error.code + ' ' + error.messageText).toLowerCase();
    return error.status === 400 &&
        (text.indexOf('tool') !== -1 ||
            text.indexOf('function') !== -1 ||
            text.indexOf('google_search') !== -1 ||
            text.indexOf('function_declarations') !== -1);
}

function generateInteractions(apiKey, model, input, options, callback) {
    generateInteractionsWithInput(apiKey, model, buildInteractionText(input), options, function(err, response) {
        if (!err || !shouldRetryInteractionWithContentList(err)) {
            callback(err, response);
            return;
        }
        generateInteractionsWithInput(apiKey, model, buildInteractionContentList(input), options, callback);
    });
}

function generateInteractionsWithInput(apiKey, model, interactionInput, options, callback) {
    var body = {
        model: model,
        input: interactionInput,
        store: false
    };

    if (options.systemInstruction) {
        body.system_instruction = options.systemInstruction;
    }

    body.tools = [];

    if (options.enableSearchGrounding) {
        body.tools.push({type: 'google_search'});
    }

    if (options.tools) {
        body.tools = body.tools.concat(options.tools);
    }

    if (body.tools.length === 0) {
        delete body.tools;
    }

    var finished = false;
    var req = new XMLHttpRequest();
    function finish(err, value) {
        if (finished) {
            return;
        }
        finished = true;
        callback(err, value);
    }
    req.open('POST', INTERACTIONS_URL, true);
    req.timeout = GEMINI_REQUEST_TIMEOUT_MS;
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
            var response = JSON.parse(req.responseText);
            finish(null, {
                text: extractText(response),
                functionCalls: extractFunctionCalls(response),
                interactionId: response.id || null,
                historyItems: extractHistoryItems(response),
                raw: response
            });
        } catch (e) {
            finish(e);
        }
    };
    req.onerror = function() {
        finish(new Error('Gemini request failed before a response was received.'));
    };
    req.ontimeout = function() {
        finish(new Error('Gemini request timed out. Please try again.'));
    };
    req.send(JSON.stringify(body));
}

exports.generate = function(input, options, callback) {
    var apiKey = config.getGeminiApiKey();
    if (!apiKey) {
        callback(new Error('Gemini API key is not configured. Open Billy settings and add a Gemini API key.'));
        return;
    }

    options = options || {};
    var models = [config.getGeminiModel()];
    FALLBACK_MODELS.forEach(function(model) {
        if (models.indexOf(model) === -1) {
            models.push(model);
        }
    });

    generateWithModels(apiKey, models, input, options, callback);
}

function generateWithModels(apiKey, models, input, options, callback) {
    var model = models.shift();
    generateContent(apiKey, model, models.slice(0), input, options, function(err, response) {
        if (!err) {
            if (hasUsableResponse(response)) {
                callback(null, response);
                return;
            }
            if (models.length > 0) {
                generateWithModels(apiKey, models, input, options, callback);
                return;
            }
            callback(new Error('Gemini returned an empty answer. Please try again.'));
            return;
        }
        if (shouldTryGenerateContentFallback(err)) {
            generateInteractions(apiKey, model, input, options, function(interactionsErr, interactionsResponse) {
                if (!interactionsErr && hasUsableResponse(interactionsResponse)) {
                    callback(null, interactionsResponse);
                    return;
                }
                var fallbackErr = interactionsErr || new Error('Gemini returned an empty answer. Please try again.');
                if (models.length > 0 && shouldFallback(fallbackErr)) {
                    generateWithModels(apiKey, models, input, options, callback);
                    return;
                }
                callback(fallbackErr);
            });
            return;
        }
        if (models.length > 0 && shouldFallback(err)) {
            generateWithModels(apiKey, models, input, options, callback);
            return;
        }
        callback(err);
    });
}

function hasUsableResponse(response) {
    return !!(response &&
        ((response.text && response.text.trim && response.text.trim().length > 0) ||
            (response.functionCalls && response.functionCalls.length > 0)));
}

function generateContent(apiKey, model, models, input, options, callback) {
    var body = {
        contents: buildGenerateContentContents(input),
        generationConfig: {
            candidateCount: 1,
            maxOutputTokens: 700
        }
    };
    if (options.systemInstruction) {
        body.systemInstruction = {
            parts: [{text: options.systemInstruction}]
        };
    }
    var tools = buildGenerateContentTools(options);
    if (tools.length > 0) {
        body.tools = tools;
    }

    var finished = false;
    var req = new XMLHttpRequest();
    function finish(err, value) {
        if (finished) {
            return;
        }
        finished = true;
        callback(err, value);
    }
    req.open('POST', GENERATE_CONTENT_BASE_URL + encodeURIComponent(model) + ':generateContent', true);
    req.timeout = GEMINI_REQUEST_TIMEOUT_MS;
    req.setRequestHeader('Content-Type', 'application/json');
    req.setRequestHeader('x-goog-api-key', apiKey);
    req.onload = function() {
        if (req.readyState !== 4) {
            return;
        }
        if (req.status < 200 || req.status >= 300) {
            var error = parseError(req.status, req.responseText, model);
            if (models.length > 0 && shouldFallback(error)) {
                generateContent(apiKey, models.shift(), models, input, options, callback);
                return;
            }
            finish(error);
            return;
        }
        try {
            var response = JSON.parse(req.responseText);
            finish(null, {
                text: extractText(response),
                functionCalls: extractFunctionCalls(response),
                interactionId: null,
                historyItems: extractHistoryItems(response),
                raw: response
            });
        } catch (e) {
            finish(e);
        }
    };
    req.onerror = function() {
        finish(new Error('Gemini request failed before a response was received.'));
    };
    req.ontimeout = function() {
        finish(new Error('Gemini request timed out. Please try again.'));
    };
    req.send(JSON.stringify(body));
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
        // Keep the raw response text.
    }
    message = message || 'unknown error';
    var lowerMessage = message.toLowerCase();
    if (status === 403 && lowerMessage.indexOf('generativelanguage.googleapis.com') !== -1 && lowerMessage.indexOf('blocked') !== -1) {
        message = 'Gemini API key is blocked. Enable the Gemini API and allow generativelanguage.googleapis.com. For companionless mode, do not restrict the key to Billy Companion package/SHA.';
    }
    if ((status === 401 || status === 403) && lowerMessage.indexOf('invalid authentication credentials') !== -1) {
        message = 'Gemini did not accept this value as an API key. Use a Gemini API key from Google AI Studio, not an OAuth client ID, client secret, or access token.';
    }
    if (message.length > 180) {
        message = message.substring(0, 177) + '...';
    }
    var err = new Error('Gemini ' + model + ' failed: ' + message + (code ? ' (' + code + ')' : ''));
    err.status = status;
    err.code = code;
    err.messageText = message;
    return err;
}

function shouldFallback(error) {
    var text = (error.message + ' ' + error.code + ' ' + error.messageText).toLowerCase();
    return error.status === 429 ||
        error.status === 502 ||
        error.status === 500 ||
        error.status === 503 ||
        error.status === 404 ||
        text.indexOf('empty answer') !== -1 ||
        text.indexOf('api_error') !== -1 ||
        text.indexOf('high demand') !== -1 ||
        text.indexOf('overloaded') !== -1 ||
        text.indexOf('unavailable') !== -1;
}

function shouldTryGenerateContentFallback(error) {
    var text = (error.message + ' ' + error.code + ' ' + error.messageText).toLowerCase();
    return error.status === 400 &&
        (text.indexOf('tool') !== -1 ||
            text.indexOf('function') !== -1 ||
            text.indexOf('input') !== -1 ||
            text.indexOf('top-level') !== -1 ||
            text.indexOf('top level') !== -1 ||
            text.indexOf('list') !== -1 ||
            text.indexOf('unsupported') !== -1);
}

function shouldRetryInteractionWithContentList(error) {
    var text = (error.message + ' ' + error.code + ' ' + error.messageText).toLowerCase();
    return error.status === 400 &&
        (text.indexOf('input') !== -1 ||
            text.indexOf('top-level') !== -1 ||
            text.indexOf('top level') !== -1 ||
            text.indexOf('list') !== -1);
}
