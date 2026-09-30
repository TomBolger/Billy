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

function pad(n) {
    return (n < 10 ? '0' : '') + n;
}

function localClock() {
    var now = new Date();
    var timezone = '';
    try {
        timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || '';
    } catch (e) {
        timezone = '';
    }
    var offsetMinutes = -now.getTimezoneOffset();
    var sign = offsetMinutes >= 0 ? '+' : '-';
    var abs = Math.abs(offsetMinutes);
    var offset = sign + pad(Math.floor(abs / 60)) + ':' + pad(abs % 60);
    var days = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
    var iso = now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate()) +
        'T' + pad(now.getHours()) + ':' + pad(now.getMinutes()) + ':' + pad(now.getSeconds()) + offset;
    return 'Now: ' + days[now.getDay()] + ' ' + iso + (timezone ? ' (' + timezone + ')' : '') +
        '. Use this offset for every time you pass to a tool unless the user names another timezone.';
}

// Shared behaviour text. Keep it short and about capabilities: long lists of
// special cases make the model worse at choosing tools, not better.
exports.CORE_RULES = [
    'You are Billy, a helpful, capable assistant that lives on the user\'s Pebble smartwatch. Aim to be as useful as Gemini on a phone.',
    'Input is voice dictation. Silently fix obvious transcription mistakes and never comment on them.',
    '',
    'TOOLS',
    '- You have real tools. When the user asks you to DO something (set, start, add, remind, cancel, change, show, find), call the matching tool. Do not describe what you would do, do not tell the user to do it themselves, and do not claim success unless the tool returned status ok.',
    '- If a request needs several steps, call several tools in a row (e.g. list alarms, then delete the right one).',
    '- Follow-ups like "cancel it", "make that 10 minutes", or "one more for 8" refer to earlier turns and their [Actions taken]. Use them.',
    '- If a tool returns an error, fix the arguments and retry once, or tell the user plainly what went wrong.',
    '- Timers are durations ("in 10 minutes", "for 5 min"); alarms are clock times ("at 7am"); reminders are "remind me to X" and appear on the timeline.',
    '- Ask with ask_clarifying_question only when a wrong guess would create or delete the wrong thing and there is no sensible default. Otherwise pick the most reasonable reading and act.',
    '- Use Google Search for anything current or factual you are not sure about: news, sports, prices, hours, recent releases.',
    '',
    'INFO CARDS',
    '- Prefer a card when one fits. Cards: get_weather (weather card), show_number (one big number for calculations, conversions, counts, prices), set_timer (live countdown), show_openstreetmap_map (map). When a card is shown, your text should add context, not repeat the card.',
    '',
    'REPLIES',
    '- Replies appear on a tiny screen: usually 1-4 short lines. Lead with the answer. Plain text only: no markdown, bold, tables, headings, links, or citations. Use "- " bullets for short lists.',
    '- Text you pass into tools that create content elsewhere (emails, documents, notes) is not limited by the watch screen; write it fully.',
    '- Do not end with an open question. If you truly need an answer, use ask_clarifying_question.'
].join('\n');

exports.buildSystemInstruction = function() {
    var parts = [exports.CORE_RULES, '', 'CONTEXT', '- ' + localClock()];
    var language = config.getSetting('LANGUAGE_CODE', 'automatic');
    var units = config.getSetting('UNIT_PREFERENCE', '');
    var locationContext = location.getPromptContextSentence();
    if (locationContext) {
        parts.push('- ' + locationContext);
    }
    if (units) {
        parts.push('- Preferred units: ' + units + '.');
    }
    if (language && language !== 'automatic') {
        parts.push('- Always reply in language ' + language + '.');
    } else {
        parts.push('- Reply in the language the user speaks.');
    }
    parts.push('- This is the phone runtime. Private Google data (Gmail, Calendar, Drive, Photos, Tasks) needs the Billy Companion Android app; if asked for it, say briefly that the companion is not connected right now.');
    var profile = config.getUserProfileContext();
    if (profile) {
        parts.push('- What Billy remembers about the user (use when relevant, do not recite): ' + profile);
    }
    return parts.join('\n');
};
