/**
 * Read-only calendar for the phone runtime, from calendar links the user
 * pastes into Billy's settings (Google "secret address in iCal format",
 * iCloud public calendar links, Outlook "publish calendar" ICS links).
 * Works on iPhone; no Google sign-in needed.
 */

var config = require('../config');
var ical = require('../lib/ical');

var CACHE_MS = 10 * 60 * 1000;
var cache = {};

exports.links = function() {
    return String(config.getSetting('CALENDAR_LINKS', '') || '')
        .split(/[\s,]+/)
        .map(function(link) {
            return link.trim().replace(/^webcal:\/\//i, 'https://');
        })
        .filter(function(link) {
            return /^https:\/\//i.test(link);
        });
};

exports.getDeclarations = function() {
    return [{
        name: 'get_calendar_events',
        description: "Read the user's calendar (from the calendar links in Billy settings). Use for \"what's on my calendar\", \"when is my next meeting\", \"am I free Friday\". Read-only: Billy can't add or change events in this mode; offer a reminder instead.",
        parameters: {
            type: 'object',
            properties: {
                start: {type: 'string', description: 'Window start, ISO 8601 local time or date. Defaults to now.'},
                end: {type: 'string', description: 'Window end. Defaults to end of the start day, or 60 days ahead when searching.'},
                search: {type: 'string', description: 'Optional words to match in the title or location.'},
                max_results: {type: 'integer', description: 'Default 10.'}
            }
        }
    }];
};

exports.handles = function(name) {
    return name === 'get_calendar_events';
};

exports.execute = function(session, name, args, callback) {
    if (name !== 'get_calendar_events') {
        return false;
    }
    args = args || {};
    var links = exports.links();
    if (links.length === 0) {
        callback({
            status: 'needs_setup',
            summary: "No calendar is connected. In Billy's settings in the Pebble app, paste your calendar's iCal link (Google Calendar: Settings > your calendar > Secret address in iCal format)."
        });
        return true;
    }
    var search = String(args.search || '').trim().toLowerCase();
    var start = parseDate(args.start) || new Date();
    var end = parseDate(args.end);
    if (!end) {
        end = search ? new Date(start.getTime() + 60 * 86400000) : new Date(start.getFullYear(), start.getMonth(), start.getDate() + 1);
    }
    var max = Math.max(1, Math.min(30, parseInt(args.max_results, 10) || 10));
    session.handleMessage({data: 'fChecking your calendar'});
    loadAll(links, function(events, failures) {
        var items = ical.occurrences(events, start.getTime(), end.getTime()).filter(function(o) {
            return !search || (o.title + ' ' + o.location).toLowerCase().indexOf(search) !== -1;
        }).slice(0, max);
        var list = items.map(function(o) {
            return {
                title: o.title,
                start: new Date(o.start).toString(),
                end: new Date(o.end).toString(),
                all_day: o.allDay,
                location: o.location,
                repeating: o.repeating
            };
        });
        callback({
            status: failures.length === links.length ? 'error' : 'ok',
            summary: items.length === 0 ? 'Nothing on the calendar in that window.' : items.length + ' events.',
            events: list,
            unreadable_links: failures.length
        });
    });
    return true;
};

function parseDate(value) {
    if (!value) {
        return null;
    }
    var text = String(value).trim();
    var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
    if (m) {
        return new Date(+m[1], +m[2] - 1, +m[3]);
    }
    var d = new Date(text);
    return isNaN(d.getTime()) ? null : d;
}

function loadAll(links, done) {
    var events = [];
    var failures = [];
    var pending = links.length;
    links.forEach(function(link) {
        load(link, function(err, parsed) {
            if (err) {
                failures.push(link);
            } else {
                events = events.concat(parsed);
            }
            if (--pending === 0) {
                done(events, failures);
            }
        });
    });
}

function load(link, callback) {
    var hit = cache[link];
    if (hit && Date.now() - hit.at < CACHE_MS) {
        callback(null, hit.events);
        return;
    }
    var finished = false;
    var req = new XMLHttpRequest();
    function finish(err, value) {
        if (!finished) {
            finished = true;
            callback(err, value);
        }
    }
    req.open('GET', link, true);
    req.timeout = 20000;
    req.onload = function() {
        if (req.status < 200 || req.status >= 300) {
            finish(new Error('HTTP ' + req.status));
            return;
        }
        try {
            var events = ical.parse(req.responseText);
            cache[link] = {at: Date.now(), events: events};
            finish(null, events);
        } catch (e) {
            finish(e);
        }
    };
    req.onerror = function() {
        finish(new Error('network error'));
    };
    req.ontimeout = function() {
        finish(new Error('timed out'));
    };
    req.send();
}
