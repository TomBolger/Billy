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
var location = require('../location');
var reminders = require('./reminders');
var package_json = require('package.json');
var session = require('../session');

function constructFeedbackMetadata(request) {
    var appVersion = 'unknown'
    var alarmCount = 0;
    if (request) {
        appVersion = '' + request['FEEDBACK_APP_MAJOR'] + '.' + request['FEEDBACK_APP_MINOR'];
        alarmCount = request['FEEDBACK_ALARM_COUNT'];
    }
    var locationEnabled = config.isLocationEnabled();
    var locationReady = location.isReady();
    var settings = config.getSettings();
    var unitPreference = settings['UNIT_PREFERENCE'] || '';
    var languagePreference = settings['LANGUAGE_CODE'] || '';
    var reminderCount = reminders.getAllReminders().length;
    var jsVersion = package_json['version'];
    var timezone = (-(new Date()).getTimezoneOffset());
    var platform = 'unknown';
    if (window.cobble) {
        platform = 'Cobble';
    } else if (window.navigator) {
        var userAgent = navigator.userAgent;
        var androidVersionRegex = /Android (\d+(?:\.\d+)?)/;
        var androidVersion = androidVersionRegex.exec(userAgent);
        if (androidVersion) {
            platform = 'Android ' + androidVersion[1];
        } else {
            platform = 'iOS';
        }
    } else {
        platform = 'iOS';
    }
    var watch = Pebble.getActiveWatchInfo ? Pebble.getActiveWatchInfo() : null;
    var watchFirmware = watch ? '' + watch.firmware.major + '.' + watch.firmware.minor + '.' + watch.firmware.patch : '(unknown)';
    if (watch && watch.firmware.suffix) {
        watchFirmware += '-' + watch.firmware.suffix;
    }
    var watchPlatform = watch ? watch.platform : '(unknown)';
    var model = watch ? watch.model : '(unknown)';
    return {
        'appVersion': appVersion,
        'alarmCount': alarmCount,
        'locationEnabled': locationEnabled,
        'locationReady': locationReady,
        'unitPreference': unitPreference,
        'languagePreference': languagePreference,
        'reminderCount': reminderCount,
        'jsVersion': jsVersion,
        'timezone': timezone,
        'platform': platform,
        'watchFirmware': watchFirmware,
        'watchModel': model,
        'watchPlatform': watchPlatform,
        'timelineToken': session.userToken,
        'assistantRuntime': config.getAssistantRuntime(),
        'geminiModel': config.getGeminiModel()
    };
}

function encodeQuery(params) {
    var pieces = [];
    Object.keys(params).forEach(function(key) {
        if (params[key] !== undefined && params[key] !== null && params[key] !== '') {
            pieces.push(encodeURIComponent(key) + '=' + encodeURIComponent(params[key]));
        }
    });
    return pieces.join('&');
}

function metadataMarkdown(request) {
    var lines = ['## Device metadata'];
    Object.keys(request).sort().forEach(function(key) {
        if (key === 'text' || key === 'thread_uuid') {
            return;
        }
        lines.push('- ' + key + ': ' + request[key]);
    });
    return lines.join('\n');
}

function githubIssueUrl(request, kind) {
    var base = config.getGithubIssuesUrl();
    var separator = base.indexOf('?') === -1 ? '?' : '&';
    var title = kind === 'report' ? 'Billy conversation report' : 'Billy feedback';
    var body = '## ' + (kind === 'report' ? 'Report' : 'Feedback') + '\n\n' +
        (request.text || 'Describe the issue here.') + '\n\n';
    if (request.thread_uuid) {
        body += 'Thread ID: `' + request.thread_uuid + '`\n\n';
    }
    body += metadataMarkdown(request);
    return base + separator + encodeQuery({
        title: title,
        body: body,
        labels: 'feedback'
    });
}

function sendGithubFallback(request, kind, callback) {
    var url = githubIssueUrl(request, kind);
    console.log("Opening GitHub feedback URL: " + url);
    try {
        Pebble.openURL(url);
        callback(true, 0);
    } catch (e) {
        console.log("Opening GitHub feedback URL failed: " + e.message);
        callback(false, 0);
    }
}

function sendPostRequest(request, url, callback) {
    var req = new XMLHttpRequest();
    req.open('POST', url, true);
    req.setRequestHeader('Content-Type', 'application/json');
    req.onload = function() {
        if (req.readyState === 4) {
            if (req.status >= 200 && req.status < 300) {
                console.log("Feedback sent successfully");
                callback(true, req.status);
            } else {
                console.log("Feedback request returned error code " + req.status.toString());
                callback(false, req.status);
            }
        }
    };
    req.onerror = function() {
        console.log("Feedback request failed before a response was received");
        callback(false, 0);
    };
    console.log("Feedback request: " + JSON.stringify(request));
    req.send(JSON.stringify(request));
}

function sendRequest(request, kind, callback) {
    request.kind = kind;
    var postUrl = config.getFeedbackPostUrl();
    if (postUrl) {
        sendPostRequest(request, postUrl, callback);
        return;
    }
    sendGithubFallback(request, kind, callback);
}

exports.sendFeedback = function(feedbackText, threadId, callback) {
    var feedback = constructFeedbackMetadata(null);
    if (feedbackText) {
        feedback['text'] = feedbackText;
    }
    if (threadId) {
        feedback['thread_uuid'] = threadId;
    }
    sendRequest(feedback, 'feedback', callback);
}

exports.handleFeedbackRequest = function(request) {
    var feedback = constructFeedbackMetadata(request);
    feedback['text'] = request['FEEDBACK_TEXT'];
    sendRequest(feedback, 'feedback', function(success, status) {
        Pebble.sendAppMessage({'FEEDBACK_SEND_RESULT': success ? 0 : 1});
    });
}

exports.handleReportRequest = function(request) {
    var report = constructFeedbackMetadata(request);
    report['thread_uuid'] = request['REPORT_THREAD_UUID'];
    sendRequest(report, 'report', function(success, status) {
        Pebble.sendAppMessage({'REPORT_SEND_RESULT': success ? 0 : 1});
    });
}
