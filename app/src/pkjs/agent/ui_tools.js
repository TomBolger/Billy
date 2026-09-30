/**
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

// Watch UI tools: the clarification picker and the big-number info card.

var messageKeys = require('message_keys');

function obj(properties, required) {
    return {type: 'object', properties: properties, required: required || []};
}

function str(description) {
    return {type: 'string', description: description};
}

exports.getDeclarations = function() {
    var optionMax = exports.pickerOptionMaxChars();
    return [
        {
            name: 'ask_clarifying_question',
            description: 'Show a picker so the user can choose an answer with the watch buttons (a "Dictate..." option is added automatically). Use ONLY when guessing could do the wrong thing (create/delete/send the wrong item) and no sensible default exists. Never use it for yes/no confirmation of something the user already asked for. Ends your turn; you will receive the answer as the next message.',
            parameters: obj({
                question: str('Short question that fits a watch screen.'),
                options: {
                    type: 'array',
                    items: {type: 'string'},
                    description: '2-3 likely answers, each ' + optionMax + ' characters or fewer.'
                }
            }, ['question', 'options'])
        },
        {
            name: 'show_number',
            description: 'Show a large number card on the watch. Use for any answer that is essentially one number or amount: calculations, unit or currency conversions, "how many days until...", scores, prices, distances, percentages. Call it before your short text reply.',
            parameters: obj({
                value: str('The number exactly as it should be displayed, with separators or currency symbol (e.g. "$1,234.50", "42", "3.7 km").'),
                label: str('Short caption under the number (e.g. "EUR to USD", "days until Christmas").')
            }, ['value'])
        }
    ];
};

exports.handles = function(name) {
    return name === 'ask_clarifying_question' || name === 'show_number';
};

exports.execute = function(session, name, args, callback) {
    args = args || {};
    if (name === 'show_number') {
        var value = String(args.value !== undefined ? args.value : args.number || '').trim();
        if (!value) {
            callback({status: 'error', summary: 'show_number needs a value.'});
            return true;
        }
        var card = {HIGHLIGHT_WIDGET: 1, HIGHLIGHT_WIDGET_PRIMARY: value.substring(0, 24)};
        var label = String(args.label || args.unit || '').trim();
        if (label) {
            card.HIGHLIGHT_WIDGET_SECONDARY = label.substring(0, 40);
        }
        session.enqueue(card);
        callback({status: 'ok', summary: 'Number card shown.', watch_card: 'The number is on screen; reply with one short line of context at most.'});
        return true;
    }
    if (name !== 'ask_clarifying_question') {
        return false;
    }
    exports.sendClarification(session, {
        question: args.question,
        options: Array.isArray(args.options) ? args.options : []
    });
    callback({status: 'waiting_for_user', stop_for_user: true});
    return true;
};

exports.sendClarification = function(session, card) {
    var max = exports.pickerOptionMaxChars();
    var options = (card.options || []).map(function(option) {
        return shortLabel(option, max);
    }).filter(function(option, index, list) {
        return option.length > 0 && list.indexOf(option) === index && !/^dictate/i.test(option);
    }).slice(0, 3);
    if (options.length === 0) {
        options = ['Yes', 'No'];
    }
    options.push('Dictate...');
    var message = {
        CLARIFY_WIDGET: 1,
        CLARIFY_QUESTION: String(card.question || 'Which one?').substring(0, 120),
        // The answer comes back in the same thread, so no hidden context is
        // needed; keep a short copy of the question for the answer prompt.
        CLARIFY_CONTEXT: 'companionless',
        CLARIFY_OPTION_COUNT: options.length
    };
    for (var i = 0; i < options.length; i++) {
        message[messageKeys.CLARIFY_OPTION_0 + i] = options[i];
    }
    session.enqueue(message);
    session.enqueue({CHAT_DONE: true});
};

exports.pickerOptionMaxChars = function() {
    var platform = '';
    try {
        platform = Pebble && Pebble.platform ? Pebble.platform : '';
    } catch (e) {
        platform = '';
    }
    if (platform === 'emery') {
        return 28;
    }
    if (platform === 'basalt') {
        return 20;
    }
    return 18;
};

function shortLabel(option, maxChars) {
    var label = String(option || '').split('|')[0].replace(/\s+/g, ' ').trim();
    if (label.length <= maxChars) {
        return label;
    }
    return label.substring(0, Math.max(0, maxChars - 3)).replace(/\s+$/, '') + '...';
}
