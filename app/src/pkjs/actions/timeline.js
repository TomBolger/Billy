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

// The timeline public URL root
var API_URL_ROOT = 'https://timeline-api.rebble.io/';

function timelineRequest(pin, type, topics, apiKey, callback) {
    var finished = false;
    var xhr = new XMLHttpRequest();
    var timer = setTimeout(function() {
        finish(new Error('Timeline did not confirm the request. Check before retrying.'));
        if (xhr.abort) { xhr.abort(); }
    }, 8000);
    function finish(error) {
        if (finished) { return; }
        finished = true;
        clearTimeout(timer);
        if (callback) { callback(error || null); }
    }
    xhr.onload = function() {
        finish(xhr.status >= 200 && xhr.status < 300 ? null :
            new Error('Timeline returned HTTP ' + xhr.status + '.'));
    };
    xhr.onerror = function() { finish(new Error('Could not reach Timeline.')); };
    xhr.ontimeout = function() { finish(new Error('Timeline did not confirm the request.')); };
    xhr.open(type, API_URL_ROOT + 'v1/' + (topics != null ? 'shared/' : 'user/') + 'pins/' + pin.id, true);
    xhr.timeout = 8000;
    xhr.setRequestHeader('Content-Type', 'application/json');
    if (topics != null) {
        xhr.setRequestHeader('X-Pin-Topics', topics.join(','));
        xhr.setRequestHeader('X-API-Key', apiKey);
    }
    Pebble.getTimelineToken(function(token) {
        if (finished) { return; }
        try {
            xhr.setRequestHeader('X-User-Token', token);
            xhr.send(JSON.stringify(pin));
        } catch (e) { finish(e); }
    }, function() { finish(new Error('Timeline authorization is unavailable.')); });
}

// Insert a pin into the timeline
exports.insertUserPin = function(pin, callback) {
    timelineRequest(pin, 'PUT', null, null, callback);
};

// Delete a pin from the timeline
exports.deleteUserPin = function(pinId, callback) {
    var pin = { "id": pinId };
    timelineRequest(pin, 'DELETE', null, null, callback);
};
