/**
 * Store-screenshot scenes for the emulator.
 *
 * Only active in emulator mode (pypkjs) when a screenshot build has filled in
 * demo_assets.js. A local control server run by the screenshot workflow says
 * which scene to show; the scene's prompt is sent to the watch as if typed on
 * the phone, and its scripted answer plays back exactly the messages the
 * Billy Companion sends for that kind of request.
 */

var messageQueue = require('../lib/message_queue').Queue;
var imageManager = require('../lib/image_transfer').sharedManager;
var decodeJpeg = require('../lib/jpeg_decoder');
var pebbleImage = require('../lib/pebble_image');
var assets = require('./demo_assets');

var CONTROL_URL = 'http://127.0.0.1:8765/scene';

function chat(text) {
    // Arrive in a few pieces, the way a streamed answer does.
    var words = text.split(' ');
    var parts = [];
    for (var i = 0; i < words.length; i += 4) {
        parts.push({CHAT: words.slice(i, i + 4).join(' ') + (i + 4 < words.length ? ' ' : '')});
    }
    return parts;
}

var SCENES = {
    photo: {
        prompt: 'Show me the photo of me with Mango from last spring',
        steps: function() {
            return [{FUNCTION: 'Searching your Google Photos'}, {$image: 'photo'}]
                .concat(chat(assets.photoCaption || 'Here you are with Mango, May 2025.'))
                .concat([{CHAT_DONE: true}]);
        }
    },
    email: {
        prompt: "Reply to Sarah's email and tell her Thursday at 2 works",
        steps: function() {
            return [
                {FUNCTION: 'Reading your Gmail'},
                {
                    CLARIFY_WIDGET: 1,
                    CLARIFY_QUESTION: 'Send to Sarah Chen?\n"Thursday at 2 works great. See you then!"',
                    CLARIFY_CONTEXT: 'demo',
                    CLARIFY_OPTION_COUNT: 3,
                    CLARIFY_OPTION_0: 'Send reply',
                    CLARIFY_OPTION_1: 'Change wording',
                    CLARIFY_OPTION_2: 'Cancel'
                },
                {CHAT_DONE: true}
            ];
        }
    },
    flight: {
        prompt: "When's my flight to Denver?",
        steps: function() {
            return [
                {FUNCTION: 'Checking your Gmail'},
                {HIGHLIGHT_WIDGET: 1, HIGHLIGHT_WIDGET_PRIMARY: '6:45am', HIGHLIGHT_WIDGET_SECONDARY: 'Friday'}
            ].concat(chat('Alaska 1342 leaves Spokane Friday at 6:45am from gate C7. Your confirmation is HX7Q2P.'))
                .concat([{CHAT_DONE: true}]);
        }
    },
    weather: {
        prompt: 'Do I need a jacket tonight?',
        steps: function() {
            return [
                {FUNCTION: 'Checking the forecast nearby'},
                {
                    WEATHER_WIDGET: 1,
                    WEATHER_WIDGET_DAY_HIGH: 64,
                    WEATHER_WIDGET_DAY_LOW: 41,
                    WEATHER_WIDGET_LOCATION: 'SPOKANE',
                    WEATHER_WIDGET_DAY_SUMMARY: 'Clear, cooling fast',
                    WEATHER_WIDGET_TEMP_UNIT: '°F',
                    WEATHER_WIDGET_DAY_ICON: 8,
                    WEATHER_WIDGET_DAY_OF_WEEK: 'Tonight'
                }
            ].concat(chat("Yes. It drops to 41°F by 10pm, so grab a warm layer."))
                .concat([{CHAT_DONE: true}]);
        }
    },
    timer: {
        prompt: 'Set a pasta timer for 9 minutes',
        steps: function() {
            return [
                {FUNCTION: 'Setting a timer'},
                {SET_ALARM_TIME: 540, SET_ALARM_IS_TIMER: true, SET_ALARM_NAME: 'Pasta'}
            ].concat(chat('Pasta timer set for 9 minutes.'))
                .concat([{CHAT_DONE: true}]);
        }
    }
};

function enabled() {
    return !!assets.photo;
}

function base64ToBytes(b64) {
    var alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    var clean = String(b64).replace(/[^A-Za-z0-9+/]/g, '');
    var out = new Uint8Array(Math.floor(clean.length * 3 / 4));
    var buffer = 0;
    var bits = 0;
    var n = 0;
    for (var i = 0; i < clean.length; i++) {
        buffer = (buffer << 6) | alphabet.indexOf(clean.charAt(i));
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out[n++] = (buffer >> bits) & 0xff;
        }
    }
    return out.subarray(0, n);
}

function sendPicture(name) {
    var decoded = decodeJpeg(base64ToBytes(assets[name]), {useTArray: true, formatAsRGBA: true, maxMemoryUsageInMB: 64});
    var encoded = pebbleImage.encode(decoded.data, decoded.width, decoded.height, 198, 150, true);
    var imageId = imageManager.sendImage(encoded.width, encoded.height, encoded.bytes);
    setTimeout(function() {
        messageQueue.enqueue({MAP_WIDGET: 1, MAP_WIDGET_IMAGE_ID: imageId, MAP_WIDGET_USER_LOCATION: 0});
    }, 300);
}

function play(steps, index) {
    if (index >= steps.length) {
        return;
    }
    var step = steps[index];
    var delay = 120;
    if (step.$image) {
        sendPicture(step.$image);
        delay = 900;
    } else {
        messageQueue.enqueue(step);
    }
    setTimeout(function() {
        play(steps, index + 1);
    }, delay);
}

/** Plays the scripted answer if this prompt belongs to a scene. */
exports.answer = function(prompt) {
    if (!enabled()) {
        return false;
    }
    var key = function(text) {
        return String(text || '').toLowerCase().replace(/[^a-z0-9]/g, '');
    };
    for (var id in SCENES) {
        if (key(SCENES[id].prompt) === key(prompt)) {
            console.log('Demo scene: ' + id);
            setTimeout(function(scene) {
                play(scene.steps(), 0);
            }.bind(null, SCENES[id]), 700);
            return true;
        }
    }
    return false;
};

/** Asks the screenshot workflow which scene to show, then "types" its prompt. */
exports.start = function() {
    if (!enabled()) {
        return;
    }
    var req = new XMLHttpRequest();
    req.open('GET', CONTROL_URL, true);
    req.timeout = 3000;
    req.onload = function() {
        var id = String(req.responseText || '').trim();
        var scene = SCENES[id];
        console.log('Demo control says: ' + id);
        if (!scene) {
            return;
        }
        setTimeout(function() {
            Pebble.sendAppMessage({WATCH_PROMPT: scene.prompt}, function() {
                console.log('Demo prompt sent.');
            }, function(e) {
                console.log('Demo prompt failed: ' + JSON.stringify(e));
            });
        }, 2500);
    };
    req.onerror = function() {
        console.log('No demo control server.');
    };
    req.send();
};
