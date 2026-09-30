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

// Decides which runtime answers a prompt. Exactly one runtime answers.
//
// Every prompt from the watch reaches both this phone JS and (if installed)
// the Android companion. The companion claims a prompt by echoing its request
// id back through the watch the moment it receives it. This file waits a short,
// bounded time for that claim and otherwise answers itself. No keyword guessing:
// whichever runtime answers has every tool, including alarms, timers,
// reminders, and settings (the companion reaches those through relay.js).

var config = require('../config');
var CompanionlessRuntime = require('./companionless').CompanionlessRuntime;

var SEEN_KEY = 'androidCompanionSeenAt';
var CLAIM_PREFIX = 'androidCompanionClaimed:';
var CLAIM_TTL_MS = 10 * 60 * 1000;
var RECENTLY_SEEN_MS = 7 * 24 * 60 * 60 * 1000;
var AUTOMATIC_CLAIM_WAIT_MS = 2500;
var ANDROID_MODE_CLAIM_WAIT_MS = 4500;
var POLL_MS = 100;

function normalizeRequestId(requestId) {
    if (requestId === undefined || requestId === null || requestId === 0 || requestId === '0') {
        return '';
    }
    return String(requestId);
}

function seenRecently() {
    var seenAt = parseInt(localStorage.getItem(SEEN_KEY), 10);
    return !!seenAt && Date.now() - seenAt < RECENTLY_SEEN_MS;
}

function isClaimed(requestId) {
    var id = normalizeRequestId(requestId);
    if (!id) {
        return false;
    }
    var claimedAt = parseInt(localStorage.getItem(CLAIM_PREFIX + id), 10);
    return !!claimedAt && Date.now() - claimedAt < CLAIM_TTL_MS;
}

function pruneClaims() {
    try {
        for (var i = localStorage.length - 1; i >= 0; i--) {
            var key = localStorage.key(i);
            if (key && key.indexOf(CLAIM_PREFIX) === 0) {
                var at = parseInt(localStorage.getItem(key), 10);
                if (!at || Date.now() - at > CLAIM_TTL_MS) {
                    localStorage.removeItem(key);
                }
            }
        }
    } catch (e) {
        // best effort
    }
}

exports.recordAndroidCompanionSeen = function(requestId) {
    localStorage.setItem(SEEN_KEY, Date.now());
    var id = normalizeRequestId(requestId);
    if (id) {
        localStorage.setItem(CLAIM_PREFIX + id, Date.now());
        console.log('Android companion claimed request ' + id + '.');
    }
};

function claimWaitMs(runtime) {
    if (runtime === config.RUNTIME_ANDROID) {
        return ANDROID_MODE_CLAIM_WAIT_MS;
    }
    if (runtime === config.RUNTIME_AUTOMATIC && seenRecently()) {
        return AUTOMATIC_CLAIM_WAIT_MS;
    }
    return 0;
}

exports.run = function(session) {
    var runtime = config.getAssistantRuntime();
    session.shouldStandDown = function() {
        return runtime !== config.RUNTIME_COMPANIONLESS && isClaimed(session.androidRequestId);
    };
    if (runtime === config.RUNTIME_COMPANIONLESS || !normalizeRequestId(session.androidRequestId)) {
        new CompanionlessRuntime(session).run();
        return;
    }
    pruneClaims();
    var waitMs = claimWaitMs(runtime);
    var started = Date.now();
    (function poll() {
        if (isClaimed(session.androidRequestId)) {
            console.log('Android companion is answering; phone runtime stands down.');
            return;
        }
        if (Date.now() - started >= waitMs) {
            if (runtime === config.RUNTIME_ANDROID) {
                console.log('Android companion did not claim the prompt; answering from the phone instead.');
            }
            new CompanionlessRuntime(session).run();
            return;
        }
        setTimeout(poll, POLL_MS);
    })();
};
