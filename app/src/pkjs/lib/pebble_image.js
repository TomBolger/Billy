/**
 * Turns decoded RGBA pixels into a Pebble bitmap (.pbi) the watch can show
 * directly, without a companion app. Works on iPhone and Android.
 *
 * Color watches get a 16-color palettized image chosen from the photo's own
 * colors, with Floyd-Steinberg dithering so photos stay recognizable on
 * Pebble's 64-color screen. Black-and-white watches get 4 grays.
 */

// Pebble color channel levels (2 bits each).
var LEVELS = [0, 85, 170, 255];

function toLevel(value) {
    return value < 43 ? 0 : value < 128 ? 1 : value < 213 ? 2 : 3;
}

// Resize to fit inside maxW x maxH, keeping aspect ratio (box filter).
function resize(rgba, width, height, maxW, maxH) {
    var scale = Math.min(maxW / width, maxH / height, 1);
    var w = Math.max(1, Math.round(width * scale));
    var h = Math.max(1, Math.round(height * scale));
    var out = new Float32Array(w * h * 3);
    for (var y = 0; y < h; y++) {
        var sy0 = Math.floor(y * height / h);
        var sy1 = Math.max(sy0 + 1, Math.floor((y + 1) * height / h));
        for (var x = 0; x < w; x++) {
            var sx0 = Math.floor(x * width / w);
            var sx1 = Math.max(sx0 + 1, Math.floor((x + 1) * width / w));
            var r = 0, g = 0, b = 0, n = 0;
            for (var sy = sy0; sy < sy1; sy++) {
                for (var sx = sx0; sx < sx1; sx++) {
                    var i = (sy * width + sx) * 4;
                    var a = rgba[i + 3] / 255;
                    // Transparent pixels become white.
                    r += rgba[i] * a + 255 * (1 - a);
                    g += rgba[i + 1] * a + 255 * (1 - a);
                    b += rgba[i + 2] * a + 255 * (1 - a);
                    n++;
                }
            }
            var o = (y * w + x) * 3;
            out[o] = r / n;
            out[o + 1] = g / n;
            out[o + 2] = b / n;
        }
    }
    return {width: w, height: h, rgb: out};
}

// Most common Pebble colors in the image (as [r,g,b] in 0..255).
function choosePalette(img, size) {
    var counts = {};
    for (var i = 0; i < img.rgb.length; i += 3) {
        var key = (toLevel(img.rgb[i]) << 4) | (toLevel(img.rgb[i + 1]) << 2) | toLevel(img.rgb[i + 2]);
        counts[key] = (counts[key] || 0) + 1;
    }
    var keys = Object.keys(counts).sort(function(a, b) {
        return counts[b] - counts[a];
    });
    // Always keep black and white for contrast.
    var chosen = [0, 63];
    for (var k = 0; k < keys.length && chosen.length < size; k++) {
        var value = parseInt(keys[k], 10);
        if (chosen.indexOf(value) === -1) {
            chosen.push(value);
        }
    }
    while (chosen.length < size) {
        chosen.push(0);
    }
    return chosen.map(function(c) {
        return [LEVELS[(c >> 4) & 3], LEVELS[(c >> 2) & 3], LEVELS[c & 3]];
    });
}

function nearest(palette, r, g, b) {
    var best = 0;
    var bestDist = Infinity;
    for (var i = 0; i < palette.length; i++) {
        var p = palette[i];
        // Weighted for human perception.
        var d = 2 * (p[0] - r) * (p[0] - r) + 4 * (p[1] - g) * (p[1] - g) + 3 * (p[2] - b) * (p[2] - b);
        if (d < bestDist) {
            bestDist = d;
            best = i;
        }
    }
    return best;
}

// Floyd-Steinberg dithering into palette indexes.
function dither(img, palette) {
    var w = img.width, h = img.height, rgb = img.rgb;
    var indexes = new Uint8Array(w * h);
    function spread(x, y, er, eg, eb, f) {
        if (x < 0 || x >= w || y >= h) {
            return;
        }
        var j = (y * w + x) * 3;
        rgb[j] += er * f;
        rgb[j + 1] += eg * f;
        rgb[j + 2] += eb * f;
    }
    for (var y = 0; y < h; y++) {
        for (var x = 0; x < w; x++) {
            var i = (y * w + x) * 3;
            var r = Math.max(0, Math.min(255, rgb[i]));
            var g = Math.max(0, Math.min(255, rgb[i + 1]));
            var b = Math.max(0, Math.min(255, rgb[i + 2]));
            var idx = nearest(palette, r, g, b);
            indexes[y * w + x] = idx;
            var p = palette[idx];
            var er = r - p[0], eg = g - p[1], eb = b - p[2];
            spread(x + 1, y, er, eg, eb, 7 / 16);
            spread(x - 1, y + 1, er, eg, eb, 3 / 16);
            spread(x, y + 1, er, eg, eb, 5 / 16);
            spread(x + 1, y + 1, er, eg, eb, 1 / 16);
        }
    }
    return indexes;
}

function writeU16(bytes, offset, value) {
    bytes[offset] = value & 0xff;
    bytes[offset + 1] = (value >> 8) & 0xff;
}

/**
 * rgba: Uint8Array of width*height*4. Returns {width, height, bytes} where
 * bytes is a plain array (what the AppMessage image transfer expects).
 */
exports.encode = function(rgba, width, height, maxW, maxH, color) {
    var img = resize(rgba, width, height, maxW, maxH);
    var bits = color ? 4 : 2;
    var palette = color ? choosePalette(img, 16) : [[0, 0, 0], [85, 85, 85], [170, 170, 170], [255, 255, 255]];
    if (!color) {
        // Grayscale first so dithering works on brightness.
        for (var g = 0; g < img.rgb.length; g += 3) {
            var lum = 0.3 * img.rgb[g] + 0.59 * img.rgb[g + 1] + 0.11 * img.rgb[g + 2];
            img.rgb[g] = img.rgb[g + 1] = img.rgb[g + 2] = lum;
        }
    }
    var indexes = dither(img, palette);
    var perByte = 8 / bits;
    var rowSize = Math.ceil(img.width / perByte);
    var dataSize = rowSize * img.height;
    var bytes = new Array(12 + dataSize + palette.length);
    for (var z = 0; z < bytes.length; z++) {
        bytes[z] = 0;
    }
    writeU16(bytes, 0, rowSize);
    // version 1, palettized format: 3 = 2-bit, 4 = 4-bit.
    writeU16(bytes, 2, (1 << 12) | ((bits === 4 ? 4 : 3) << 1));
    writeU16(bytes, 4, 0);
    writeU16(bytes, 6, 0);
    writeU16(bytes, 8, img.width);
    writeU16(bytes, 10, img.height);
    for (var y = 0; y < img.height; y++) {
        for (var x = 0; x < img.width; x++) {
            var idx = indexes[y * img.width + x];
            var byteIndex = 12 + y * rowSize + Math.floor(x / perByte);
            var shift = 8 - bits * (x % perByte + 1);
            bytes[byteIndex] |= (idx << shift);
        }
    }
    for (var p = 0; p < palette.length; p++) {
        // Pebble ARGB8: alpha 11, then 2 bits each of R, G, B.
        bytes[12 + dataSize + p] = 0xc0 | (toLevel(palette[p][0]) << 4) | (toLevel(palette[p][1]) << 2) | toLevel(palette[p][2]);
    }
    return {width: img.width, height: img.height, bytes: bytes};
};
