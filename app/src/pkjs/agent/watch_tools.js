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

// Watch-owned tools: alarms, timers, reminders, and Billy settings.
//
// These are ALWAYS offered to Gemini. They also run on behalf of the Android
// companion through the watch relay (see relay.js), so both runtimes share one
// implementation and one set of descriptions.

var actions = require('../actions');

function obj(properties, required) {
    return {type: 'object', properties: properties, required: required || []};
}

function str(description, values) {
    var schema = {type: 'string', description: description};
    if (values) {
        schema['enum'] = values;
    }
    return schema;
}

function int(description) {
    return {type: 'integer', description: description};
}

function bool(description) {
    return {type: 'boolean', description: description};
}

var VIBES = ['Reveille', 'Mario', 'Nudge Nudge', 'Jackhammer', 'Standard'];
var ISO_TIME = "Local ISO 8601 time with the user's UTC offset, e.g. 2026-07-12T07:00:00-07:00.";

var DECLARATIONS = [
    {
        name: 'set_alarm',
        description: 'Set a watch alarm that rings at a clock time (e.g. "wake me at 7", "alarm for 6:30 tomorrow"). Use set_timer instead for durations ("in 10 minutes", "for 20 minutes").',
        parameters: obj({
            time: str('When the alarm rings. Must be in the future. ' + ISO_TIME),
            name: str('Optional label, only if the user gave one. Title Case.')
        }, ['time'])
    },
    {
        name: 'get_alarms',
        description: 'List the alarms currently set on the watch.',
        parameters: obj({})
    },
    {
        name: 'delete_alarm',
        description: 'Delete one alarm by its exact time. If you do not already know the exact time from this conversation, call get_alarms first.',
        parameters: obj({time: str('Exact alarm time as returned by get_alarms. ' + ISO_TIME)}, ['time'])
    },
    {
        name: 'set_timer',
        description: 'Start a countdown timer on the watch for a duration ("10 minute timer", "time the eggs for 7 minutes"). Shows a live countdown card. Give the total duration using any combination of hours, minutes, and seconds.',
        parameters: obj({
            hours: int('Hours part of the duration.'),
            minutes: int('Minutes part of the duration.'),
            seconds: int('Seconds part of the duration.'),
            name: str('Optional label, only if the user gave one (e.g. "Pasta"). Title Case.')
        })
    },
    {
        name: 'get_timers',
        description: 'List running timers with time remaining.',
        parameters: obj({})
    },
    {
        name: 'delete_timer',
        description: 'Cancel one running timer. Pass its expiration time from get_timers. Call get_timers first unless you already know it.',
        parameters: obj({time: str('Timer expiration time exactly as returned by get_timers (expirationTimeForDeletingAndWidgets).')}, ['time'])
    },
    {
        name: 'set_reminder',
        description: 'Create a reminder that notifies the user on the watch at a time and appears on the Pebble timeline. Provide exactly one of time or delay_minutes. If the user gives a clock time without a day, use the next occurrence of that time.',
        parameters: obj({
            what: str('What to remind the user about, phrased as the task (e.g. "Call mom").'),
            time: str('When to remind. ' + ISO_TIME),
            delay_minutes: int('Minutes from now, for relative requests like "in 20 minutes".')
        }, ['what'])
    },
    {
        name: 'get_reminders',
        description: 'List the active reminders with their ids and times.',
        parameters: obj({})
    },
    {
        name: 'delete_reminder',
        description: 'Delete a reminder by id. Call get_reminders first to find the id.',
        parameters: obj({id: str('Reminder id from get_reminders.')}, ['id'])
    },
    {
        name: 'update_settings',
        description: "Change Billy's own settings: units, response language, alarm/timer vibration, quick-launch behaviour, or whether dictated prompts need confirmation. Only include the settings being changed.",
        parameters: obj({
            unitSystem: str('Measurement units.', ['imperial', 'metric', 'uk hybrid', 'both', 'auto']),
            responseLanguage: str("Language code such as 'en_US', 'de_DE', 'fr_FR', or 'auto'."),
            alarmVibrationPattern: str('Alarm vibration pattern.', VIBES),
            timerVibrationPattern: str('Timer vibration pattern.', VIBES),
            quickLaunchBehaviour: str('What quick launch does.', ['start conversation and time out', 'start conversation and stay open', 'open home screen']),
            confirmPrompts: bool('True to confirm dictated prompts before sending.')
        })
    }
];

var NAMES = DECLARATIONS.map(function(d) {
    return d.name;
});

exports.getDeclarations = function() {
    return DECLARATIONS;
};

exports.handles = function(name) {
    return NAMES.indexOf(name) !== -1 || name === 'get_alarm' || name === 'get_timer';
};

function progress(session, text) {
    if (session && session.handleMessage) {
        session.handleMessage({data: 'f' + text});
    }
}

function callAction(session, action, callback) {
    var ws = {
        send: function(resultString) {
            var result;
            try {
                result = JSON.parse(resultString);
            } catch (e) {
                result = {error: e.message};
            }
            if (result && result.error && !result.status) {
                result.status = 'error';
            }
            callback(result);
        }
    };
    actions.handleAction(session, ws, JSON.stringify(action));
}

function toInt(value) {
    var parsed = parseInt(value, 10);
    return isNaN(parsed) ? 0 : parsed;
}

function timerSeconds(args) {
    return toInt(args.seconds) + toInt(args.duration_seconds) +
        (toInt(args.minutes) + toInt(args.duration_minutes)) * 60 +
        (toInt(args.hours) + toInt(args.duration_hours)) * 3600;
}

function formatDuration(seconds) {
    var h = Math.floor(seconds / 3600);
    var m = Math.floor((seconds % 3600) / 60);
    var s = seconds % 60;
    var parts = [];
    if (h) {
        parts.push(h + ' h');
    }
    if (m) {
        parts.push(m + ' min');
    }
    if (s) {
        parts.push(s + ' s');
    }
    return parts.join(' ') || '0 s';
}

function fail(callback, summary) {
    callback({status: 'error', summary: summary});
}

// Executes a watch tool. `session` needs handleMessage (progress text) and
// enqueue (AppMessage to the watch). Returns false if the name is not ours.
exports.execute = function(session, name, args, callback) {
    args = args || {};
    switch (name) {
    case 'set_alarm':
        if (!args.time || isNaN(new Date(args.time).getTime())) {
            fail(callback, 'set_alarm needs a valid ISO time.');
            return true;
        }
        progress(session, 'Setting an alarm');
        callAction(session, {action: 'set_alarm', isTimer: false, time: args.time, name: args.name || null, cancel: false}, function(result) {
            if (result.status === 'ok') {
                result.summary = 'Alarm set for ' + args.time + (args.name ? ' (' + args.name + ')' : '') + '.';
            }
            callback(result);
        });
        return true;
    case 'get_alarms':
    case 'get_alarm':
        progress(session, 'Checking alarms');
        callAction(session, {action: 'get_alarm', isTimer: false}, callback);
        return true;
    case 'delete_alarm':
        if (!args.time) {
            fail(callback, 'delete_alarm needs the exact alarm time. Call get_alarms first.');
            return true;
        }
        progress(session, 'Deleting alarm');
        callAction(session, {action: 'set_alarm', isTimer: false, time: args.time, cancel: true}, callback);
        return true;
    case 'set_timer':
        var seconds = timerSeconds(args);
        if (seconds < 1) {
            fail(callback, 'set_timer needs a positive duration.');
            return true;
        }
        progress(session, 'Starting timer');
        var startedAt = Date.now();
        callAction(session, {action: 'set_alarm', isTimer: true, duration: seconds, name: args.name || null, cancel: false}, function(result) {
            if (result.status === 'ok') {
                var target = Math.round(startedAt / 1000) + seconds;
                var card = {TIMER_WIDGET: 1, TIMER_WIDGET_TARGET_TIME: target};
                if (args.name) {
                    card.TIMER_WIDGET_NAME = String(args.name).substring(0, 30);
                }
                session.enqueue(card);
                result.summary = formatDuration(seconds) + ' timer started' + (args.name ? ' (' + args.name + ')' : '') + '.';
                result.watch_card = 'A live countdown card is already on screen; do not repeat the duration at length.';
            }
            callback(result);
        });
        return true;
    case 'get_timers':
    case 'get_timer':
        progress(session, 'Checking timers');
        callAction(session, {action: 'get_alarm', isTimer: true}, callback);
        return true;
    case 'delete_timer':
        if (!args.time) {
            fail(callback, 'delete_timer needs the timer expiration time. Call get_timers first.');
            return true;
        }
        progress(session, 'Cancelling timer');
        callAction(session, {action: 'set_alarm', isTimer: true, time: args.time, cancel: true}, callback);
        return true;
    case 'set_reminder':
        var what = String(args.what || '').trim();
        if (!what) {
            fail(callback, 'set_reminder needs the reminder text in "what".');
            return true;
        }
        var time = args.time;
        var delay = toInt(args.delay_minutes || args.delay_mins);
        if (!time && delay > 0) {
            time = new Date(Date.now() + delay * 60000).toISOString();
        }
        if (!time || isNaN(new Date(time).getTime())) {
            fail(callback, 'set_reminder needs a time or delay_minutes.');
            return true;
        }
        progress(session, 'Setting reminder');
        callAction(session, {action: 'set_reminder', what: what, time: time}, callback);
        return true;
    case 'get_reminders':
        progress(session, 'Checking reminders');
        callAction(session, {action: 'get_reminders'}, callback);
        return true;
    case 'delete_reminder':
        if (!args.id) {
            fail(callback, 'delete_reminder needs an id. Call get_reminders first.');
            return true;
        }
        progress(session, 'Deleting reminder');
        callAction(session, {action: 'delete_reminder', id: String(args.id)}, callback);
        return true;
    case 'update_settings':
        progress(session, 'Updating settings');
        var action = {action: 'update_settings'};
        Object.keys(args).forEach(function(key) {
            action[key] = args[key];
        });
        callAction(session, action, callback);
        return true;
    default:
        return false;
    }
};
