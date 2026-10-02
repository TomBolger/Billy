/**
 * show_image for the phone runtime (works on iPhone, no companion needed):
 * finds a picture of a subject on Wikipedia/Wikimedia, decodes the JPEG in
 * JavaScript, converts it to a Pebble bitmap, and shows it as a watch card.
 */

var decodeJpeg = require('../lib/jpeg_decoder');
var pebbleImage = require('../lib/pebble_image');
var imageManager = require('../lib/image_transfer').sharedManager;

var USER_AGENT_NOTE = 'BillyAssistant (Pebble watch app)';

exports.getDeclarations = function() {
    return [{
        name: 'show_image',
        description: 'Show a picture of something on the watch (from Wikipedia/Wikimedia). Use it proactively, alongside your text answer, whenever the user asks about something with a recognizable look: landmarks, places, animals, plants, famous people, artworks, buildings, vehicles, dishes. ' +
            'Skip it when the answer depends on fine detail the tiny low-color screen cannot show (charts, diagrams, text; use show_openstreetmap_map for maps).',
        parameters: {
            type: 'object',
            properties: {
                query: {type: 'string', description: 'The subject as a Wikipedia article title would name it, e.g. "Eiffel Tower", "Golden retriever".'}
            },
            required: ['query']
        }
    }];
};

exports.handles = function(name) {
    return name === 'show_image';
};

exports.execute = function(session, name, args, callback) {
    if (name !== 'show_image') {
        return false;
    }
    var query = String((args && args.query) || '').trim();
    if (!query) {
        callback({status: 'error', summary: 'show_image needs a subject.'});
        return true;
    }
    session.handleMessage({data: 'fFinding a picture'});
    findImageUrl(query, function(err, url) {
        if (err || !url) {
            callback({status: 'error', summary: 'No picture found for ' + query + '.'});
            return;
        }
        fetchBytes(url, function(fetchErr, bytes) {
            if (fetchErr) {
                callback({status: 'error', summary: 'Could not download the picture.'});
                return;
            }
            var encoded;
            try {
                var decoded = decodeJpeg(bytes, {useTArray: true, formatAsRGBA: true, maxMemoryUsageInMB: 64});
                var size = watchImageSize();
                encoded = pebbleImage.encode(decoded.data, decoded.width, decoded.height, size.width, size.height, size.color);
            } catch (e) {
                callback({status: 'error', summary: 'Could not convert the picture for the watch.'});
                return;
            }
            var imageId = imageManager.sendImage(encoded.width, encoded.height, encoded.bytes);
            setTimeout(function() {
                session.enqueue({
                    MAP_WIDGET: 1,
                    MAP_WIDGET_IMAGE_ID: imageId,
                    MAP_WIDGET_USER_LOCATION: 0
                });
                callback({
                    status: 'ok',
                    summary: 'Showing a picture of ' + query + '.',
                    watch_card: 'The picture is on the watch; answer in text without describing the picture at length.'
                });
            }, 120);
        });
    });
    return true;
};

function watchImageSize() {
    var platform = 'emery';
    try {
        if (Pebble.getActiveWatchInfo) {
            platform = Pebble.getActiveWatchInfo().platform || platform;
        }
    } catch (e) {
        // keep default
    }
    switch (platform) {
    case 'aplite':
    case 'diorite':
        return {width: 144, height: 110, color: false};
    case 'chalk':
        return {width: 150, height: 120, color: true};
    case 'basalt':
        return {width: 144, height: 110, color: true};
    default:
        return {width: 198, height: 150, color: true};
    }
}

// Wikipedia lead image (usually the canonical picture), then Commons search.
function findImageUrl(query, callback) {
    var wiki = 'https://en.wikipedia.org/w/api.php?action=query&format=json&prop=pageimages&piprop=thumbnail' +
        '&pithumbsize=400&generator=search&gsrlimit=3&gsrsearch=' + encodeURIComponent(query) + '&origin=*';
    fetchJson(wiki, function(err, json) {
        var url = !err && firstJpeg(json, function(page) {
            return page.thumbnail && page.thumbnail.source;
        }, function(page) {
            return page.index;
        });
        if (url) {
            callback(null, url);
            return;
        }
        var commons = 'https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6' +
            '&gsrlimit=8&prop=imageinfo&iiprop=url%7Cmime&iiurlwidth=400&origin=*&gsrsearch=' + encodeURIComponent(query);
        fetchJson(commons, function(err2, json2) {
            callback(err2, !err2 && firstJpeg(json2, function(page) {
                var info = page.imageinfo && page.imageinfo[0];
                return info && info.mime === 'image/jpeg' && (info.thumburl || info.url);
            }, function(page) {
                return page.index;
            }));
        });
    });
}

function firstJpeg(json, urlOf, orderOf) {
    var pages = json && json.query && json.query.pages;
    if (!pages) {
        return null;
    }
    var list = Object.keys(pages).map(function(k) {
        return pages[k];
    }).sort(function(a, b) {
        return (orderOf(a) || 0) - (orderOf(b) || 0);
    });
    for (var i = 0; i < list.length; i++) {
        var url = urlOf(list[i]);
        if (url && /\.jpe?g($|\?)/i.test(url)) {
            return url;
        }
    }
    return null;
}

function fetchJson(url, callback) {
    request(url, 'text', function(err, req) {
        if (err) {
            callback(err);
            return;
        }
        try {
            callback(null, JSON.parse(req.responseText));
        } catch (e) {
            callback(e);
        }
    });
}

function fetchBytes(url, callback) {
    request(url, 'arraybuffer', function(err, req) {
        if (err) {
            callback(err);
            return;
        }
        if (req.response && typeof req.response !== 'string') {
            callback(null, new Uint8Array(req.response));
            return;
        }
        var text = req.responseText || '';
        var bytes = new Uint8Array(text.length);
        for (var i = 0; i < text.length; i++) {
            bytes[i] = text.charCodeAt(i) & 0xff;
        }
        callback(null, bytes);
    });
}

function request(url, type, callback) {
    var finished = false;
    var req = new XMLHttpRequest();
    function finish(err) {
        if (!finished) {
            finished = true;
            callback(err, req);
        }
    }
    req.open('GET', url, true);
    req.timeout = 15000;
    if (type === 'arraybuffer') {
        try {
            req.responseType = 'arraybuffer';
        } catch (e) {
            // fall back to binary string below
        }
        if (req.overrideMimeType) {
            req.overrideMimeType('text/plain; charset=x-user-defined');
        }
    }
    try {
        req.setRequestHeader('Api-User-Agent', USER_AGENT_NOTE);
    } catch (e) {
        // not allowed on some hosts
    }
    req.onload = function() {
        finish(req.status >= 200 && req.status < 300 ? null : new Error('HTTP ' + req.status));
    };
    req.onerror = function() {
        finish(new Error('network error'));
    };
    req.ontimeout = function() {
        finish(new Error('timed out'));
    };
    req.send();
}
