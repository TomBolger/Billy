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

var MAX_CONTEXT_LENGTH = 1200;

function schema(properties, required) {
    return {
        type: 'object',
        properties: properties,
        required: required || []
    };
}

function stringSchema(description) {
    return {
        type: 'string',
        description: description
    };
}

function clean(text) {
    return String(text || '')
        .replace(/[\r\n]+/g, ' ')
        .replace(/\s+/g, ' ')
        .replace(/^\s+|\s+$/g, '');
}

function splitMemories(context) {
    return clean(context)
        .split(/\s*(?:;|\u2022|\|)\s*/g)
        .map(clean)
        .filter(function(item) {
            return item.length > 0;
        });
}

function saveMemories(memories) {
    var value = memories
        .map(clean)
        .filter(function(item, index, list) {
            return item.length > 0 && list.indexOf(item) === index;
        })
        .join('; ')
        .substring(0, MAX_CONTEXT_LENGTH);
    config.setSetting('USER_PROFILE_CONTEXT', value);
    return value;
}

exports.getDeclarations = function() {
    return [
        {
            type: 'function',
            name: 'get_billy_user_profile',
            description: 'Read Billy companionless local profile/memory notes. Use when the user asks what Billy knows or remembers about them.',
            parameters: schema({}, [])
        },
        {
            type: 'function',
            name: 'remember_billy_user_fact',
            description: 'Store a durable Billy memory only when the user explicitly asks Billy to remember/save a personal fact or clearly corrects durable profile information.',
            parameters: schema({
                fact: stringSchema('Short durable personal fact or preference to remember.')
            }, ['fact'])
        },
        {
            type: 'function',
            name: 'forget_billy_user_fact',
            description: 'Remove Billy companionless local profile/memory notes matching the user request.',
            parameters: schema({
                query: stringSchema('Memory text, person, preference, or topic to forget.')
            }, ['query'])
        }
    ];
}

exports.shouldExpose = function(prompt) {
    return /\b(remember|forget|forgot|delete.*memory|what.*know.*about me|what.*remember.*about me|my profile|about me)\b/i.test(prompt);
}

exports.execute = function(session, call, callback) {
    var args = call.arguments || {};
    if (typeof args === 'string') {
        try {
            args = JSON.parse(args);
        } catch (e) {
            args = {};
        }
    }
    var current = config.getUserProfileContext();
    if (call.name === 'get_billy_user_profile') {
        callback({
            status: 'ok',
            profile_context: current,
            summary: current ? 'Billy has local profile notes.' : 'Billy has no local profile notes yet.'
        });
        return true;
    }
    if (call.name === 'remember_billy_user_fact') {
        var fact = clean(args.fact).substring(0, 180);
        if (!fact) {
            callback({status: 'rejected', summary: 'No durable fact was provided.'});
            return true;
        }
        var memories = splitMemories(current).filter(function(item) {
            return item.toLowerCase() !== fact.toLowerCase();
        });
        memories.push(fact);
        saveMemories(memories);
        callback({status: 'ok', summary: 'Remembered: ' + fact, remembered: fact});
        return true;
    }
    if (call.name === 'forget_billy_user_fact') {
        var query = clean(args.query).toLowerCase();
        if (!query) {
            callback({status: 'rejected', summary: 'No memory query was provided.'});
            return true;
        }
        var before = splitMemories(current);
        var after = before.filter(function(item) {
            var lower = item.toLowerCase();
            return lower.indexOf(query) < 0 && query.indexOf(lower) < 0;
        });
        saveMemories(after);
        callback({
            status: before.length === after.length ? 'not_found' : 'ok',
            summary: before.length === after.length ? 'I did not find a matching Billy memory.' : 'Forgot ' + (before.length - after.length) + ' Billy memory.',
            removed_count: before.length - after.length
        });
        return true;
    }
    return false;
}
