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

var PREFIX = 'billy-local-thread:';
var MAX_TURNS = 6;

function randomHex(count) {
    var out = '';
    for (var i = 0; i < count; i++) {
        out += Math.floor(Math.random() * 16).toString(16);
    }
    return out;
}

function createThreadId() {
    return [
        randomHex(8),
        randomHex(4),
        '4' + randomHex(3),
        (8 + Math.floor(Math.random() * 4)).toString(16) + randomHex(3),
        randomHex(12)
    ].join('-');
}

function load(threadId) {
    if (!threadId) {
        return [];
    }
    try {
        var raw = localStorage.getItem(PREFIX + threadId);
        return raw ? JSON.parse(raw) : [];
    } catch (e) {
        console.log('Failed to load local thread: ' + e.message);
        return [];
    }
}

function save(threadId, turns) {
    if (!threadId) {
        return;
    }
    try {
        localStorage.setItem(PREFIX + threadId, JSON.stringify(turns.slice(-MAX_TURNS)));
    } catch (e) {
        console.log('Failed to save local thread: ' + e.message);
    }
}

exports.ensureThreadId = function(session) {
    if (session.threadId) {
        return session.threadId;
    }
    session.threadId = createThreadId();
    session.handleMessage({data: 't' + session.threadId});
    return session.threadId;
}

exports.buildInput = function(threadId, prompt) {
    var turns = load(threadId);
    if (turns.length === 0) {
        return prompt;
    }
    var lines = ['Recent local conversation context:'];
    turns.forEach(function(turn) {
        lines.push('User: ' + turn.user);
        lines.push('Billy: ' + turn.assistant);
    });
    lines.push('Current user request: ' + prompt);
    return lines.join('\n');
}

exports.recordTurn = function(threadId, userPrompt, assistantText) {
    var turns = load(threadId);
    turns.push({
        user: userPrompt,
        assistant: assistantText
    });
    save(threadId, turns);
}

exports.hasTurns = function(threadId) {
    return load(threadId).length > 0;
}
