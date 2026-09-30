/**
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

// Runs watch tools (alarms, timers, reminders, settings) on behalf of the
// Android companion. The companion cannot talk to this phone JS directly, so
// the watch bounces messages both ways:
//
//   Android --JS_TOOL_REQUEST--> watch --JS_TOOL_REQUEST--> phone JS (here)
//   phone JS --JS_TOOL_RESULT--> watch --JS_TOOL_RESULT--> Android
//
// Payloads are compact JSON strings: {"id":"...","name":"set_timer","args":{...}}
// and {"id":"...","result":{...}}.

var messageQueue = require('../lib/message_queue').Queue;
var watchTools = require('./watch_tools');
var uiTools = require('./ui_tools');

var MAX_RESULT_CHARS = 900;
var handled = {};

function relaySession() {
    return {
        prompt: '',
        // Progress text is owned by the companion's conversation.
        handleMessage: function() {},
        enqueue: function(message) {
            messageQueue.enqueue(message);
        }
    };
}

function compact(result) {
    var text = JSON.stringify(result);
    if (text.length <= MAX_RESULT_CHARS) {
        return result;
    }
    // Shrink lists (e.g. many reminders) rather than cutting JSON in half.
    var copy = JSON.parse(text);
    ['reminders', 'alarms', 'timers'].forEach(function(key) {
        if (Array.isArray(copy[key])) {
            copy[key] = copy[key].map(function(item) {
                var small = {};
                Object.keys(item).forEach(function(k) {
                    var v = item[k];
                    small[k] = typeof v === 'string' ? v.substring(0, 60) : v;
                });
                return small;
            });
            while (JSON.stringify(copy).length > MAX_RESULT_CHARS && copy[key].length > 1) {
                copy[key].pop();
                copy.truncated = true;
            }
        }
    });
    if (JSON.stringify(copy).length > MAX_RESULT_CHARS) {
        return {status: copy.status || 'ok', summary: String(copy.summary || 'Result too long for the watch relay.').substring(0, 300)};
    }
    return copy;
}

function reply(id, result) {
    messageQueue.enqueue({JS_TOOL_RESULT: JSON.stringify({id: id, result: compact(result)})});
}

exports.handleRequest = function(raw) {
    var request;
    try {
        request = JSON.parse(raw);
    } catch (e) {
        console.log('Bad relay request: ' + raw);
        return;
    }
    var id = String(request.id || '');
    if (!id) {
        return;
    }
    if (handled[id]) {
        // The watch retried a bounce; answer again without re-running the tool.
        reply(id, handled[id]);
        return;
    }
    var name = String(request.name || '');
    console.log('Relay tool request ' + id + ': ' + name);
    var done = function(result) {
        handled[id] = result;
        reply(id, result);
    };
    if (watchTools.handles(name)) {
        watchTools.execute(relaySession(), name, request.args || {}, done);
    } else if (name === 'show_number') {
        uiTools.execute(relaySession(), name, request.args || {}, done);
    } else {
        reply(id, {status: 'error', summary: 'The phone cannot run ' + name + '.'});
    }
};
