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

var location = require('./location');
var session = require('./session');
var quota = require('./quota');
var Clay = require('@rebble/clay');
var clayConfig = require('./config.json');
var customConfigFunction = require('./custom_config');
var config = require('./config');
var reminders = require('./reminders');
var feedback = require('./lib/feedback');
var package_json = require('package.json');
var runtimeRouter = require('./agent/runtime_router');
var relay = require('./agent/relay');


clayConfig[0].defaultValue = 'Billy ' + package_json.version;
var clay = new Clay(clayConfig, customConfigFunction, {autoHandleEvents: false});
var messageKeys = require('message_keys');

// Only these settings are used by the watch itself. Everything else (API key,
// memory, calendar links) stays on the phone, which keeps the settings message
// small enough for the watch to accept.
var WATCH_SETTINGS = [
    'QUICK_LAUNCH_BEHAVIOUR', 'ALARM_VIBE_PATTERN', 'TIMER_VIBE_PATTERN',
    'CONFIRM_TRANSCRIPTS', 'ASSISTANT_RUNTIME', 'GEMINI_MODEL', 'LOCATION_ENABLED'
];

Pebble.addEventListener('showConfiguration', function() {
    Pebble.openURL(clay.generateUrl());
});

Pebble.addEventListener('webviewclosed', function(e) {
    if (!e || !e.response) {
        return;
    }
    var all = clay.getSettings(e.response);
    var forWatch = {};
    WATCH_SETTINGS.forEach(function(name) {
        var key = messageKeys[name];
        if (key !== undefined && all[key] !== undefined) {
            forWatch[key] = all[key];
        }
    });
    Pebble.sendAppMessage(forWatch, function() {
        console.log('Settings sent to watch.');
    }, function(err) {
        console.log('Settings send failed: ' + JSON.stringify(err));
    });
});

function main() {
    // Keep the watch's copy of the model current so the Android companion
    // (which reads it from each prompt) uses the same model as this runtime.
    Pebble.sendAppMessage({GEMINI_MODEL: config.getGeminiModel()});
    doQuotaWarning();
    location.update();
    Pebble.addEventListener('appmessage', handleAppMessage);
}

function doQuotaWarning() {
    quota.fetchQuota(function(response) {
        if (!response.hasSubscription) {
            Pebble.showSimpleNotificationOnPebble(
                "Subscription Needed",
                "In order to use Billy, you need a Rebble subscription. You can sign up for a subscription at auth.rebble.io."
            );
        }
    });
}

var handledPrompts = {};
var currentSession = null;

function handleAppMessage(e) {
    console.log("Inbound app message!");
    console.log(JSON.stringify(e));
    var data = e.payload;
    if (data.ANDROID_COMPANION_READY) {
        runtimeRouter.recordAndroidCompanionSeen(data.ANDROID_REQUEST_ID);
        return;
    }
    if (data.JS_TOOL_REQUEST) {
        relay.handleRequest(data.JS_TOOL_REQUEST);
        return;
    }
    if (data.JS_TOOL_RESULT) {
        // Our own relay reply bounced back by the watch for the companion.
        return;
    }
    if (data.PROMPT) {
        var id = String(data.ANDROID_REQUEST_ID || '');
        var now = Date.now();
        Object.keys(handledPrompts).forEach(function(key) {
            if (now - handledPrompts[key] > 600000) { delete handledPrompts[key]; }
        });
        if (id && id !== '0') {
            if (handledPrompts[id]) { return; }
            handledPrompts[id] = now;
        }
        console.log("Starting a new Session...");
        var s = new session.Session(data.PROMPT, data.THREAD_ID, data.ANDROID_REQUEST_ID);
        if (currentSession) { currentSession.obsolete = true; }
        currentSession = s;
        s.run();
        return;
    }

    if (reminders.handleReminderMessage(data)) {
        return;
    }

    if (data.QUOTA_REQUEST) {
        console.log("Requesting quota...");
        quota.handleQuotaRequest();
    }
    if ('LOCATION_ENABLED' in data) {
        config.setSetting("LOCATION_ENABLED", !!data.LOCATION_ENABLED);
        console.log("Location enabled: " + config.isLocationEnabled());
        // We need to confirm that we received this for the watch to proceed.
        Pebble.sendAppMessage({
            LOCATION_ENABLED: data.LOCATION_ENABLED,
        });
    }
    if ('FEEDBACK_TEXT' in data) {
        console.log("Handling feedback...");
        feedback.handleFeedbackRequest(data);
    }
    if ('REPORT_THREAD_UUID' in data) {
        console.log("Handling report...");
        feedback.handleReportRequest(data);
    }
}

function doCobbleWarning() {
    if (window.cobble) {
        console.log("WARNING: Running Billy on Cobble is not supported, and has multiple known issues.");
        Pebble.sendAppMessage({COBBLE_WARNING: 1});
    }
}

Pebble.addEventListener("ready",
    function(e) {
        // This happens before anything else because I don't trust Cobble to get through the normal flow,
        // given how many things bizarrely don't work.
        doCobbleWarning();
        console.log("Billy " + package_json['version']);
        if (Pebble.platform === 'pypkjs') {
            console.log("Entering emulator mode.");
            var emulator_main = require('./emulator/emulator_main');
            emulator_main.main();
            return;
        }
        // Ordinary AI and the tool relay must work even when Timeline is unavailable.
        main();
        Pebble.getTimelineToken(function(token) {
            console.log("Entering real mode.");
            session.userToken = token;
        }, function(e) {
            console.log("Get timeline token failed???", e);
        })
    }
);
