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

test('built-in tools: Search, Maps, URL reading and code ride along with functions', async function() {
    gemini.script.push(text('ok'));
    newSession('hello').run();
    await waitFor(done);
    var keys = gemini.requests[0].body.tools.map(function(t) { return Object.keys(t)[0]; });
    ['googleSearch', 'googleMaps', 'urlContext', 'codeExecution', 'functionDeclarations'].forEach(function(k) {
        assert(keys.indexOf(k) !== -1, 'missing ' + k + ' in ' + keys);
    });
});

test('model rejecting built-ins steps down (all -> Search only -> none) instead of flattening', async function() {
    var reject = {status: 400, json: {error: {message: 'Tool combination is unsupported', status: 'INVALID_ARGUMENT'}}};
    gemini.script.push(reject);
    gemini.script.push(reject);
    gemini.script.push(text('ok'));
    newSession('hello').run();
    await waitFor(done);
    assert.strictEqual(gemini.requests.length, 3);
    var second = gemini.requests[1].body.tools;
    assert(second.some(function(t) { return t.googleSearch; }), 'tier 1 keeps Search');
    assert(!second.some(function(t) { return t.googleMaps; }), 'tier 1 drops Maps');
    assert(!gemini.requests[2].body.tools.some(function(t) { return t.googleSearch; }), 'tier 2 drops Search');
    assert(/:generateContent/.test(gemini.requests[2].url));
});

test('overloaded model falls back to the next model on the first call only', async function() {
    gemini.script.push({status: 503, json: {error: {message: 'overloaded'}}});
    gemini.script.push({status: 503, json: {error: {message: 'overloaded'}}});
    gemini.script.push(modelTurn([call('set_timer', {minutes: 1})]));
    gemini.script.push(text('Go.'));
    newSession('one minute timer').run();
    await waitFor(done, 6000);
    assert(/gemini-3\.7-flash/.test(gemini.requests[2].url), gemini.requests[2].url);
    assert(/gemini-3\.7-flash/.test(gemini.requests[3].url), 'model not pinned after fallback');
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

test('router: automatic mode answers itself when the companion stays silent', async function() {
    setSettings({ASSISTANT_RUNTIME: 'automatic'});
    var router = require(path.join(PKJS, 'agent/runtime_router'));
    router.recordAndroidCompanionSeen(0);
    gemini.script.push(text('Here.'));
    newSession('hello', 5151).run();
    await waitFor(done, 5000);
    assert.strictEqual(chatText(), 'Here.');
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
