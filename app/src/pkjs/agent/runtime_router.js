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
// bounded time for that claim. No keyword guessing.
//
// The phone runtime has fewer features than the companion (no background
// work, no Android integrations), so it must never quietly stand in for a
// companion that should have answered: if the user uses the companion (mode
// "android", or "automatic" with a companion seen recently) and it does not
// claim the prompt in time, the watch shows a clear error instead. The phone
// runtime answers only when the user chose it (companionless) or no companion
// has ever been seen (iPhone, or Android without the companion).

var config = require('../config');
var CompanionlessRuntime = require('./companionless').CompanionlessRuntime;
var diagnostics = require('./diagnostics');

var SEEN_KEY = 'androidCompanionSeenAt';
var CLAIM_PREFIX = 'androidCompanionClaimed:';
var CLAIM_TTL_MS = 10 * 60 * 1000;
var RECENTLY_SEEN_MS = 7 * 24 * 60 * 60 * 1000;
var AUTOMATIC_CLAIM_WAIT_MS = 2500;
var ANDROID_MODE_CLAIM_WAIT_MS = 4500;
var POLL_MS = 100;
var COMPANION_MISSING_MESSAGE = 'Billy Companion on your phone didn\'t pick this up. ' +
    'Open Billy Companion once, and check that it\'s allowed to run in the background. ' +
    'You can report this from the menu.';

// request id -> diagnostics turn id, for requests we gave up on. A claim that
// arrives afterwards is only noted in the diagnostics; we never answer twice.
var gaveUp = {};

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

// The oldest Billy Companion that works with Google's current Gemini API
// (0.7, version code 81). Older ones get a once-a-day reminder on the watch.
var MIN_COMPANION_VERSION = 81;
var UPDATE_NAG_KEY = 'billyCompanionUpdateNagAt';
var UPDATE_NAG_EVERY_MS = 24 * 60 * 60 * 1000;
exports.MIN_COMPANION_VERSION = MIN_COMPANION_VERSION;

function remindToUpdate(version) {
    var last = parseInt(localStorage.getItem(UPDATE_NAG_KEY), 10);
    if (last && Date.now() - last < UPDATE_NAG_EVERY_MS) {
        return;
    }
    localStorage.setItem(UPDATE_NAG_KEY, Date.now());
    console.log('Billy Companion version ' + version + ' is out of date.');
    if (typeof Pebble !== 'undefined' && typeof Pebble.showSimpleNotificationOnPebble === 'function') {
        Pebble.showSimpleNotificationOnPebble('Update Billy Companion',
            'Your Billy Companion app is out of date and some answers will fail. On your phone, get the new version from ' +
            'github.com/TomBolger/Billy/releases and install it over the old one. Your setup is kept.');
    }
}

exports.recordAndroidCompanionSeen = function(requestId, version) {
    localStorage.setItem(SEEN_KEY, Date.now());
    var companionVersion = parseInt(version, 10) || 1;
    if (companionVersion < MIN_COMPANION_VERSION) {
        remindToUpdate(companionVersion);
    }
    var id = normalizeRequestId(requestId);
    if (id) {
        localStorage.setItem(CLAIM_PREFIX + id, Date.now());
        console.log('Android companion claimed request ' + id + '.');
        if (gaveUp[id]) {
            console.log('Android companion claimed request ' + id + ' after Billy showed the "companion silent" error.');
            diagnostics.noteLateClaim(gaveUp[id]);
            delete gaveUp[id];
        }
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

function runPhone(session, path, waitedMs) {
    session.diagInfo = {path: path, claimWaitMs: waitedMs};
    new CompanionlessRuntime(session).run();
}

// Shows the error exactly the way the phone runtime shows its own failures:
// a warning message, end of answer, then the connection close.
function showCompanionMissing(session, requestId, waitedMs) {
    var turn = diagnostics.begin('error-companion-silent', waitedMs);
    gaveUp[normalizeRequestId(requestId)] = turn.id;
    diagnostics.end(turn, 'error', COMPANION_MISSING_MESSAGE);
    session.handleMessage({data: 'w' + COMPANION_MISSING_MESSAGE});
    session.handleMessage({data: 'd'});
    session.handleClose({code: 1000, reason: '', wasClean: true});
}

exports.run = function(session) {
    var runtime = config.getAssistantRuntime();
    session.shouldStandDown = function() {
        return !!session.obsolete || (runtime !== config.RUNTIME_COMPANIONLESS && isClaimed(session.androidRequestId));
    };
    if (runtime === config.RUNTIME_COMPANIONLESS) {
        runPhone(session, 'phone-companionless', 0);
        return;
    }
    if (!normalizeRequestId(session.androidRequestId)) {
        // No request id means the companion cannot claim this prompt at all.
        runPhone(session, 'phone-no-request-id', 0);
        return;
    }
    pruneClaims();
    var companionExpected = runtime === config.RUNTIME_ANDROID || seenRecently();
    if (!companionExpected) {
        runPhone(session, 'phone-no-companion', 0);
        return;
    }
    var waitMs = claimWaitMs(runtime);
    var started = Date.now();
    (function poll() {
        if (session.obsolete) {
            return;
        }
        if (isClaimed(session.androidRequestId)) {
            console.log('Android companion is answering; phone runtime stands down.');
            var claimed = diagnostics.begin('companion-claimed', Date.now() - started);
            diagnostics.end(claimed, 'companion-answering');
            return;
        }
        if (Date.now() - started >= waitMs) {
            console.log('Android companion did not claim the prompt; showing an error.');
            showCompanionMissing(session, session.androidRequestId, Date.now() - started);
            return;
        }
        setTimeout(poll, POLL_MS);
    })();
};

exports.COMPANION_MISSING_MESSAGE = COMPANION_MISSING_MESSAGE;
