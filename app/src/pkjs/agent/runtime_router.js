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

var config = require('../config');
var CompanionlessRuntime = require('./companionless').CompanionlessRuntime;
var watchTools = require('./watch_tools');

var COMPANION_SEEN_KEY = 'androidCompanionSeenAt';
var COMPANION_RECENT_MS = 15000;
var AUTOMATIC_COMPANION_WAIT_MS = 1200;

function isAndroidCompanionAvailable() {
    var seenAt = parseInt(localStorage.getItem(COMPANION_SEEN_KEY), 10);
    return !!seenAt && Date.now() - seenAt < COMPANION_RECENT_MS;
}

function shouldWaitForAndroidCompanion(prompt) {
    return config.getAssistantRuntime() === config.RUNTIME_AUTOMATIC &&
        !watchTools.shouldExpose(prompt || '') &&
        !isAndroidCompanionAvailable();
}

exports.recordAndroidCompanionSeen = function() {
    localStorage.setItem(COMPANION_SEEN_KEY, Date.now());
    console.log('Android companion heartbeat recorded.');
}

exports.selectRuntime = function(prompt, threadId) {
    var runtime = config.getAssistantRuntime();
    if (watchTools.shouldExpose(prompt || '')) {
        return config.RUNTIME_COMPANIONLESS;
    }
    if (runtime === config.RUNTIME_ANDROID) {
        return config.RUNTIME_ANDROID;
    }
    if (runtime === config.RUNTIME_COMPANIONLESS) {
        return config.RUNTIME_COMPANIONLESS;
    }
    if (isAndroidCompanionAvailable()) {
        return config.RUNTIME_ANDROID;
    }
    return config.RUNTIME_COMPANIONLESS;
}

exports.run = function(session) {
    if (!session.waitedForAndroidCompanion && shouldWaitForAndroidCompanion(session.prompt)) {
        session.waitedForAndroidCompanion = true;
        console.log('Automatic runtime waiting briefly for Android companion heartbeat.');
        setTimeout(function() {
            exports.run(session);
        }, AUTOMATIC_COMPANION_WAIT_MS);
        return;
    }
    var runtime = exports.selectRuntime(session.prompt, session.threadId);
    console.log('Selected assistant runtime: ' + runtime);
    if (runtime === config.RUNTIME_ANDROID) {
        console.log('Android companion runtime selected; JS phone runtime is standing down.');
        return;
    }
    new CompanionlessRuntime(session).run();
}
