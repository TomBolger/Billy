/**
 * Small iCalendar (.ics) reader for calendar links (Google Calendar "secret
 * address in iCal format", iCloud public calendars, Outlook published
 * calendars). Expands common repeat rules into real occurrences.
 */

var DAY_CODES = ['SU', 'MO', 'TU', 'WE', 'TH', 'FR', 'SA'];
var MAX_STEPS = 3000;

function unfold(text) {
    return String(text || '').replace(/\r\n/g, '\n').replace(/\n[ \t]/g, '').split('\n');
}

function unescapeText(value) {
    return String(value || '').replace(/\\n/gi, '\n').replace(/\\([,;\\])/g, '$1');
}

function parseLine(line) {
    var colon = -1;
    var inQuotes = false;
    for (var i = 0; i < line.length; i++) {
        var ch = line[i];
        if (ch === '"') {
            inQuotes = !inQuotes;
        } else if (ch === ':' && !inQuotes) {
            colon = i;
            break;
        }
    }
    if (colon < 0) {
        return null;
    }
    var head = line.substring(0, colon).split(';');
    var params = {};
    for (var j = 1; j < head.length; j++) {
        var eq = head[j].indexOf('=');
        if (eq > 0) {
            params[head[j].substring(0, eq).toUpperCase()] = head[j].substring(eq + 1).replace(/"/g, '');
        }
    }
    return {name: head[0].toUpperCase(), params: params, value: line.substring(colon + 1)};
}

// Offset (ms) of a named time zone at a given UTC instant, via Intl.
function zoneOffsetMs(timeZone, utcMs) {
    try {
        var parts = new Intl.DateTimeFormat('en-US', {
            timeZone: timeZone,
            hourCycle: 'h23',
            year: 'numeric', month: '2-digit', day: '2-digit',
            hour: '2-digit', minute: '2-digit', second: '2-digit'
        }).formatToParts(new Date(utcMs));
        var get = function(type) {
            for (var i = 0; i < parts.length; i++) {
                if (parts[i].type === type) {
                    return parseInt(parts[i].value, 10);
                }
            }
            return 0;
        };
        var asUtc = Date.UTC(get('year'), get('month') - 1, get('day'), get('hour') % 24, get('minute'), get('second'));
        return asUtc - utcMs;
    } catch (e) {
        return null;
    }
}

/** Returns {ms, allDay} or null. Floating and unknown-zone times are phone-local. */
function parseTime(prop) {
    if (!prop) {
        return null;
    }
    var v = prop.value.trim();
    var m = /^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})(Z)?)?$/.exec(v);
    if (!m) {
        return null;
    }
    var y = +m[1], mo = +m[2] - 1, d = +m[3];
    if (!m[4]) {
        return {ms: new Date(y, mo, d).getTime(), allDay: true};
    }
    var h = +m[4], mi = +m[5], s = +m[6];
    if (m[7]) {
        return {ms: Date.UTC(y, mo, d, h, mi, s), allDay: false};
    }
    var tzid = prop.params.TZID;
    if (tzid) {
        var guess = Date.UTC(y, mo, d, h, mi, s);
        var offset = zoneOffsetMs(tzid, guess);
        if (offset !== null) {
            var adjusted = guess - offset;
            var offset2 = zoneOffsetMs(tzid, adjusted);
            return {ms: offset2 !== null ? guess - offset2 : adjusted, allDay: false};
        }
    }
    return {ms: new Date(y, mo, d, h, mi, s).getTime(), allDay: false};
}

function parseDuration(value) {
    var m = /^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(String(value || '').trim());
    if (!m) {
        return null;
    }
    var ms = ((+m[2] || 0) * 7 * 86400 + (+m[3] || 0) * 86400 + (+m[4] || 0) * 3600 + (+m[5] || 0) * 60 + (+m[6] || 0)) * 1000;
    return m[1] === '-' ? -ms : ms;
}

function parseRule(value) {
    var rule = {};
    String(value || '').split(';').forEach(function(part) {
        var eq = part.indexOf('=');
        if (eq > 0) {
            rule[part.substring(0, eq).toUpperCase()] = part.substring(eq + 1);
        }
    });
    return rule;
}

/** Parses .ics text into a list of event objects (not yet expanded). */
exports.parse = function(text) {
    var events = [];
    var current = null;
    unfold(text).forEach(function(line) {
        if (line === 'BEGIN:VEVENT') {
            current = {exdates: []};
            return;
        }
        if (line === 'END:VEVENT') {
            if (current && current.start) {
                events.push(current);
            }
            current = null;
            return;
        }
        if (!current) {
            return;
        }
        var prop = parseLine(line);
        if (!prop) {
            return;
        }
        switch (prop.name) {
        case 'UID': current.uid = prop.value; break;
        case 'SUMMARY': current.title = unescapeText(prop.value); break;
        case 'LOCATION': current.location = unescapeText(prop.value); break;
        case 'DESCRIPTION': current.description = unescapeText(prop.value).substring(0, 300); break;
        case 'STATUS': current.cancelled = /CANCELLED/i.test(prop.value); break;
        case 'DTSTART': current.start = parseTime(prop); break;
        case 'DTEND': current.end = parseTime(prop); break;
        case 'DURATION': current.duration = parseDuration(prop.value); break;
        case 'RRULE': current.rule = parseRule(prop.value); break;
        case 'RECURRENCE-ID': current.recurrenceId = parseTime(prop); break;
        case 'EXDATE':
            prop.value.split(',').forEach(function(v) {
                var t = parseTime({value: v, params: prop.params});
                if (t) {
                    current.exdates.push(t.ms);
                }
            });
            break;
        }
    });
    return events;
};

function addInterval(date, freq, n) {
    var d = new Date(date.getTime());
    if (freq === 'DAILY') d.setDate(d.getDate() + n);
    else if (freq === 'WEEKLY') d.setDate(d.getDate() + 7 * n);
    // Months/years step from the 1st so short months don't overflow.
    else if (freq === 'MONTHLY') d = new Date(d.getFullYear(), d.getMonth() + n, 1);
    else if (freq === 'YEARLY') d = new Date(d.getFullYear() + n, d.getMonth(), 1);
    return d;
}

// Candidate start times within one period beginning at periodStart.
function expandPeriod(periodStart, base, rule) {
    var freq = rule.FREQ;
    var byDay = rule.BYDAY ? rule.BYDAY.split(',') : null;
    var byMonthDay = rule.BYMONTHDAY ? rule.BYMONTHDAY.split(',').map(Number) : null;
    var withTime = function(d) {
        return new Date(d.getFullYear(), d.getMonth(), d.getDate(), base.getHours(), base.getMinutes(), base.getSeconds());
    };
    if (freq === 'WEEKLY' && byDay) {
        var weekStart = new Date(periodStart.getFullYear(), periodStart.getMonth(), periodStart.getDate() - ((periodStart.getDay() + 6) % 7));
        return byDay.map(function(code) {
            var dow = DAY_CODES.indexOf(code.slice(-2));
            var d = new Date(weekStart.getFullYear(), weekStart.getMonth(), weekStart.getDate() + ((dow + 6) % 7));
            return withTime(d);
        });
    }
    if ((freq === 'MONTHLY' || freq === 'YEARLY') && (byDay || byMonthDay)) {
        var year = periodStart.getFullYear();
        var month = periodStart.getMonth();
        if (freq === 'YEARLY' && rule.BYMONTH) {
            month = parseInt(rule.BYMONTH, 10) - 1;
        }
        var daysInMonth = new Date(year, month + 1, 0).getDate();
        var out = [];
        (byMonthDay || []).forEach(function(md) {
            var day = md > 0 ? md : daysInMonth + md + 1;
            if (day >= 1 && day <= daysInMonth) {
                out.push(withTime(new Date(year, month, day)));
            }
        });
        (byDay || []).forEach(function(code) {
            var m = /^([+-]?\d+)?([A-Z]{2})$/.exec(code);
            if (!m) {
                return;
            }
            var dow = DAY_CODES.indexOf(m[2]);
            var matches = [];
            for (var day = 1; day <= daysInMonth; day++) {
                if (new Date(year, month, day).getDay() === dow) {
                    matches.push(day);
                }
            }
            var nth = m[1] ? parseInt(m[1], 10) : 0;
            var picks = nth === 0 ? matches : [nth > 0 ? matches[nth - 1] : matches[matches.length + nth]];
            picks.forEach(function(day) {
                if (day) {
                    out.push(withTime(new Date(year, month, day)));
                }
            });
        });
        return out;
    }
    return [new Date(periodStart.getTime())];
}

/** Occurrences overlapping [fromMs, toMs), sorted by start. */
exports.occurrences = function(events, fromMs, toMs) {
    var overrides = {};
    events.forEach(function(e) {
        if (e.recurrenceId && e.uid) {
            overrides[e.uid + '@' + e.recurrenceId.ms] = true;
        }
    });
    var out = [];
    events.forEach(function(e) {
        if (e.cancelled) {
            return;
        }
        var length = e.end ? e.end.ms - e.start.ms : (e.duration !== null && e.duration !== undefined ? e.duration : (e.start.allDay ? 86400000 : 3600000));
        var push = function(startMs) {
            if (startMs < toMs && startMs + Math.max(length, 1) > fromMs) {
                out.push({
                    title: e.title || '(busy)',
                    start: startMs,
                    end: startMs + length,
                    allDay: e.start.allDay,
                    location: e.location || '',
                    description: e.description || '',
                    repeating: !!e.rule
                });
            }
        };
        if (!e.rule || e.recurrenceId) {
            push(e.start.ms);
            return;
        }
        var rule = {};
        Object.keys(e.rule).forEach(function(k) {
            rule[k] = e.rule[k];
        });
        var startDate = new Date(e.start.ms);
        if (rule.FREQ === 'MONTHLY' && !rule.BYDAY && !rule.BYMONTHDAY) {
            rule.BYMONTHDAY = String(startDate.getDate());
        }
        if (rule.FREQ === 'YEARLY' && !rule.BYDAY && !rule.BYMONTHDAY) {
            rule.BYMONTH = rule.BYMONTH || String(startDate.getMonth() + 1);
            rule.BYMONTHDAY = String(startDate.getDate());
        }
        var interval = Math.max(1, parseInt(rule.INTERVAL || '1', 10));
        var count = rule.COUNT ? parseInt(rule.COUNT, 10) : Infinity;
        var until = rule.UNTIL ? (parseTime({value: rule.UNTIL, params: {}}) || {ms: Infinity}).ms : Infinity;
        var base = new Date(e.start.ms);
        var emitted = 0;
        for (var step = 0; step < MAX_STEPS && emitted < count; step++) {
            var periodStart = addInterval(base, rule.FREQ, step * interval);
            if (periodStart.getTime() >= toMs || periodStart.getTime() > until) {
                break;
            }
            var candidates = expandPeriod(periodStart, base, rule).sort(function(a, b) {
                return a - b;
            });
            for (var c = 0; c < candidates.length && emitted < count; c++) {
                var t = candidates[c].getTime();
                if (t < e.start.ms || t > until) {
                    continue;
                }
                emitted++;
                if (e.exdates.indexOf(t) !== -1 || (e.uid && overrides[e.uid + '@' + t])) {
                    continue;
                }
                push(t);
            }
        }
    });
    return out.sort(function(a, b) {
        return a.start - b.start;
    });
};
