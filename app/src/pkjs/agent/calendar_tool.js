/**
 * Read-only calendar for the phone runtime, from calendar links the user
 * pastes into Billy's settings (Google "secret address in iCal format",
 * iCloud public calendar links, Outlook "publish calendar" ICS links).
 * Works on iPhone; no Google sign-in needed.
 */

var config = require('../config');
var ical = require('../lib/ical');
var plugins = require('./plugins');

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
        description: "Read the user's calendar: the phone's own calendar (through Pebble plugins, next 10 events within a week) and any calendar links in Billy settings. Use for \"what's on my calendar\", \"when is my next meeting\", \"am I free Friday\". Read-only: Billy can't add or change events in this mode; offer a reminder instead.",
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
    var phoneCalendar = plugins.hasSource('calendar', 'event');
    if (links.length === 0 && !phoneCalendar) {
        callback({
            status: 'needs_setup',
            summary: "No calendar is connected. In Billy's settings in the Pebble app, paste your calendar's iCal link (Google Calendar: Settings > your calendar > Secret address in iCal format). With Pebble plugins turned on, Billy can also read the phone's own calendar."
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
    var pending = 2;
    var linkItems = [];
    var linkFailures = [];
    var phoneItems = [];
    var phoneError = null;
    function matches(o) {
        return o.end >= start.getTime() && o.start < end.getTime() &&
            (!search || (o.title + ' ' + (o.location || '')).toLowerCase().indexOf(search) !== -1);
    }
    function finished() {
        if (--pending > 0) {
            return;
        }
        // The same event can come from both: keep one.
        var seen = {};
        var items = phoneItems.concat(linkItems).filter(function(o) {
            var key = o.title.toLowerCase() + '|' + Math.round(o.start / 60000);
            if (seen[key]) {
                return false;
            }
            seen[key] = true;
            return true;
        }).sort(function(a, b) {
            return a.start - b.start;
        }).slice(0, max);
        var warnings = [];
        if (linkFailures.length > 0) {
            warnings.push(linkFailures.length + ' of ' + links.length + ' calendar links could not be read');
        }
        if (phoneError) {
            warnings.push("the phone's calendar could not be read (" + phoneError + ')');
        }
        if (phoneCalendar && !phoneError && end.getTime() - Date.now() > 7 * 86400000) {
            warnings.push("the phone's calendar only shows the next 10 events within a week");
        }
        var nothingRead = (links.length === 0 || linkFailures.length === links.length) && (!phoneCalendar || phoneError);
        callback({
            status: nothingRead ? 'error' : 'ok',
            summary: (items.length === 0 ? 'Nothing on the calendar in that window.' : items.length + ' events.') +
                (warnings.length > 0 ? ' WARNING: ' + warnings.join('; ') + ', so this list may be incomplete.' : ''),
            events: items.map(function(o) {
                return {
                    title: o.title,
                    start: new Date(o.start).toString(),
                    end: new Date(o.end).toString(),
                    all_day: o.allDay,
                    location: o.location,
                    calendar: o.calendar,
                    repeating: o.repeating
                };
            }),
            tell_user: warnings.length > 0 ? 'Say plainly that ' + warnings.join(' and ') + '.' : undefined
        });
    }
    if (links.length > 0) {
        loadAll(links, function(events, failures) {
            linkFailures = failures;
            linkItems = ical.occurrences(events, start.getTime(), end.getTime()).filter(matches);
            finished();
        });
    } else {
        finished();
    }
    if (phoneCalendar) {
        plugins.read('calendar', 'event', null, function(err, envelope) {
            if (err) {
                phoneError = plugins.explainError(err, "the phone's calendar");
            } else {
                phoneItems = ((envelope && envelope.instances) || []).map(function(instance) {
                    var p = instance.properties || {};
                    var text = function(prop) {
                        return p[prop] ? ((p[prop].longText && p[prop].longText.text) || (p[prop].shortText && p[prop].shortText.text) || '') : '';
                    };
                    var time = function(prop) {
                        return p[prop] && p[prop].timestamp ? p[prop].timestamp.value * 1000 : NaN;
                    };
                    return {
                        title: text('name'),
                        start: time('starts_at'),
                        end: isNaN(time('ends_at')) ? time('starts_at') : time('ends_at'),
                        allDay: !!(p.all_day && p.all_day.boolean && p.all_day.boolean.value),
                        location: text('location'),
                        calendar: text('calendar'),
                        repeating: false
                    };
                }).filter(function(o) {
                    return !isNaN(o.start) && matches(o);
                });
            }
            finished();
        });
    } else {
        finished();
    }
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
