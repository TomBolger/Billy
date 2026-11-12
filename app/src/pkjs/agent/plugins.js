/**
 * Pebble plugins (preview, Pebble app 1.16+): data and actions other apps and
 * the Pebble app itself offer on the phone, such as the phone's calendar,
 * notifications, music, battery, find-my-phone, opening watch apps, and
 * third-party plugins like Todoist or Hue.
 *
 * Everything here is inert unless the user turned plugins on in the Pebble
 * app; then enumeratePlugins() returns what's installed.
 * See https://developer.repebble.com/guides/plugins/preview/
 */

var READ_TIMEOUT_MS = 12000;   // a first read may wait for the user to allow a permission
var ACTION_TIMEOUT_MS = 15000;
var MAX_INSTANCES = 15;
var MAX_TEXT = 160;

var BUILT_IN = {
    phone: '00000000-0000-0000-0001-000000000001',
    weather: '00000000-0000-0000-0001-000000000002',
    music: '00000000-0000-0000-0001-000000000003',
    calendar: '00000000-0000-0000-0001-000000000004',
    notifications: '00000000-0000-0000-0001-000000000005',
    watchSettings: '00000000-0000-0000-0001-000000000006',
    watchState: '00000000-0000-0000-0001-000000000007',
    findMyPhone: '00000000-0000-0000-0001-000000000008',
    preferences: '00000000-0000-0000-0001-000000000009'
};
exports.BUILT_IN = BUILT_IN;

var units = null;

function supported() {
    return typeof Pebble !== 'undefined' && typeof Pebble.enumeratePlugins === 'function' &&
        typeof Pebble.subscribeToSource === 'function';
}

/** Installed plugins, [] when plugins are off or unsupported. */
exports.catalog = function() {
    if (!supported()) {
        return [];
    }
    try {
        var list = Pebble.enumeratePlugins();
        return Array.isArray(list) ? list : [];
    } catch (e) {
        console.log('enumeratePlugins failed: ' + e);
        return [];
    }
};

exports.available = function() {
    return exports.catalog().length > 0;
};

/** True when some installed plugin serves category/item. */
exports.hasSource = function(category, item) {
    return exports.catalog().some(function(plugin) {
        return (plugin.sources || []).some(function(source) {
            return source.category === category && (source.items || []).indexOf(item) !== -1;
        });
    });
};

function explainError(err, what) {
    var code = (err && err.code) || 'UNKNOWN';
    var message = (err && err.message) || '';
    if (code === 'PERMISSION_DENIED') {
        return 'Billy isn\'t allowed to read ' + what + ' yet. In the Pebble app, allow it on Billy\'s page (Apps, Billy, Permissions), then ask again.' +
            (message ? ' (' + message + ')' : '');
    }
    if (code === 'PLUGIN_UNAVAILABLE') {
        return 'Nothing on the phone provides ' + what + ' right now.' + (message ? ' (' + message + ')' : '');
    }
    if (code === 'AUTH_REQUIRED') {
        return 'That plugin needs you to sign in on its settings page in the Pebble app.' + (message ? ' (' + message + ')' : '');
    }
    if (code === 'TIMEOUT') {
        return 'The phone took too long to answer for ' + what + '.';
    }
    return code + (message ? ': ' + message : '');
}
exports.explainError = explainError;

/**
 * Reads a source once: callback(null, envelope) or callback({code, message}).
 */
exports.read = function(category, item, properties, callback) {
    if (!supported()) {
        callback({code: 'PLUGIN_UNAVAILABLE', message: 'Plugins are off. Turn them on in the Pebble app: Settings, Plugins.'});
        return;
    }
    var finished = false;
    var subscription = null;
    function finish(err, data) {
        if (finished) {
            return;
        }
        finished = true;
        clearTimeout(timer);
        if (subscription) {
            try { subscription.unsubscribe(); } catch (e) { /* already gone */ }
        }
        callback(err, data);
    }
    var timer = setTimeout(function() {
        finish({code: 'TIMEOUT', message: 'No answer from ' + category + '/' + item + '.'});
    }, READ_TIMEOUT_MS);
    try {
        var request = {
            category: category,
            item: item,
            onData: function(envelope) { finish(null, envelope || {instances: []}); },
            onError: function(err) { finish(err || {code: 'UNKNOWN'}); }
        };
        if (properties && properties.length) {
            request.properties = properties;
        }
        subscription = Pebble.subscribeToSource(request);
        if (finished && subscription) {
            try { subscription.unsubscribe(); } catch (e) { /* ignore */ }
        }
    } catch (e) {
        finish({code: 'INVALID_REQUEST', message: String(e && e.message || e)});
    }
};

/** Runs an action: callback({ok, text, code, message}). */
exports.invoke = function(pluginUuid, action, args, callback) {
    if (!supported() || typeof Pebble.invokeAction !== 'function') {
        callback({ok: false, code: 'PLUGIN_UNAVAILABLE', message: 'Plugins are off. Turn them on in the Pebble app: Settings, Plugins.'});
        return;
    }
    var finished = false;
    function finish(result) {
        if (!finished) {
            finished = true;
            callback(result || {ok: false, code: 'UNKNOWN'});
        }
    }
    try {
        Pebble.invokeAction({plugin: pluginUuid, action: action, args: args || {}, timeoutMs: ACTION_TIMEOUT_MS})
            .then(finish, function(err) {
                finish({ok: false, code: (err && err.code) || 'UNKNOWN', message: String(err && err.message || err)});
            });
    } catch (e) {
        finish({ok: false, code: 'INVALID_REQUEST', message: String(e && e.message || e)});
    }
    setTimeout(function() {
        finish({ok: false, code: 'TIMEOUT', message: 'The plugin did not answer.'});
    }, ACTION_TIMEOUT_MS + 3000);
};

function clip(text) {
    text = String(text);
    return text.length > MAX_TEXT ? text.substring(0, MAX_TEXT - 1) + '…' : text;
}

/** One readable value per property, from whichever shape is there. */
function plainValue(shapes) {
    if (!shapes || typeof shapes !== 'object') {
        return undefined;
    }
    if (shapes.timestamp && typeof shapes.timestamp.value === 'number') {
        return new Date(shapes.timestamp.value * 1000).toString();
    }
    if (shapes.longText && shapes.longText.text !== undefined) {
        return clip(shapes.longText.text);
    }
    if (shapes.shortText && shapes.shortText.text !== undefined) {
        return clip(shapes.shortText.text);
    }
    if (shapes.numericValue && typeof shapes.numericValue.value === 'number') {
        return shapes.numericValue.unit ? shapes.numericValue.value + ' ' + shapes.numericValue.unit : shapes.numericValue.value;
    }
    if (shapes.boolean && typeof shapes.boolean.value === 'boolean') {
        return shapes.boolean.value;
    }
    if (shapes.series) {
        var s = shapes.series;
        var values = (s.shortText && s.shortText.values) || (s.numericValue && s.numericValue.values) || [];
        return {start: s.start ? new Date(s.start * 1000).toString() : undefined, step_seconds: s.stepSec, values: values.slice(0, 24)};
    }
    return undefined; // icons and images aren't useful to the model
}

/** Envelope -> [{id, ...properties}] with plain values, capped in size. */
exports.simplify = function(envelope, instanceId) {
    var instances = (envelope && envelope.instances) || [];
    if (instanceId) {
        instances = instances.filter(function(i) { return String(i.instanceId) === String(instanceId); });
    }
    return instances.slice(0, MAX_INSTANCES).map(function(instance) {
        var out = {id: instance.instanceId};
        var props = instance.properties || {};
        Object.keys(props).forEach(function(name) {
            var value = plainValue(props[name]);
            if (value !== undefined) {
                out[name] = value;
            }
        });
        return out;
    });
};

/**
 * Keeps the user's units from the Pebble app (temperature, distance, clock...)
 * so answers match what the watch shows.
 */
exports.init = function() {
    if (!exports.hasSource('preferences', 'units')) {
        return;
    }
    try {
        Pebble.subscribeToSource({
            category: 'preferences',
            item: 'units',
            onData: function(envelope) {
                var first = exports.simplify(envelope)[0];
                units = first || null;
            },
            onError: function(err) { console.log('Units preference unavailable: ' + (err && err.code)); }
        });
    } catch (e) {
        console.log('Units subscription failed: ' + e);
    }
};

/** e.g. "temperature °F, distance mi, 12h clock", or '' */
exports.unitsSentence = function() {
    if (!units) {
        return '';
    }
    var parts = [];
    ['temperature', 'distance', 'wind_speed', 'pressure', 'precipitation'].forEach(function(key) {
        if (units[key]) { parts.push(key.replace('_', ' ') + ' ' + units[key]); }
    });
    if (units.clock) { parts.push(units.clock + ' clock'); }
    return parts.join(', ');
};
