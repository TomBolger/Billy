// Offline regression tests for Billy's phone-side agent (app/src/pkjs/agent).
//
// Runs the real agent code against a fake Pebble runtime, fake watch, and a
// scripted fake Gemini. No network, no SDK needed:
//
//   node tools/pkjs-tests/agent.test.js
//
// Each test scripts what "Gemini" returns and checks what Billy sends back to
// Gemini and to the watch.

'use strict';

var path = require('path');
var fs = require('fs');
var Module = require('module');
var assert = require('assert');

var APP = path.resolve(__dirname, '../../app');
var PKJS = path.join(APP, 'src/pkjs');

// ---- message keys, assigned like the Pebble SDK: array keys first, then scalars.
var MESSAGE_KEYS = (function() {
    var keys = require(path.join(APP, 'package.json')).pebble.messageKeys.map(function(k) {
        return k.trim();
    });
    var n = 10000;
    var map = {};
    keys.filter(function(k) { return /\[/.test(k); }).forEach(function(k) {
        var m = /^(\w+)\[(\d+)\]$/.exec(k);
        map[m[1]] = n;
        n += parseInt(m[2], 10);
    });
    keys.filter(function(k) { return !/\[/.test(k); }).forEach(function(k) {
        map[k] = n++;
    });
    return map;
})();

var originalResolve = Module._resolveFilename;
Module._resolveFilename = function(request, parent) {
    if (request === 'message_keys') {
        return path.join(__dirname, '__message_keys__');
    }
    if (request === 'package.json') {
        return path.join(APP, 'package.json');
    }
    return originalResolve.apply(this, arguments);
};
require.cache[path.join(__dirname, '__message_keys__')] = {
    id: 'message_keys', filename: 'message_keys', loaded: true, exports: MESSAGE_KEYS
};

// ---- fake browser/Pebble globals ---------------------------------------------
global.window = global;
Object.defineProperty(global, 'navigator', {value: {language: 'en-US', geolocation: {getCurrentPosition: function() {}}}, configurable: true});
var store = {};
global.localStorage = {
    getItem: function(k) { return Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null; },
    setItem: function(k, v) { store[k] = String(v); },
    removeItem: function(k) { delete store[k]; },
    key: function(i) { return Object.keys(store)[i]; },
    get length() { return Object.keys(store).length; }
};

var watch = {sent: [], listeners: []};
global.Pebble = {
    platform: 'emery',
    addEventListener: function(name, fn) { if (name === 'appmessage') { watch.listeners.push(fn); } },
    removeEventListener: function(name, fn) { watch.listeners = watch.listeners.filter(function(l) { return l !== fn; }); },
    sendAppMessage: function(message, ok) {
        watch.sent.push(message);
        setTimeout(function() {
            if (ok) { ok(); }
            fakeWatchReact(message);
        }, 1);
    },
    showSimpleNotificationOnPebble: function() {}
};

// The fake watch answers alarm/timer requests the way alarms/manager.c does.
function fakeWatchReact(message) {
    if ('SET_ALARM_TIME' in message || 'CANCEL_ALARM_TIME' in message) {
        emitFromWatch({SET_ALARM_RESULT: 0});
    }
    if ('GET_ALARM_OR_TIMER' in message) {
        emitFromWatch({GET_ALARM_RESULT: 0, CURRENT_TIME: Math.round(Date.now() / 1000)});
    }
}

function emitFromWatch(payload) {
    var copy = {};
    Object.keys(payload).forEach(function(k) {
        copy[k] = payload[k];
        if (MESSAGE_KEYS[k] !== undefined) {
            copy[MESSAGE_KEYS[k]] = payload[k];
        }
    });
    watch.listeners.slice().forEach(function(fn) { fn({payload: copy}); });
}

// ---- scripted fake Gemini -------------------------------------------------------
var gemini = {requests: [], script: []};
var httpRoutes = {};
global.XMLHttpRequest = function() {
    var self = this;
    this.open = function(method, url) { self.url = url; };
    this.setRequestHeader = function() {};
    this.overrideMimeType = function() {};
    this.send = function(body) {
        var parsed = body ? JSON.parse(body) : null;
        var isGemini = /generativelanguage/.test(self.url);
        if (isGemini) {
            gemini.requests.push({url: self.url, body: parsed});
        }
        setTimeout(function() {
            var reply = isGemini ? gemini.script.shift() : null;
            if (!isGemini) {
                var routes = Object.keys(httpRoutes);
                for (var r = 0; r < routes.length && !reply; r++) {
                    if (new RegExp(routes[r]).test(self.url)) {
                        reply = httpRoutes[routes[r]](self.url);
                    }
                }
                reply = reply || {status: 200, json: {}};
            }
            if (reply && reply.bytes) {
                self.readyState = 4;
                self.status = 200;
                self.response = reply.bytes.buffer.slice(reply.bytes.byteOffset, reply.bytes.byteOffset + reply.bytes.length);
                self.responseText = '';
                self.onload();
                return;
            }
            if (reply && reply.text !== undefined) {
                self.readyState = 4;
                self.status = 200;
                self.responseText = reply.text;
                self.onload();
                return;
            }
            if (typeof reply === 'function') {
                reply = reply(parsed, self.url);
            }
            if (reply && reply.networkError) {
                self.status = 0;
                self.onerror();
                return;
            }
            if (reply && reply.timeout) {
                self.status = 0;
                self.ontimeout();
                return;
            }
            if (!reply) {
                reply = {status: 500, json: {error: {message: 'script exhausted'}}};
            }
            self.readyState = 4;
            self.status = reply.status || 200;
            self.responseText = JSON.stringify(reply.json);
            self.onload();
        }, 1);
    };
};

function modelTurn(parts) {
    return {status: 200, json: {candidates: [{content: {role: 'model', parts: parts}, finishReason: 'STOP'}],
        usageMetadata: {promptTokenCount: 10, candidatesTokenCount: 5}}};
}
function call(name, args, id) {
    return {functionCall: {name: name, args: args || {}, id: id || ('call-' + name)}, thoughtSignature: 'sig-' + name};
}
function text(t) {
    return modelTurn([{text: t}]);
}

// ---- helpers --------------------------------------------------------------------
function reset() {
    Object.keys(store).forEach(function(k) { delete store[k]; });
    store['clay-settings'] = JSON.stringify({GEMINI_API_KEY: 'test-key', ASSISTANT_RUNTIME: 'companionless'});
    watch.sent = [];
    gemini.requests = [];
    gemini.script = [];
    httpRoutes = {};
    var queue = require(path.join(PKJS, 'lib/message_queue')).Queue;
    queue.queue = [];
    queue.messagesInFlight = 0;
    queue.bytesInFlight = 0;
}

function setSettings(extra) {
    var s = JSON.parse(store['clay-settings']);
    Object.keys(extra).forEach(function(k) { s[k] = extra[k]; });
    store['clay-settings'] = JSON.stringify(s);
}

function newSession(prompt, requestId) {
    var Session = require(path.join(PKJS, 'session')).Session;
    return new Session(prompt, 'thread-1', requestId || 0);
}

function waitFor(predicate, ms) {
    ms = ms || 3000;
    var started = Date.now();
    return new Promise(function(resolve, reject) {
        (function poll() {
            if (predicate()) { resolve(); return; }
            if (Date.now() - started > ms) { reject(new Error('timed out waiting')); return; }
            setTimeout(poll, 5);
        })();
    });
}

function chatText() {
    return watch.sent.filter(function(m) { return m.CHAT; }).map(function(m) { return m.CHAT; }).join('');
}
function done() {
    return watch.sent.some(function(m) { return m.CHAT_DONE; });
}
function declaredNames(request) {
    var names = [];
    (request.body.tools || []).forEach(function(tool) {
        (tool.functionDeclarations || []).forEach(function(d) { names.push(d.name); });
    });
    return names;
}

var tests = [];
function test(name, fn) { tests.push({name: name, fn: fn}); }

// ---- tests ----------------------------------------------------------------------

test('every tool is offered even when the prompt has no keyword', async function() {
    gemini.script.push(text('Hi!'));
    newSession('start one for ten').run();
    await waitFor(done);
    var names = declaredNames(gemini.requests[0]);
    ['set_timer', 'set_alarm', 'set_reminder', 'get_weather', 'show_number', 'ask_clarifying_question',
        'show_openstreetmap_map', 'update_settings', 'remember_billy_user_fact'].forEach(function(n) {
        assert(names.indexOf(n) !== -1, 'missing tool ' + n);
    });
});

test('Search and functions are combined with includeServerSideToolInvocations on Gemini 3', async function() {
    gemini.script.push(text('ok'));
    newSession('hello').run();
    await waitFor(done);
    var body = gemini.requests[0].body;
    assert.deepStrictEqual(body.tools[0], {googleSearch: {}});
    assert.strictEqual(body.toolConfig.includeServerSideToolInvocations, true);
    assert.strictEqual(body.generationConfig.thinkingConfig.thinkingLevel, 'low');
    assert(/gemini-3\.8-flash:generateContent/.test(gemini.requests[0].url), gemini.requests[0].url);
});

test('timer: tool runs on the watch, countdown card shown, result returned with call id', async function() {
    gemini.script.push(modelTurn([call('set_timer', {minutes: 10}, 'abc')]));
    gemini.script.push(text('Ten minutes, starting now.'));
    newSession('start one for ten minutes').run();
    await waitFor(done);
    assert(watch.sent.some(function(m) { return m.SET_ALARM_TIME === 600 && m.SET_ALARM_IS_TIMER; }), 'timer not sent to watch');
    assert(watch.sent.some(function(m) { return m.TIMER_WIDGET === 1; }), 'no countdown card');
    var second = gemini.requests[1].body.contents;
    var modelContent = second[second.length - 2];
    assert.strictEqual(modelContent.parts[0].thoughtSignature, 'sig-set_timer', 'thought signature not replayed');
    var fr = second[second.length - 1].parts[0].functionResponse;
    assert.strictEqual(fr.id, 'abc');
    assert.strictEqual(fr.response.status, 'ok');
    assert.strictEqual(chatText(), 'Ten minutes, starting now.');
});

test('a tool request written out as text is caught and retried, never shown', async function() {
    gemini.script.push(text('request:APIcall:show_image{query:avocado}'));
    gemini.script.push(text('Avocado is closer to the cucumber? No: avocado and peanut are both in different orders; cucumber is closest.'));
    newSession('what is biologically closer to avocado, cucumber or peanut').run();
    await waitFor(done);
    assert.strictEqual(gemini.requests.length, 2, 'should retry once');
    assert(chatText().indexOf('request:') === -1, 'leaked text shown: ' + chatText());
    var detect = require(path.join(PKJS, 'agent/companionless')).looksLikeLeakedToolCall;
    assert(detect('call:default_api:get_weather{}'));
    assert(!detect('Avocado and cucumber: both flowering plants. Peanut: a legume.'));
    assert(!detect('Ratio is 3:2, time 10:30.'));
});

test('parallel calls return in ONE user turn; model stays pinned', async function() {
    gemini.script.push(modelTurn([call('set_alarm', {time: new Date(Date.now() + 3600e3).toISOString()}, 'a1'),
        call('set_timer', {seconds: 30}, 't1')]));
    gemini.script.push(text('Done.'));
    newSession('alarm in an hour and a 30 second timer').run();
    await waitFor(done);
    var contents = gemini.requests[1].body.contents;
    var last = contents[contents.length - 1];
    assert.strictEqual(last.role, 'user');
    assert.strictEqual(last.parts.length, 2);
    assert.strictEqual(gemini.requests[0].url, gemini.requests[1].url, 'model changed mid-turn');
});

test('duplicate mutating calls in one turn are not run twice', async function() {
    var args = {minutes: 5};
    gemini.script.push(modelTurn([call('set_timer', args, 'x1')]));
    gemini.script.push(modelTurn([call('set_timer', args, 'x2')]));
    gemini.script.push(text('Set.'));
    newSession('five minute timer').run();
    await waitFor(done);
    var timerSends = watch.sent.filter(function(m) { return m.SET_ALARM_TIME === 300; });
    assert.strictEqual(timerSends.length, 1);
});

test('follow-up turn gets earlier turns and their actions as history', async function() {
    gemini.script.push(modelTurn([call('set_timer', {minutes: 10})]));
    gemini.script.push(text('Timer set.'));
    newSession('ten minute timer').run();
    await waitFor(done);
    watch.sent = [];
    gemini.script.push(text('Cancelled.'));
    newSession('cancel it').run();
    await waitFor(done);
    var contents = gemini.requests[2].body.contents;
    assert.strictEqual(contents[0].parts[0].text, 'ten minute timer');
    assert(/set_timer/.test(contents[1].parts[0].text), 'actions missing from history: ' + contents[1].parts[0].text);
    assert.strictEqual(contents[2].parts[0].text, 'cancel it');
});

test('weather tool shows a week card when asked', async function() {
    setSettings({LOCATION_ENABLED: true});
    var weatherReply = {current: {temperature_2m: 20, apparent_temperature: 19, weather_code: 1, wind_speed_10m: 8},
        daily: {time: ['2026-09-30', '2026-10-01', '2026-10-02'], temperature_2m_max: [22, 18, 15],
            temperature_2m_min: [10, 9, 8], weather_code: [1, 61, 3], precipitation_probability_max: [5, 70, 20]}};
    gemini.script.push(modelTurn([call('get_weather', {location_name: 'Spokane', card: 'week'})]));
    gemini.script.push(text('Rain Thursday.'));
    // geocode + forecast go through the same fake XHR (non-Gemini URLs).
    var origSend = global.XMLHttpRequest;
    global.XMLHttpRequest = function() {
        var x = new origSend();
        var send = x.send;
        x.send = function(body) {
            if (/nominatim/.test(x.url)) {
                setTimeout(function() { x.readyState = 4; x.status = 200; x.responseText = JSON.stringify([{lat: '47.6', lon: '-117.4'}]); x.onload(); }, 1);
                return;
            }
            if (/open-meteo/.test(x.url)) {
                setTimeout(function() { x.readyState = 4; x.status = 200; x.responseText = JSON.stringify(weatherReply); x.onload(); }, 1);
                return;
            }
            send.call(x, body);
        };
        return x;
    };
    try {
        newSession('weather this week in spokane').run();
        await waitFor(done);
    } finally {
        global.XMLHttpRequest = origSend;
    }
    var card = watch.sent.filter(function(m) { return m.WEATHER_WIDGET; })[0];
    assert.strictEqual(card.WEATHER_WIDGET, 3);
    assert.strictEqual(card[MESSAGE_KEYS.WEATHER_WIDGET_MULTI_HIGH + 1], 18);
    var fr = gemini.requests[1].body.contents.slice(-1)[0].parts[0].functionResponse.response;
    assert.strictEqual(fr.daily[1].rain_chance, 70);
});

test('show_number sends the highlight card', async function() {
    gemini.script.push(modelTurn([call('show_number', {value: '$1,234', label: 'EUR to USD'})]));
    gemini.script.push(text('At today\'s rate.'));
    newSession('what is 1100 euros in dollars').run();
    await waitFor(done);
    var card = watch.sent.filter(function(m) { return m.HIGHLIGHT_WIDGET; })[0];
    assert.strictEqual(card.HIGHLIGHT_WIDGET_PRIMARY, '$1,234');
});

test('clarification picker ends the turn; the answer comes back as a sentence', async function() {
    gemini.script.push(modelTurn([call('ask_clarifying_question', {question: 'Which alarm?', options: ['7:00', '8:00']})]));
    newSession('delete my alarm').run();
    await waitFor(function() { return watch.sent.some(function(m) { return m.CLARIFY_WIDGET; }); });
    var card = watch.sent.filter(function(m) { return m.CLARIFY_WIDGET; })[0];
    assert.strictEqual(card.CLARIFY_OPTION_COUNT, 3);
    assert.strictEqual(card[MESSAGE_KEYS.CLARIFY_OPTION_0 + 2], 'Dictate...');
    await new Promise(function(r) { setTimeout(r, 30); });
    assert.strictEqual(gemini.requests.length, 1, 'kept calling Gemini after asking the user');
    var normalize = require(path.join(PKJS, 'agent/companionless'))._normalizePrompt;
    assert.strictEqual(normalize('BILLY_CLARIFICATION_ANSWER\ncontext=companionless\nquestion=Which alarm?\nanswer=8:00'),
        'My answer to your question "Which alarm?": 8:00');
});

test('built-in tools: Search, Maps and URL reading ride along with functions, never Maps with code execution', async function() {
    gemini.script.push(text('ok'));
    newSession('hello').run();
    await waitFor(done);
    var keys = gemini.requests[0].body.tools.map(function(t) { return Object.keys(t)[0]; });
    ['googleSearch', 'googleMaps', 'urlContext', 'functionDeclarations'].forEach(function(k) {
        assert(keys.indexOf(k) !== -1, 'missing ' + k + ' in ' + keys);
    });
    // Google rejects googleMaps + codeExecution in one request (400).
    assert(keys.indexOf('codeExecution') === -1, 'codeExecution must not be sent with googleMaps');
});

test('quota errors say which limit was hit', function() {
    var g = require(path.join(PKJS, 'agent/gemini'));
    assert(/isn't included with a free Gemini key/.test(g._quotaMessage('Quota exceeded for metric: generate_content_free_tier_requests, limit: 0, model: gemini-3.1-pro-preview', 'gemini-3.1-pro-preview')));
    assert(/today's free Gemini requests/.test(g._quotaMessage('limit: 20 ... "quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier"', 'gemini-3.8-flash')));
    assert(/this minute/.test(g._quotaMessage('limit: 5 ... "quotaId": "GenerateRequestsPerMinutePerProjectPerModel-FreeTier"', 'gemini-3.8-flash')));
});

test('retired models move to their successor', function() {
    var config = require(path.join(PKJS, 'config'));
    config.setSetting('GEMINI_MODEL', 'gemini-3.5-flash');
    assert.strictEqual(config.getGeminiModel(), 'gemini-3.8-flash');
    config.setSetting('GEMINI_MODEL', 'gemini-3.6-flash');
    assert.strictEqual(config.getGeminiModel(), 'gemini-3.6-flash');
    config.setSetting('GEMINI_MODEL', '');
});

function warningText() {
    return watch.sent.filter(function(m) { return m.WARNING; }).map(function(m) { return m.WARNING; }).join('|');
}

test('a model that rejects the tools fails with Google\'s message; tools are never dropped', async function() {
    gemini.script.push({status: 400, json: {error: {message: 'Tool combination is unsupported', status: 'INVALID_ARGUMENT'}}});
    gemini.script.push(text('must not be asked'));
    newSession('hello').run();
    await waitFor(done);
    assert.strictEqual(gemini.requests.length, 1, 'no retry or step-down after a 400');
    assert(/Tool combination is unsupported/.test(warningText()), warningText());
    assert(/gemini-3\.8-flash/.test(warningText()), warningText());
    assert(/You can report this from the menu\./.test(warningText()));
    assert.strictEqual(chatText(), '', 'nothing may be answered after a failure');
});

test('exactly one model: a 404 does not fall back to another model', async function() {
    setSettings({GEMINI_MODEL: 'gemini-9-imaginary'});
    gemini.script.push({status: 404, json: {error: {message: 'models/gemini-9-imaginary is not found'}}});
    gemini.script.push(text('must not be asked'));
    newSession('hello').run();
    await waitFor(done);
    assert.strictEqual(gemini.requests.length, 1);
    assert(/gemini-9-imaginary/.test(gemini.requests[0].url));
    assert(/gemini-9-imaginary/.test(warningText()), warningText());
    var config = require(path.join(PKJS, 'config'));
    assert.strictEqual(config.FALLBACK_MODELS, undefined, 'FALLBACK_MODELS must be gone');
});

test('HTTP 503 is retried once on the same model, then reported plainly', async function() {
    gemini.script.push({status: 503, json: {error: {message: 'overloaded'}}});
    gemini.script.push({status: 503, json: {error: {message: 'overloaded'}}});
    gemini.script.push(text('must not be asked'));
    newSession('hello').run();
    await waitFor(done, 6000);
    assert.strictEqual(gemini.requests.length, 2);
    assert.strictEqual(gemini.requests[0].url, gemini.requests[1].url, 'retry must use the same model');
    assert(/^Gemini is overloaded right now \(503\)\. You can report this from the menu\.$/.test(warningText()), warningText());
});

test('a 503 followed by success answers from the same model', async function() {
    gemini.script.push({status: 503, json: {error: {message: 'overloaded'}}});
    gemini.script.push(text('Back.'));
    newSession('hello').run();
    await waitFor(done, 6000);
    assert.strictEqual(chatText(), 'Back.');
    assert.strictEqual(gemini.requests[0].url, gemini.requests[1].url);
});

test('network error is retried once; then "No internet connection"', async function() {
    gemini.script.push({networkError: true});
    gemini.script.push({networkError: true});
    newSession('hello').run();
    await waitFor(done, 6000);
    assert.strictEqual(gemini.requests.length, 2);
    assert(/^No internet connection on your phone\. You can report this from the menu\.$/.test(warningText()), warningText());
});

test('a timeout is not retried and says how long it waited', async function() {
    gemini.script.push({timeout: true});
    gemini.script.push(text('must not be asked'));
    newSession('hello').run();
    await waitFor(done, 6000);
    assert.strictEqual(gemini.requests.length, 1);
    assert(/^Gemini \(gemini-3\.8-flash\) didn't respond within 25 seconds\. You can report this from the menu\.$/.test(warningText()), warningText());
});

test('429 names the daily limit and is not retried', async function() {
    gemini.script.push({status: 429, json: {error: {message: 'Quota exceeded for metric: generate_content_free_tier_requests, limit: 20',
        details: [{violations: [{quotaId: 'GenerateRequestsPerDayPerProjectPerModel-FreeTier'}]}]}}});
    newSession('hello').run();
    await waitFor(done);
    assert.strictEqual(gemini.requests.length, 1);
    assert(/^You've used today's free Gemini requests for gemini-3\.8-flash\./.test(warningText()), warningText());
});

test('an empty answer is re-asked once, then reported instead of faked', async function() {
    var empty = modelTurn([]);
    gemini.script.push(empty);
    gemini.script.push(empty);
    newSession('hello').run();
    await waitFor(done, 6000);
    assert.strictEqual(gemini.requests.length, 2);
    assert(/empty answer/.test(warningText()), warningText());
    assert(/report this from the menu/.test(warningText()));
});

test('old Flash-Lite default is migrated once to the new default', async function() {
    setSettings({GEMINI_MODEL: 'gemini-3.1-flash-lite'});
    var config = require(path.join(PKJS, 'config'));
    assert.strictEqual(config.getGeminiModel(), 'gemini-3.8-flash');
    config.setSetting('GEMINI_MODEL', 'gemini-3.1-flash-lite');
    assert.strictEqual(config.getGeminiModel(), 'gemini-3.1-flash-lite', 'explicit later choice must be kept');
});

test('router: automatic mode stands down when the companion claims the request', async function() {
    setSettings({ASSISTANT_RUNTIME: 'automatic'});
    var router = require(path.join(PKJS, 'agent/runtime_router'));
    router.recordAndroidCompanionSeen(0);
    gemini.script.push(text('should not be used'));
    newSession('what time is it in Tokyo', 4242).run();
    setTimeout(function() { router.recordAndroidCompanionSeen(4242); }, 300);
    await new Promise(function(r) { setTimeout(r, 3000); });
    assert.strictEqual(gemini.requests.length, 0, 'phone runtime answered a claimed prompt');
});

test('router: automatic mode with a recently seen companion shows an error instead of silently answering', async function() {
    setSettings({ASSISTANT_RUNTIME: 'automatic'});
    var router = require(path.join(PKJS, 'agent/runtime_router'));
    router.recordAndroidCompanionSeen(0);
    gemini.script.push(text('Here.'));
    newSession('hello', 5151).run();
    await waitFor(done, 6000);
    assert.strictEqual(gemini.requests.length, 0, 'phone runtime must not answer');
    assert.strictEqual(chatText(), '');
    assert.strictEqual(warningText(), router.COMPANION_MISSING_MESSAGE);
    assert(/^Billy Companion on your phone didn't pick this up\. Open Billy Companion once, and check that it's allowed to run in the background\. You can report this from the menu\.$/.test(warningText()));
    // a late claim must not produce a second message or an answer
    await new Promise(function(r) { setTimeout(r, 400); });
    var before = watch.sent.length;
    router.recordAndroidCompanionSeen(5151);
    await new Promise(function(r) { setTimeout(r, 300); });
    assert.strictEqual(watch.sent.length, before, 'late claim added messages');
    assert.strictEqual(gemini.requests.length, 0);
});

test('router: android mode never falls back to the phone runtime', async function() {
    setSettings({ASSISTANT_RUNTIME: 'android'});
    gemini.script.push(text('Here.'));
    newSession('hello', 6161).run();
    await waitFor(done, 8000);
    assert.strictEqual(gemini.requests.length, 0);
    assert(/Billy Companion on your phone didn't pick this up/.test(warningText()), warningText());
});

test('router: automatic mode with no companion ever seen answers from the phone (iPhone)', async function() {
    setSettings({ASSISTANT_RUNTIME: 'automatic'});
    gemini.script.push(text('Phone answer.'));
    newSession('hello', 7171).run();
    await waitFor(done, 3000);
    assert.strictEqual(chatText(), 'Phone answer.');
    assert.strictEqual(warningText(), '');
});

// ---- diagnostics --------------------------------------------------------------
test('diagnostics: records timings per turn, never the prompt, reply or tool arguments', async function() {
    var secretPrompt = 'remind me to call dr zebrowski about my rash';
    var secretReply = 'Sure, I will mention the zebrowski rash call.';
    gemini.script.push(modelTurn([call('set_timer', {minutes: 5, name: 'zebrowski rash call'}, 'd1')]));
    gemini.script.push(text(secretReply));
    newSession(secretPrompt).run();
    await waitFor(done, 5000);
    var diag = require(path.join(PKJS, 'agent/diagnostics'));
    var turns = diag.recent(5);
    assert.strictEqual(turns.length, 1);
    var t = turns[0];
    assert.strictEqual(t.path, 'phone-companionless');
    assert.strictEqual(t.outcome, 'ok');
    assert.strictEqual(t.gemini.length, 2);
    assert.strictEqual(t.gemini[0].model, 'gemini-3.8-flash');
    assert.strictEqual(t.gemini[0].status, 200);
    assert.strictEqual(t.gemini[0].finishReason, 'STOP');
    assert.strictEqual(t.gemini[0].prompt, 10);
    assert.strictEqual(t.tools[0].name, 'set_timer');
    assert(typeof t.totalMs === 'number');
    var everything = store[diag.STORAGE_KEY] + '\n' + diag.formatRecent(5);
    ['zebrowski', 'rash', 'remind me'].forEach(function(word) {
        assert(everything.indexOf(word) === -1, 'diagnostics leaked "' + word + '"');
    });
    // and the report carries them
    var feedback = require(path.join(PKJS, 'lib/feedback'));
    var opened = null;
    Pebble.openURL = function(u) { opened = u; };
    feedback.handleReportRequest({REPORT_THREAD_UUID: 'abc', FEEDBACK_APP_MAJOR: 1, FEEDBACK_APP_MINOR: 2, FEEDBACK_ALARM_COUNT: 0});
    assert(opened, 'no report URL opened');
    var body = decodeURIComponent(/body=([^&]*)/.exec(opened)[1]);
    assert(/path=phone-companionless/.test(body), body);
    assert(/lastTurnRuntimePath: phone-companionless/.test(body), body);
    assert(/tool set_timer/.test(body));
    assert(/gemini-3\.8-flash/.test(body));
    assert(/body=([^&]*)/.exec(opened)[1].length <= 6000);
});

test('diagnostics: keeps only the last 20 turns and notes errors', async function() {
    var diag = require(path.join(PKJS, 'agent/diagnostics'));
    for (var i = 0; i < 25; i++) {
        var t = diag.begin('phone-companionless', 0);
        diag.end(t, i === 24 ? 'error' : 'ok', i === 24 ? 'Gemini is overloaded right now (503).' : '');
    }
    var all = diag.recent(100);
    assert.strictEqual(all.length, 20);
    assert.strictEqual(all[19].outcome, 'error');
});

test('relay: companion request runs the watch tool and returns a JS_TOOL_RESULT', async function() {
    var relay = require(path.join(PKJS, 'agent/relay'));
    relay.handleRequest(JSON.stringify({id: 'r1', name: 'set_timer', args: {minutes: 3, name: 'Tea'}}));
    await waitFor(function() { return watch.sent.some(function(m) { return m.JS_TOOL_RESULT; }); });
    var result = JSON.parse(watch.sent.filter(function(m) { return m.JS_TOOL_RESULT; })[0].JS_TOOL_RESULT);
    assert.strictEqual(result.id, 'r1');
    assert.strictEqual(result.result.status, 'ok');
    assert(watch.sent.some(function(m) { return m.SET_ALARM_TIME === 180 && m.SET_ALARM_NAME === 'Tea'; }));
    assert(watch.sent.some(function(m) { return m.TIMER_WIDGET === 1 && m.TIMER_WIDGET_NAME === 'Tea'; }));
});


test('show_image (phone-only): Wikipedia picture is decoded and sent as a Pebble bitmap card', async function() {
    var jpeg = new Uint8Array(fs.readFileSync(path.join(__dirname, 'fixtures', 'landmark.jpg')));
    httpRoutes['wikipedia.org/w/api.php'] = function() {
        return {json: {query: {pages: {'1': {index: 1, title: 'Eiffel Tower', thumbnail: {source: 'https://upload.wikimedia.org/x/Tour_Eiffel.jpg'}}}}}};
    };
    httpRoutes['upload.wikimedia.org'] = function() {
        return {bytes: jpeg};
    };
    gemini.script.push(modelTurn([call('show_image', {query: 'Eiffel Tower'})]));
    gemini.script.push(text('Paris, 330 m tall.'));
    newSession('tell me about the eiffel tower').run();
    await waitFor(done, 5000);
    var start = watch.sent.filter(function(m) { return m.IMAGE_START_BYTE_SIZE; })[0];
    assert(start, 'no image sent');
    assert(start.IMAGE_WIDTH <= 198 && start.IMAGE_HEIGHT <= 150, start.IMAGE_WIDTH + 'x' + start.IMAGE_HEIGHT);
    await waitFor(function() { return watch.sent.some(function(m) { return m.MAP_WIDGET === 1; }); }, 3000);
    assert(watch.sent.some(function(m) { return m.IMAGE_COMPLETE; }), 'image not completed');
});

test('calendar (phone-only): reads events from an iCal link, including repeats', async function() {
    setSettings({CALENDAR_LINKS: 'webcal://calendar.example.com/private/basic.ics'});
    var now = new Date();
    var pad = function(n) { return (n < 10 ? '0' : '') + n; };
    var day = now.getFullYear() + pad(now.getMonth() + 1) + pad(now.getDate());
    httpRoutes['calendar.example.com'] = function(url) {
        assert(/^https:/.test(url), 'webcal should become https');
        return {text: ['BEGIN:VCALENDAR', 'BEGIN:VEVENT', 'UID:x', 'SUMMARY:Dentist', 'DTSTART:' + day + 'T235800', 'DTEND:' + day + 'T235900',
            'RRULE:FREQ=DAILY;COUNT=3', 'END:VEVENT', 'END:VCALENDAR'].join('\r\n')};
    };
    gemini.script.push(modelTurn([call('get_calendar_events', {search: 'dentist'})]));
    gemini.script.push(text('Dentist tonight.'));
    newSession('when is my dentist appointment').run();
    await waitFor(done, 5000);
    var names = declaredNames(gemini.requests[0]);
    assert(names.indexOf('get_calendar_events') !== -1);
    var fr = gemini.requests[1].body.contents.slice(-1)[0].parts[0].functionResponse.response;
    assert.strictEqual(fr.status, 'ok');
    assert.strictEqual(fr.events.length, 3);
    assert.strictEqual(fr.events[0].title, 'Dentist');
});

test('weather: card for a specific day shows that day', async function() {
    setSettings({LOCATION_ENABLED: true});
    httpRoutes['nominatim'] = function() { return {json: [{lat: '47.6', lon: '-117.4'}]}; };
    httpRoutes['open-meteo'] = function() {
        return {json: {current: {temperature_2m: 20, apparent_temperature: 19, weather_code: 1, wind_speed_10m: 8},
            daily: {time: ['2026-09-30', '2026-10-01', '2026-10-02', '2026-10-03'], temperature_2m_max: [22, 18, 15, 11],
                temperature_2m_min: [10, 9, 8, 3], weather_code: [1, 61, 3, 71], precipitation_probability_max: [5, 70, 20, 40]}}};
    };
    gemini.script.push(modelTurn([call('get_weather', {location_name: 'Spokane', card: 'day', date: '2026-10-03'})]));
    gemini.script.push(text('Snow Saturday.'));
    newSession('weather saturday in spokane').run();
    await waitFor(done, 5000);
    var card = watch.sent.filter(function(m) { return m.WEATHER_WIDGET; })[0];
    assert.strictEqual(card.WEATHER_WIDGET, 1);
    assert.strictEqual(card.WEATHER_WIDGET_DAY_HIGH, 11);
    assert.strictEqual(card.WEATHER_WIDGET_DAY_OF_WEEK, 'Saturday');
});

test('relay keys stay where the Android companion expects them', function() {
    assert.strictEqual(MESSAGE_KEYS.CHAT, 10030);
    assert.strictEqual(MESSAGE_KEYS.ANDROID_REQUEST_ID, 10125);
    assert.strictEqual(MESSAGE_KEYS.JS_TOOL_REQUEST, 10126);
    assert.strictEqual(MESSAGE_KEYS.JS_TOOL_RESULT, 10127);
    var kotlin = fs.readFileSync(path.resolve(__dirname,
        '../../companion-android/app/src/main/java/com/tombo/billyassistant/companion/pebble/BillyPebbleListenerService.kt'), 'utf8');
    var re = /val (\w+): UInt = (\d+)u/g;
    var m;
    var checked = 0;
    while ((m = re.exec(kotlin))) {
        if (MESSAGE_KEYS[m[1]] !== undefined) {
            assert.strictEqual(MESSAGE_KEYS[m[1]], parseInt(m[2], 10), 'Kotlin key ' + m[1] + ' drifted');
            checked++;
        }
    }
    assert(checked > 20, 'only checked ' + checked + ' keys');
});

// ---- Pebble plugins (preview) ----------------------------------------------------
var FAKE_PLUGINS = [
    {uuid: '00000000-0000-0000-0001-000000000004', name: 'Calendar', actions: [],
        sources: [{category: 'calendar', items: ['event'], properties: {name: ['shortText', 'longText'], starts_at: ['timestamp'], ends_at: ['timestamp']}}]},
    {uuid: '00000000-0000-0000-0001-000000000003', name: 'Music',
        sources: [{category: 'music', items: ['track'], properties: {title: ['shortText'], artist: ['shortText'], artwork: ['image']}}],
        actions: [{name: 'set_playing', description: 'Play or pause.', parameters: {type: 'object', properties: {on: {type: 'boolean'}}, required: ['on']}},
            {name: 'next_track', description: 'Skip.', parameters: null}]},
    {uuid: 'aaaa-bbbb', name: 'Todoist', sources: [],
        actions: [{name: 'delete_task', description: 'Delete a task.', parameters: {type: 'object', properties: {id: {type: 'string'}}}, destructive: true}]},
    {uuid: '00000000-0000-0000-0001-000000000008', name: 'Find My Phone', actions: [{name: 'ring', parameters: null}],
        sources: [{category: 'phone', items: ['find_my_phone'], properties: {ringing: ['boolean']}}]}
];

function withPlugins(fn) {
    var invoked = [];
    var subscriptions = 0;
    var inNow = Math.round(Date.now() / 1000) + 3600;
    Pebble.enumeratePlugins = function() { return FAKE_PLUGINS; };
    Pebble.subscribeToSource = function(config) {
        subscriptions++;
        setTimeout(function() {
            if (config.category === 'calendar') {
                config.onData({pluginUuid: FAKE_PLUGINS[0].uuid, instances: [{instanceId: '0', properties: {
                    name: {shortText: {text: 'Dentist'}, longText: {text: 'Dentist'}},
                    starts_at: {timestamp: {value: inNow}}, ends_at: {timestamp: {value: inNow + 1800}}}}]});
            } else if (config.category === 'music') {
                config.onData({instances: [{instanceId: 'track', properties: {title: {shortText: {text: 'Song'}}, artwork: {image: {pixels: 'xx'}}}}]});
            } else {
                config.onError({code: 'PERMISSION_DENIED', message: 'not allowed'});
            }
        }, 1);
        return {unsubscribe: function() { subscriptions--; }};
    };
    Pebble.invokeAction = function(request) {
        invoked.push(request);
        return Promise.resolve({ok: true, text: 'Paused.'});
    };
    return Promise.resolve(fn(invoked, function() { return subscriptions; })).finally(function() {
        delete Pebble.enumeratePlugins;
        delete Pebble.subscribeToSource;
        delete Pebble.invokeAction;
    });
}

test('plugins: no plugin tools when plugins are off', async function() {
    gemini.script.push(text('Hi'));
    newSession('hello').run();
    await waitFor(done);
    var names = declaredNames(gemini.requests[0]);
    assert(names.indexOf('read_phone_data') === -1 && names.indexOf('use_phone_plugin') === -1, 'plugin tools offered while off');
});

test('plugins: catalog is in the tool descriptions and music can be paused', async function() {
    await withPlugins(async function(invoked) {
        gemini.script.push(modelTurn([call('use_phone_plugin', {plugin: 'Music', action: 'set_playing', args_json: '{"on": false}'}, 'm1')]));
        gemini.script.push(text('Paused.'));
        newSession('pause the music').run();
        await waitFor(done);
        var decls = gemini.requests[0].body.tools.filter(function(t) { return t.functionDeclarations; })[0].functionDeclarations;
        var act = decls.filter(function(d) { return d.name === 'use_phone_plugin'; })[0];
        assert(act && /Music > set_playing\(on\*\)/.test(act.description), 'catalog missing from description');
        var read = decls.filter(function(d) { return d.name === 'read_phone_data'; })[0];
        assert(/calendar\/event \(Calendar\)/.test(read.description), 'sources missing');
        assert(!/find_my_phone/.test(read.description), 'find_my_phone must not be readable (it rings the phone)');
        assert.strictEqual(invoked.length, 1);
        assert.strictEqual(invoked[0].plugin, '00000000-0000-0000-0001-000000000003');
        assert.deepStrictEqual(invoked[0].args, {on: false});
    });
});

test('plugins: destructive actions need the user to confirm first', async function() {
    await withPlugins(async function(invoked) {
        gemini.script.push(modelTurn([call('use_phone_plugin', {plugin: 'todoist', action: 'delete_task', args_json: '{"id":"1"}'}, 'd1')]));
        gemini.script.push(text('Should I delete it?'));
        newSession('delete my milk task').run();
        await waitFor(done);
        assert.strictEqual(invoked.length, 0, 'destructive action ran without confirmation');
        var fr = gemini.requests[1].body.contents.slice(-1)[0].parts[0].functionResponse;
        assert.strictEqual(fr.response.status, 'needs_confirmation');
    });
});

test('plugins: reads are simplified, images dropped, subscription closed', async function() {
    await withPlugins(async function(invoked, openSubscriptions) {
        gemini.script.push(modelTurn([call('read_phone_data', {category: 'music', item: 'track'}, 'r1')]));
        gemini.script.push(text('You are listening to Song.'));
        newSession("what's playing").run();
        await waitFor(done);
        var fr = gemini.requests[1].body.contents.slice(-1)[0].parts[0].functionResponse;
        assert.deepStrictEqual(fr.response.items, [{id: 'track', title: 'Song'}]);
        assert.strictEqual(openSubscriptions(), 0, 'subscription left open');
    });
});

test('plugins: a refused permission is explained, not hidden', async function() {
    await withPlugins(async function() {
        gemini.script.push(modelTurn([call('read_phone_data', {category: 'notifications', item: 'notification'}, 'n1')]));
        gemini.script.push(text('Billy needs permission.'));
        newSession('any notifications?').run();
        await waitFor(done);
        var fr = gemini.requests[1].body.contents.slice(-1)[0].parts[0].functionResponse;
        assert.strictEqual(fr.response.status, 'error');
        assert(/allow it on Billy's page/.test(fr.response.summary), fr.response.summary);
    });
});

test("plugins: the phone's calendar answers without calendar links", async function() {
    await withPlugins(async function() {
        gemini.script.push(modelTurn([call('get_calendar_events', {}, 'c1')]));
        gemini.script.push(text('Dentist in an hour.'));
        newSession("what's next on my calendar").run();
        await waitFor(done);
        var fr = gemini.requests[1].body.contents.slice(-1)[0].parts[0].functionResponse;
        assert.strictEqual(fr.response.status, 'ok', JSON.stringify(fr.response));
        assert.strictEqual(fr.response.events.length, 1);
        assert.strictEqual(fr.response.events[0].title, 'Dentist');
    });
});

test('plugins: the companion can list and use plugins through the relay', async function() {
    await withPlugins(async function(invoked) {
        var relay = require(path.join(PKJS, 'agent/relay'));
        relay.handleRequest(JSON.stringify({id: 'p1', name: 'list_phone_plugins', args: {}}));
        relay.handleRequest(JSON.stringify({id: 'p2', name: 'use_phone_plugin', args: {plugin: 'Music', action: 'next_track'}}));
        await waitFor(function() { return watch.sent.filter(function(m) { return m.JS_TOOL_RESULT; }).length >= 2; });
        var results = watch.sent.filter(function(m) { return m.JS_TOOL_RESULT; }).map(function(m) { return JSON.parse(m.JS_TOOL_RESULT); });
        var list = results.filter(function(r) { return r.id === 'p1'; })[0].result;
        assert(list.available && list.actions.some(function(a) { return /Music > next_track/.test(a); }), JSON.stringify(list));
        assert(results.every(function(r) { return r.id !== 'p1' || JSON.stringify(r).length < 1000; }), 'relay result too big for the watch');
        assert.strictEqual(invoked[0].action, 'next_track');
    });
});

test('an out-of-date Billy Companion gets a once-a-day reminder on the watch', function() {
    var router = require(path.join(PKJS, 'agent/runtime_router'));
    var shown = [];
    Pebble.showSimpleNotificationOnPebble = function(title, body) { shown.push(title); };
    localStorage.removeItem('billyCompanionUpdateNagAt');
    router.recordAndroidCompanionSeen(0, 1);       // a 0.6 companion sends 1
    router.recordAndroidCompanionSeen(0, 1);       // same day: no repeat
    router.recordAndroidCompanionSeen(0, router.MIN_COMPANION_VERSION);
    delete Pebble.showSimpleNotificationOnPebble;
    assert.deepStrictEqual(shown, ['Update Billy Companion']);
    localStorage.removeItem('billyCompanionUpdateNagAt');
});

(async function() {
    var failed = 0;
    for (var i = 0; i < tests.length; i++) {
        reset();
        try {
            await tests[i].fn();
            console.log('ok   - ' + tests[i].name);
        } catch (e) {
            failed++;
            console.log('FAIL - ' + tests[i].name + '\n       ' + (e && e.stack ? e.stack.split('\n').slice(0, 3).join('\n       ') : e));
        }
        await new Promise(function(r) { setTimeout(r, 20); });
    }
    console.log('\n' + (tests.length - failed) + '/' + tests.length + ' passed');
    process.exit(failed ? 1 : 0);
})();
