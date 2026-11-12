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

// Per-conversation memory for the phone runtime. Earlier turns are replayed to
// Gemini as real user/model turns (not pasted into one prompt), and each model
// turn carries a short note of the actions it took, so follow-ups like
// "cancel that" or "make it 10 minutes instead" have something to refer to.

var PREFIX = 'billy-local-thread:';
var INDEX_KEY = 'billy-local-thread-index';
var MAX_TURNS = 8;
var MAX_THREADS = 12;
var MAX_TEXT = 700;

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
        var turns = raw ? JSON.parse(raw) : [];
        return Array.isArray(turns) ? turns : [];
    } catch (e) {
        return [];
    }
}

function save(threadId, turns) {
    if (!threadId) {
        return;
    }
    try {
        localStorage.setItem(PREFIX + threadId, JSON.stringify(turns.slice(-MAX_TURNS)));
        var index = JSON.parse(localStorage.getItem(INDEX_KEY) || '[]').filter(function(id) {
            return id !== threadId;
        });
        index.push(threadId);
        while (index.length > MAX_THREADS) {
            localStorage.removeItem(PREFIX + index.shift());
        }
        localStorage.setItem(INDEX_KEY, JSON.stringify(index));
    } catch (e) {
        console.log('Failed to save local thread: ' + e.message);
    }
}

function clip(text) {
    text = String(text || '');
    return text.length > MAX_TEXT ? text.substring(0, MAX_TEXT) + '...' : text;
}

exports.ensureThreadId = function(session) {
    if (session.threadId) {
        return session.threadId;
    }
    session.threadId = createThreadId();
    session.handleMessage({data: 't' + session.threadId});
    return session.threadId;
};

// Earlier turns as Gemini contents.
exports.contents = function(threadId) {
    var contents = [];
    load(threadId).forEach(function(turn) {
        var modelText = clip(turn.model || turn.assistant || '');
        if (turn.actions && turn.actions.length) {
            modelText += '\n[Actions taken: ' + turn.actions.join('; ') + ']';
        }
        contents.push({role: 'user', parts: [{text: clip(turn.user)}]});
        contents.push({role: 'model', parts: [{text: modelText || '(no reply)'}]});
    });
    return contents;
};

exports.recordTurn = function(threadId, userText, modelText, actions) {
    var turns = load(threadId);
    turns.push({
        user: clip(userText),
        model: clip(modelText),
        actions: (actions || []).slice(0, 6)
    });
    save(threadId, turns);
};

exports.hasTurns = function(threadId) {
    return load(threadId).length > 0;
};
