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

// Per-turn timing diagnostics for bug reports.
//
// Privacy rule: this file only ever stores numbers, model names, tool names,
// HTTP statuses and short fixed codes. Never prompt text, replies, tool
// arguments or tool results. Error text is limited to our own error messages
// (which quote Google's error, never the user's words) and is truncated.

var STORAGE_KEY = 'billyDiagTurns';
var MAX_TURNS = 20;
var MAX_ERROR_CHARS = 160;
var MAX_CALLS = 12;

function load() {
    try {
        var raw = localStorage.getItem(STORAGE_KEY);
        var list = raw ? JSON.parse(raw) : [];
        return Object.prototype.toString.call(list) === '[object Array]' ? list : [];
    } catch (e) {
        return [];
    }
}

function save(list) {
    try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(list.slice(-MAX_TURNS)));
    } catch (e) {
        // best effort
    }
}

// path: which runtime path was taken, e.g. 'phone-companionless',
// 'phone-no-companion', 'companion-claimed', 'error-companion-silent'.
exports.begin = function(path, claimWaitMs) {
    return {
        id: Date.now() + '-' + Math.floor(Math.random() * 1000),
        at: new Date().toISOString(),
        startedMs: Date.now(),
        path: path,
        claimWaitMs: claimWaitMs || 0,
        gemini: [],
        tools: [],
        totalMs: 0,
        outcome: 'running',
        error: ''
    };
};

exports.recordGemini = function(turn, rec) {
    if (!turn || turn.gemini.length >= MAX_CALLS) {
        return;
    }
    turn.gemini.push({
        model: String(rec.model || ''),
        ms: rec.ms,
        status: rec.status,
        retry: rec.retry || 0,
        finishReason: rec.finishReason || (rec.error ? String(rec.error) : ''),
        prompt: rec.prompt === undefined ? null : rec.prompt,
        thoughts: rec.thoughts === undefined ? null : rec.thoughts,
        candidates: rec.candidates === undefined ? null : rec.candidates
    });
};

exports.recordTool = function(turn, name, ms, status) {
    if (!turn || turn.tools.length >= MAX_CALLS) {
        return;
    }
    turn.tools.push({name: String(name || ''), ms: ms, status: String(status || '')});
};

exports.end = function(turn, outcome, error) {
    if (!turn || turn.ended) {
        return;
    }
    turn.ended = true;
    turn.totalMs = Date.now() - turn.startedMs;
    turn.outcome = outcome;
    turn.error = error ? String(error).substring(0, MAX_ERROR_CHARS) : '';
    var list = load();
    list.push({
        id: turn.id, at: turn.at, path: turn.path, claimWaitMs: turn.claimWaitMs,
        gemini: turn.gemini, tools: turn.tools, totalMs: turn.totalMs,
        outcome: turn.outcome, error: turn.error
    });
    save(list);
};

// Marks an already-saved turn: the companion claimed after we gave up on it.
exports.noteLateClaim = function(turnId) {
    var list = load();
    for (var i = list.length - 1; i >= 0; i--) {
        if (list[i].id === turnId) {
            list[i].lateClaim = true;
            save(list);
            return;
        }
    }
};

exports.recent = function(count) {
    var list = load();
    return list.slice(Math.max(0, list.length - (count || MAX_TURNS)));
};

// One short, plain line per turn for the bug report.
exports.formatTurn = function(t) {
    var line = '- ' + t.at.substring(5, 16).replace('T', ' ') + 'Z path=' + t.path;
    if (t.claimWaitMs) {
        line += ' claimWait=' + t.claimWaitMs + 'ms';
    }
    line += ' total=' + t.totalMs + 'ms outcome=' + t.outcome;
    if (t.lateClaim) {
        line += ' LATE-COMPANION-CLAIM';
    }
    if (t.error) {
        line += ' error="' + t.error.replace(/["\n]/g, ' ') + '"';
    }
    (t.gemini || []).forEach(function(g) {
        line += '\n  - gemini ' + g.model + ' ' + g.ms + 'ms http=' + g.status + ' retry=' + g.retry +
            (g.finishReason ? ' finish=' + g.finishReason : '') +
            ' tokens(prompt/thoughts/out)=' + [g.prompt, g.thoughts, g.candidates].join('/');
    });
    (t.tools || []).forEach(function(c) {
        line += '\n  - tool ' + c.name + ' ' + c.ms + 'ms ' + c.status;
    });
    return line;
};

exports.formatRecent = function(count) {
    return exports.recent(count).map(exports.formatTurn).join('\n');
};

exports.lastPath = function() {
    var list = exports.recent(1);
    return list.length ? list[0].path : 'none yet';
};

exports.STORAGE_KEY = STORAGE_KEY;
