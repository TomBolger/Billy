/**
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

// One place that knows every tool the phone runtime offers Gemini.
// Every tool is offered on every turn: hiding tools behind keyword guesses was
// the main reason Billy "forgot" it could set timers, reminders, and so on.

var uiTools = require('./ui_tools');
var watchTools = require('./watch_tools');
var weatherTool = require('./weather_tool');
var osmMapTool = require('./osm_map_tool');
var profileTools = require('./profile_tools');
var imageTool = require('./image_tool');
var calendarTool = require('./calendar_tool');

// Tools that change something. A repeated identical call in the same turn is
// answered from the first result instead of running twice (no double alarms).
var MUTATING = [
    'set_alarm', 'delete_alarm', 'set_timer', 'delete_timer', 'set_reminder',
    'delete_reminder', 'update_settings', 'remember_billy_user_fact', 'forget_billy_user_fact', 'show_image'
];

// Legacy modules take (session, {name, arguments}, callback).
function legacy(module) {
    var declarations = module.getDeclarations().map(function(d) {
        return {name: d.name, description: d.description, parameters: d.parameters};
    });
    var names = declarations.map(function(d) {
        return d.name;
    });
    return {
        getDeclarations: function() {
            return declarations;
        },
        handles: function(name) {
            return names.indexOf(name) !== -1;
        },
        execute: function(session, name, args, callback) {
            return module.execute(session, {name: name, arguments: args}, callback);
        }
    };
}

var MODULES = [uiTools, watchTools, imageTool, calendarTool, legacy(weatherTool), legacy(osmMapTool), legacy(profileTools)];

exports.declarations = function() {
    var all = [];
    MODULES.forEach(function(module) {
        all = all.concat(module.getDeclarations());
    });
    return all;
};

exports.isMutating = function(name) {
    return MUTATING.indexOf(name) !== -1;
};

exports.execute = function(session, name, args, callback) {
    for (var i = 0; i < MODULES.length; i++) {
        if (MODULES[i].handles(name)) {
            var finished = false;
            var handled = MODULES[i].execute(session, name, args || {}, function(result) {
                if (finished) {
                    return;
                }
                finished = true;
                callback(result || {status: 'error', summary: 'Tool returned nothing.'});
            });
            if (handled !== false) {
                return;
            }
        }
    }
    callback({status: 'error', summary: 'Unknown tool ' + name + '. Use only the tools you were given.'});
};
