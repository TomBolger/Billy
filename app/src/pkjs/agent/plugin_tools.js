/**
 * Gemini tools for Pebble plugins: read any plugin source and run any plugin
 * action, with the installed catalog written into the tool descriptions.
 * Offered only when the user has plugins turned on. Billy Companion uses the
 * same tools through the watch relay (see relay.js).
 */

var plugins = require('./plugins');

// Sources a one-off read would misuse: subscribing to find_my_phone rings
// the phone, and these are better served by Billy's own tools or are internal.
var SKIP_SOURCES = ['phone/find_my_phone', 'preferences/complications'];

function sourceKey(category, item) {
    return category + '/' + item;
}

function argsSummary(parameters) {
    var props = (parameters && parameters.properties) || {};
    var required = (parameters && parameters.required) || [];
    return Object.keys(props).map(function(name) {
        return name + (required.indexOf(name) !== -1 ? '*' : '');
    }).join(', ');
}

/** {sources: [...], actions: [...]} as short lines. */
exports.catalogLines = function() {
    var sources = [];
    var actions = [];
    plugins.catalog().forEach(function(plugin) {
        (plugin.sources || []).forEach(function(source) {
            (source.items || []).forEach(function(item) {
                var key = sourceKey(source.category, item);
                if (SKIP_SOURCES.indexOf(key) !== -1) {
                    return;
                }
                var props = Object.keys(source.properties || {}).filter(function(p) {
                    return p !== 'app_icon' && p !== 'artwork';
                });
                sources.push(key + ' (' + plugin.name + '): ' + props.slice(0, 10).join(', '));
            });
        });
        (plugin.actions || []).forEach(function(action) {
            actions.push(plugin.name + ' > ' + action.name + '(' + argsSummary(action.parameters) + ')' +
                (action.description ? ': ' + String(action.description).substring(0, 90) : '') +
                (action.requiresConfirmation || action.destructive ? ' [confirm first]' : ''));
        });
    });
    return {sources: sources, actions: actions};
};

function findAction(pluginRef, actionName) {
    var ref = String(pluginRef || '').toLowerCase();
    var list = plugins.catalog();
    for (var i = 0; i < list.length; i++) {
        var plugin = list[i];
        if (String(plugin.uuid).toLowerCase() !== ref && String(plugin.name).toLowerCase() !== ref) {
            continue;
        }
        var actions = plugin.actions || [];
        for (var j = 0; j < actions.length; j++) {
            if (actions[j].name === actionName) {
                return {plugin: plugin, action: actions[j]};
            }
        }
        return {plugin: plugin, action: null};
    }
    return null;
}

var READ = 'read_phone_data';
var ACT = 'use_phone_plugin';
var LIST = 'list_phone_plugins';

exports.NAMES = [READ, ACT, LIST];

function readDeclaration(lines) {
    return {
        name: READ,
        description: 'Read live data from the phone through Pebble plugins. Use it for the phone\'s own calendar (calendar/event: next 10 events within a week), ' +
            'phone battery and connectivity (phone/phone_state), what\'s playing (music/track), recent notifications (notifications/notification, newest first), ' +
            'apps and watchfaces on the watch (watch/app), the watch\'s settings, and any third-party plugin. Available now:\n' +
            (lines.sources.join('\n') || '(none)'),
        parameters: {
            type: 'object',
            properties: {
                category: {type: 'string', description: 'e.g. calendar'},
                item: {type: 'string', description: 'e.g. event'},
                instance: {type: 'string', description: 'Optional instance id from an earlier read.'}
            },
            required: ['category', 'item']
        }
    };
}

function actDeclaration(lines) {
    return {
        name: ACT,
        description: 'Do something through a Pebble plugin: ring the phone to find it, play/pause/skip music, open an app or watchface on the watch ' +
            '(launch_app with the app\'s id from watch/app), change a watch setting, or a third-party plugin\'s actions (add a task, switch a light...). ' +
            'Args marked * are required. For actions marked [confirm first], or anything that sends a message, deletes or spends money, ask the user with ' +
            'ask_clarifying_question first and then call again with confirmed: true. Available now:\n' + (lines.actions.join('\n') || '(none)'),
        parameters: {
            type: 'object',
            properties: {
                plugin: {type: 'string', description: 'The plugin name as listed (e.g. "Music") or its id.'},
                action: {type: 'string', description: 'The action name, e.g. set_playing.'},
                args_json: {type: 'string', description: 'The action\'s arguments as a JSON object, e.g. {"on": false}. Omit when it takes none.'},
                confirmed: {type: 'boolean', description: 'True only after the user said yes.'}
            },
            required: ['plugin', 'action']
        }
    };
}

exports.getDeclarations = function() {
    if (!plugins.available()) {
        return [];
    }
    var lines = exports.catalogLines();
    return [readDeclaration(lines), actDeclaration(lines)];
};

exports.handles = function(name) {
    return exports.NAMES.indexOf(name) !== -1;
};

exports.execute = function(session, name, args, callback) {
    args = args || {};
    if (name === LIST) {
        // For Billy Companion, which can't see the catalog itself.
        var lines = exports.catalogLines();
        callback({status: 'ok', available: plugins.available(), sources: lines.sources, actions: lines.actions});
        return true;
    }
    if (name === READ) {
        var category = String(args.category || '').trim();
        var item = String(args.item || '').trim();
        if (!category || !item) {
            callback({status: 'error', summary: 'Give a category and item from the list.'});
            return true;
        }
        if (SKIP_SOURCES.indexOf(sourceKey(category, item)) !== -1) {
            callback({status: 'error', summary: 'Use use_phone_plugin for that (e.g. Find My Phone > ring).'});
            return true;
        }
        if (session && session.handleMessage) {
            session.handleMessage({data: 'fChecking your phone'});
        }
        plugins.read(category, item, null, function(err, envelope) {
            if (err) {
                callback({status: 'error', summary: plugins.explainError(err, sourceKey(category, item)),
                    tell_user: 'Tell the user plainly what went wrong and how to fix it.'});
                return;
            }
            var items = plugins.simplify(envelope, args.instance);
            callback({
                status: 'ok',
                summary: items.length === 0 ? 'Nothing there right now.' : items.length + ' item(s).',
                source: sourceKey(category, item),
                items: items
            });
        });
        return true;
    }
    if (name === ACT) {
        var found = findAction(args.plugin, String(args.action || ''));
        if (!found) {
            callback({status: 'error', summary: 'No installed plugin called "' + args.plugin + '". Use a name from the list.'});
            return true;
        }
        if (!found.action) {
            callback({status: 'error', summary: found.plugin.name + ' has no action "' + args.action + '".'});
            return true;
        }
        if ((found.action.requiresConfirmation || found.action.destructive) && args.confirmed !== true) {
            callback({status: 'needs_confirmation', summary: 'Ask the user to confirm "' + found.plugin.name + ' > ' + found.action.name +
                '" with ask_clarifying_question, then call again with confirmed: true.'});
            return true;
        }
        if (session && session.handleMessage) {
            session.handleMessage({data: 'f' + found.plugin.name});
        }
        var actionArgs = args.args && typeof args.args === 'object' ? args.args : {};
        if (args.args_json) {
            try {
                actionArgs = JSON.parse(String(args.args_json));
            } catch (e) {
                callback({status: 'error', summary: 'args_json is not valid JSON: ' + args.args_json});
                return true;
            }
        }
        plugins.invoke(found.plugin.uuid, found.action.name, actionArgs, function(result) {
            if (result.ok) {
                callback({status: 'ok', summary: result.text || 'Done.'});
            } else {
                callback({status: 'error', summary: plugins.explainError(result, found.plugin.name + ' > ' + found.action.name),
                    tell_user: 'Tell the user plainly that it did not work and why.'});
            }
        });
        return true;
    }
    return false;
};
