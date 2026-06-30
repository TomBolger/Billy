/**
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

var messageKeys = require('message_keys');
var osmMapTool = require('./osm_map_tool');
var weatherTool = require('./weather_tool');

function schema(properties, required) {
    return {
        type: 'object',
        properties: properties,
        required: required || []
    };
}

function stringSchema(description) {
    return {
        type: 'string',
        description: description
    };
}

function arraySchema(description) {
    return {
        type: 'array',
        description: description,
        items: {type: 'string'}
    };
}

exports.getDeclarations = function() {
    return [{
        type: 'function',
        name: 'ask_clarifying_question',
        description: 'Ask the user one short clarifying question with 2-4 selectable options when guessing would risk doing the wrong thing.',
        parameters: schema({
            question: stringSchema('Short watch-sized question.'),
            options: arraySchema('Two to four short answer options.'),
            context: stringSchema('The original user request or enough hidden context to continue after the user chooses.')
        }, ['question', 'options'])
    }].concat(osmMapTool.getDeclarations()).concat(weatherTool.getDeclarations());
};

exports.execute = function(session, call, callback) {
    if (weatherTool.execute(session, call, callback)) {
        return true;
    }
    if (osmMapTool.execute(session, call, callback)) {
        return true;
    }
    if (call.name !== 'ask_clarifying_question') {
        return false;
    }
    var args = call.arguments || {};
    if (typeof args === 'string') {
        try {
            args = JSON.parse(args);
        } catch (e) {
            args = {};
        }
    }
    var options = Array.isArray(args.options) ? args.options.slice(0, 4) : [];
    options = options.filter(function(option) {
        return String(option || '').trim().length > 0;
    });
    if (options.length < 2) {
        options = ['Yes', 'No'];
    }
    exports.sendClarification(session, {
        question: args.question,
        context: args.context,
        options: options
    });
    callback({
        stop_for_user: true,
        summary: 'Asked the user a clarifying question.'
    });
    return true;
};

exports.sendClarification = function(session, card) {
    card = card || {};
    var options = Array.isArray(card.options) ? card.options.slice(0, 4) : [];
    options = options.filter(function(option) {
        return String(option || '').trim().length > 0;
    });
    if (options.length < 2) {
        options = ['Yes', 'No'];
    }
    var message = {
        CLARIFY_WIDGET: 1,
        CLARIFY_QUESTION: String(card.question || 'Which option?').substring(0, 120),
        CLARIFY_CONTEXT: String(card.context || session.prompt || '').substring(0, 180),
        CLARIFY_OPTION_COUNT: options.length
    };
    for (var i = 0; i < options.length; i++) {
        message[messageKeys.CLARIFY_OPTION_0 + i] = String(options[i]).substring(0, 64);
    }
    session.enqueue(message);
    session.enqueue({CHAT_DONE: true});
};
