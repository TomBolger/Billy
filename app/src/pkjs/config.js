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

exports.getSettings = function() {
    return JSON.parse(localStorage.getItem('clay-settings')) || {};
}

exports.getSetting = function(key, defaultValue) {
    var settings = exports.getSettings();
    if (settings[key] !== undefined) {
        return settings[key];
    }
    return defaultValue;
}

exports.setSetting = function(key, value) {
    var settings = exports.getSettings();
    settings[key] = value;
    localStorage.setItem('clay-settings', JSON.stringify(settings));
}

exports.isLocationEnabled = function() {
    return !!exports.getSettings()['LOCATION_ENABLED'];
}

exports.RUNTIME_AUTOMATIC = 'automatic';
exports.RUNTIME_COMPANIONLESS = 'companionless';
exports.RUNTIME_ANDROID = 'android';

exports.getAssistantRuntime = function() {
    return exports.getSetting('ASSISTANT_RUNTIME', exports.RUNTIME_AUTOMATIC);
}

exports.getGeminiApiKey = function() {
    return String(exports.getSetting('GEMINI_API_KEY', '') || '').replace(/\s+/g, '');
}

exports.DEFAULT_GEMINI_MODEL = 'gemini-3.8-flash';
// Used only when the chosen model is unavailable (overloaded, 404, network).
exports.FALLBACK_MODELS = ['gemini-3.8-flash', 'gemini-3.7-flash', 'gemini-3.1-flash-lite'];
var LEGACY_DEFAULT_MODEL = 'gemini-3.1-flash-lite';
var MODEL_MIGRATION_KEY = 'billyModelDefaultMigratedV2';

exports.getGeminiModel = function() {
    var model = String(exports.getSetting('GEMINI_MODEL', '') || '').replace(/\s+/g, '');
    // Billy used to default to Flash-Lite, which is too weak to pick reliably
    // from dozens of tools. Move the old default forward once; an explicit
    // later choice of Flash-Lite is respected.
    if (model === LEGACY_DEFAULT_MODEL && !localStorage.getItem(MODEL_MIGRATION_KEY)) {
        localStorage.setItem(MODEL_MIGRATION_KEY, '1');
        exports.setSetting('GEMINI_MODEL', exports.DEFAULT_GEMINI_MODEL);
        model = exports.DEFAULT_GEMINI_MODEL;
    }
    if (!localStorage.getItem(MODEL_MIGRATION_KEY)) {
        localStorage.setItem(MODEL_MIGRATION_KEY, '1');
    }
    return model || exports.DEFAULT_GEMINI_MODEL;
}

exports.getGeminiMonthlyBudgetUsd = function() {
    return exports.getSetting('GEMINI_MONTHLY_BUDGET_USD', '10');
}

exports.getGithubIssuesUrl = function() {
    return exports.getSetting('GITHUB_ISSUES_URL', 'https://github.com/tombolger/Billy/issues/new');
}

exports.getFeedbackPostUrl = function() {
    return String(exports.getSetting('FEEDBACK_POST_URL', '') || '').replace(/^\s+|\s+$/g, '');
}

exports.getUserProfileContext = function() {
    return String(exports.getSetting('USER_PROFILE_CONTEXT', '') || '')
        .replace(/[\r\n]+/g, ' ')
        .replace(/\s+/g, ' ')
        .replace(/^\s+|\s+$/g, '')
        .substring(0, 1200);
}
