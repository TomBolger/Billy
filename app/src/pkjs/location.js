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

var config = require('./config');

var cachedLon = undefined;
var cachedLat = undefined;
var cachedAccuracy = undefined;
var cachedUpdatedAt = undefined;

var MAX_PROMPT_LOCATION_AGE_MS = 30 * 60 * 1000;
var MAX_PROMPT_ACCURACY_METERS = 25000;

exports.update = function() {
    // start with whatever we knew before, if anything.
    var oldLon = localStorage.getItem('oldLon');
    var oldLat = localStorage.getItem('oldLat');
    var oldAccuracy = localStorage.getItem('oldAccuracy');
    var oldUpdatedAt = parseInt(localStorage.getItem('oldLocationUpdatedAt'), 10);
    if (oldLon && oldLat && oldUpdatedAt && Date.now() - oldUpdatedAt < MAX_PROMPT_LOCATION_AGE_MS) {
        cachedLon = parseFloat(oldLon);
        cachedLat = parseFloat(oldLat);
        cachedAccuracy = oldAccuracy ? parseFloat(oldAccuracy) : undefined;
        cachedUpdatedAt = oldUpdatedAt;
        console.log("Recent cached location restored: (" + cachedLat + ", " + cachedLon + ")");
    }
    
    navigator.geolocation.getCurrentPosition(function(pos) {
        cachedLat = pos.coords.latitude;
        cachedLon = pos.coords.longitude;
        cachedAccuracy = pos.coords.accuracy;
        cachedUpdatedAt = Date.now();
        console.log("position updated: (" + cachedLat + ", " + cachedLon + "), accuracy=" + cachedAccuracy);
        localStorage.setItem('oldLon', cachedLon);
        localStorage.setItem('oldLat', cachedLat);
        if (cachedAccuracy !== undefined) {
            localStorage.setItem('oldAccuracy', cachedAccuracy);
        }
        localStorage.setItem('oldLocationUpdatedAt', cachedUpdatedAt);
    }, function (err) {
        console.log("Failed to update location: " + err);
    }, {
        enableHighAccuracy: true,
        maximumAge: 60000,
        timeout: 10000,
    });
}

exports.isReady = function() {
    return !!(cachedLon && cachedLat);
}

exports.getPos = function() {
    return {lon: cachedLon, lat: cachedLat, accuracy: cachedAccuracy, updatedAt: cachedUpdatedAt};
}

exports.getPromptContextSentence = function() {
    if (!config.isLocationEnabled() || !exports.isReady()) {
        return '';
    }
    if (cachedUpdatedAt && Date.now() - cachedUpdatedAt > MAX_PROMPT_LOCATION_AGE_MS) {
        return '';
    }
    if (cachedAccuracy && cachedAccuracy > MAX_PROMPT_ACCURACY_METERS) {
        return '';
    }

    var ageText = cachedUpdatedAt ? Math.round((Date.now() - cachedUpdatedAt) / 60000) + ' minutes ago' : 'recently';
    var accuracyText = cachedAccuracy ? 'accuracy about ' + Math.round(cachedAccuracy) + ' meters' : 'accuracy unknown';
    return 'The user has granted location context. Current phone GPS location is latitude ' +
        cachedLat.toFixed(5) + ', longitude ' + cachedLon.toFixed(5) + ' (' + accuracyText +
        ', updated ' + ageText + '). For weather and local questions, use these coordinates instead of inferring location from IP address, account home, or network region.';
}
