/**
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

var config = require('../config');
var location = require('../location');

var WEATHER_CONDITION_LIGHT_RAIN = 1;
var WEATHER_CONDITION_HEAVY_RAIN = 2;
var WEATHER_CONDITION_LIGHT_SNOW = 3;
var WEATHER_CONDITION_HEAVY_SNOW = 4;
var WEATHER_CONDITION_CLOUDY_DAY = 5;
var WEATHER_CONDITION_WEATHER_ICON = 6;
var WEATHER_CONDITION_PARTLY_CLOUDY = 7;
var WEATHER_CONDITION_SUN = 8;

function schema(properties, required) {
    return {
        type: 'object',
        properties: properties,
        required: required || []
    };
}

function numberSchema(description) {
    return {
        type: 'number',
        description: description
    };
}

function stringSchema(description) {
    return {
        type: 'string',
        description: description
    };
}

exports.getDeclarations = function() {
    return [{
        type: 'function',
        name: 'get_weather',
        description: 'Show a Pebble weather card with current conditions and return a short forecast. Use this for weather, temperature, umbrella, wind, or forecast requests. Omit latitude/longitude for local weather.',
        parameters: schema({
            latitude: numberSchema('Optional latitude for a named place. Omit for local weather.'),
            longitude: numberSchema('Optional longitude for a named place. Omit for local weather.'),
            location_name: stringSchema('Optional place label, city, address, or destination name.')
        }, [])
    }];
};

exports.execute = function(session, call, callback) {
    if (call.name !== 'get_weather') {
        return false;
    }
    var args = normalizeArguments(call);
    session.handleMessage({data: 'fChecking weather'});
    resolveWeatherLocation(args, function(locationErr, weatherLocation) {
        if (locationErr) {
            callback({status: 'error', summary: locationErr.message || String(locationErr)});
            return;
        }
        fetchWeather(weatherLocation, function(weatherErr, report) {
            if (weatherErr) {
                callback({status: 'error', summary: weatherErr.message || String(weatherErr)});
                return;
            }
            session.enqueue({
                WEATHER_WIDGET: 2,
                WEATHER_WIDGET_CURRENT_TEMP: report.temperature,
                WEATHER_WIDGET_FEELS_LIKE: report.feelsLike,
                WEATHER_WIDGET_LOCATION: report.locationLabel.toUpperCase().substring(0, 28),
                WEATHER_WIDGET_DAY_SUMMARY: report.description.substring(0, 80),
                WEATHER_WIDGET_TEMP_UNIT: report.tempUnit,
                WEATHER_WIDGET_WIND_SPEED: report.windSpeed,
                WEATHER_WIDGET_WIND_SPEED_UNIT: report.windSpeedUnit,
                WEATHER_WIDGET_DAY_ICON: report.condition
            });
            callback({
                status: 'ok',
                summary: report.summary,
                watch_card_contains: 'current temperature, feels-like temperature, icon, and condition',
                location: report.locationLabel,
                high: report.high,
                low: report.low,
                rain_chance: report.rainChance,
                wind_speed: report.windSpeed,
                wind_speed_unit: report.windSpeedUnit
            });
        });
    });
    return true;
};

function normalizeArguments(call) {
    var args = call.arguments || {};
    if (typeof args === 'string') {
        try {
            return JSON.parse(args);
        } catch (e) {
            return {};
        }
    }
    return args;
}

function resolveWeatherLocation(args, callback) {
    var latitude = parseFloat(args.latitude);
    var longitude = parseFloat(args.longitude);
    var name = String(args.location_name || '').trim();
    if (!isNaN(latitude) && !isNaN(longitude)) {
        callback(null, {
            latitude: latitude,
            longitude: longitude,
            label: cleanLabel(name || 'Weather')
        });
        return;
    }
    if (name) {
        geocode(name, callback);
        return;
    }
    if (!config.isLocationEnabled()) {
        callback(new Error('Location is disabled in Billy settings.'));
        return;
    }
    if (!location.isReady()) {
        location.update();
        callback(new Error('Local weather needs a recent phone location. Open Billy once or check location permission.'));
        return;
    }
    var pos = location.getPos();
    callback(null, {
        latitude: pos.lat,
        longitude: pos.lon,
        label: 'Local'
    });
}

function geocode(name, callback) {
    var url = 'https://nominatim.openstreetmap.org/search?format=json&limit=1&q=' + encodeURIComponent(name);
    fetchJson(url, function(err, results) {
        if (err) {
            callback(new Error('Location lookup failed: ' + err.message));
            return;
        }
        if (!results || !results.length) {
            callback(new Error('No location found for ' + name + '.'));
            return;
        }
        var first = results[0];
        var latitude = parseFloat(first.lat);
        var longitude = parseFloat(first.lon);
        if (isNaN(latitude) || isNaN(longitude)) {
            callback(new Error('Location result did not include coordinates.'));
            return;
        }
        callback(null, {
            latitude: latitude,
            longitude: longitude,
            label: cleanLabel(name)
        });
    });
}

function fetchWeather(weatherLocation, callback) {
    var units = getUnits();
    var url = 'https://api.open-meteo.com/v1/forecast' +
        '?latitude=' + encodeURIComponent(weatherLocation.latitude) +
        '&longitude=' + encodeURIComponent(weatherLocation.longitude) +
        '&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m' +
        '&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max' +
        '&forecast_days=1&timezone=auto' +
        '&temperature_unit=' + encodeURIComponent(units.temperatureParameter) +
        '&wind_speed_unit=' + encodeURIComponent(units.windParameter);
    fetchJson(url, function(err, json) {
        if (err) {
            callback(new Error('Weather lookup failed: ' + err.message));
            return;
        }
        try {
            callback(null, buildWeatherReport(weatherLocation, json, units));
        } catch (e) {
            callback(e);
        }
    });
}

function buildWeatherReport(weatherLocation, json, units) {
    var current = json.current || {};
    var daily = json.daily || {};
    var code = integer(current.weather_code, arrayFirst(daily.weather_code, 0));
    var high = optionalRounded(arrayFirst(daily.temperature_2m_max));
    var low = optionalRounded(arrayFirst(daily.temperature_2m_min));
    var rainChance = optionalRounded(arrayFirst(daily.precipitation_probability_max));
    var temperature = Math.round(number(current.temperature_2m, 0));
    var feelsLike = Math.round(number(current.apparent_temperature, temperature));
    var windSpeed = Math.round(number(current.wind_speed_10m, 0));
    var description = weatherDescription(code);
    return {
        locationLabel: cleanLabel(weatherLocation.label),
        temperature: temperature,
        feelsLike: feelsLike,
        tempUnit: units.temperatureUnit,
        windSpeed: windSpeed,
        windSpeedUnit: units.windUnit,
        condition: weatherCondition(code),
        description: description,
        high: high,
        low: low,
        rainChance: rainChance,
        summary: humanWeatherSummary(description, units.temperatureUnit, windSpeed, units.windUnit, high, low, rainChance)
    };
}

function humanWeatherSummary(description, tempUnit, windSpeed, windUnit, high, low, rainChance) {
    var parts = [];
    if (high !== null && low !== null) {
        parts.push('Today: high ' + high + tempUnit + ', low ' + low + tempUnit + '.');
    } else {
        parts.push(description + ' overall.');
    }
    if (rainChance !== null) {
        if (rainChance >= 50) {
            parts.push('Rain likely.');
        } else if (rainChance >= 25) {
            parts.push('Some rain risk.');
        } else {
            parts.push('Rain unlikely.');
        }
    }
    if (windSpeed >= (windUnit === 'mph' ? 25 : 40)) {
        parts.push('Strong wind: ' + windSpeed + ' ' + windUnit + '.');
    } else if (windSpeed >= (windUnit === 'mph' ? 12 : 20)) {
        parts.push('Breezy: ' + windSpeed + ' ' + windUnit + '.');
    } else {
        parts.push('Light wind.');
    }
    return parts.join(' ');
}

function getUnits() {
    var preference = String(config.getSetting('UNIT_PREFERENCE', '') || '').toLowerCase();
    var fahrenheit = preference === 'imperial' || (!preference && isLikelyUsLocale());
    var windMph = fahrenheit || preference === 'uk';
    return {
        temperatureParameter: fahrenheit ? 'fahrenheit' : 'celsius',
        temperatureUnit: fahrenheit ? '\u00b0F' : '\u00b0C',
        windParameter: windMph ? 'mph' : 'kmh',
        windUnit: windMph ? 'mph' : 'km/h'
    };
}

function isLikelyUsLocale() {
    try {
        return navigator && navigator.language && /-US$/i.test(navigator.language);
    } catch (e) {
        return false;
    }
}

function weatherCondition(code) {
    if (code === 0) {
        return WEATHER_CONDITION_SUN;
    }
    if (code === 1 || code === 2) {
        return WEATHER_CONDITION_PARTLY_CLOUDY;
    }
    if (code === 3 || code === 45 || code === 48) {
        return WEATHER_CONDITION_CLOUDY_DAY;
    }
    if (code === 51 || code === 53 || code === 55 || code === 56 || code === 57 || code === 61 || code === 80) {
        return WEATHER_CONDITION_LIGHT_RAIN;
    }
    if (code === 63 || code === 65 || code === 66 || code === 67 || code === 81 || code === 82 || code === 95 || code === 96 || code === 99) {
        return WEATHER_CONDITION_HEAVY_RAIN;
    }
    if (code === 71 || code === 73 || code === 77 || code === 85) {
        return WEATHER_CONDITION_LIGHT_SNOW;
    }
    if (code === 75 || code === 86) {
        return WEATHER_CONDITION_HEAVY_SNOW;
    }
    return WEATHER_CONDITION_WEATHER_ICON;
}

function weatherDescription(code) {
    var descriptions = {
        0: 'Clear',
        1: 'Mostly clear',
        2: 'Partly cloudy',
        3: 'Cloudy',
        45: 'Foggy',
        48: 'Foggy',
        51: 'Drizzle',
        53: 'Drizzle',
        55: 'Drizzle',
        56: 'Freezing drizzle',
        57: 'Freezing drizzle',
        61: 'Light rain',
        63: 'Rain',
        65: 'Heavy rain',
        66: 'Freezing rain',
        67: 'Freezing rain',
        71: 'Snow',
        73: 'Snow',
        75: 'Heavy snow',
        77: 'Snow grains',
        80: 'Showers',
        81: 'Heavy showers',
        82: 'Heavy showers',
        85: 'Snow showers',
        86: 'Heavy snow',
        95: 'Thunderstorms',
        96: 'Thunderstorms',
        99: 'Thunderstorms'
    };
    return descriptions[code] || 'Weather';
}

function fetchJson(url, callback) {
    var finished = false;
    var req = new XMLHttpRequest();
    function finish(err, value) {
        if (finished) {
            return;
        }
        finished = true;
        callback(err, value);
    }
    req.open('GET', url, true);
    req.timeout = 12000;
    req.setRequestHeader('Accept', 'application/json');
    req.onload = function() {
        if (req.readyState !== 4) {
            return;
        }
        if (req.status < 200 || req.status >= 300) {
            finish(new Error('HTTP ' + req.status + ': ' + String(req.responseText || '').substring(0, 120)));
            return;
        }
        try {
            finish(null, JSON.parse(req.responseText));
        } catch (e) {
            finish(e);
        }
    };
    req.onerror = function() {
        finish(new Error('network error'));
    };
    req.ontimeout = function() {
        finish(new Error('request timed out'));
    };
    req.send();
}

function arrayFirst(values, fallback) {
    if (!values || !values.length) {
        return fallback;
    }
    return values[0];
}

function optionalRounded(value) {
    var parsed = parseFloat(value);
    if (isNaN(parsed)) {
        return null;
    }
    return Math.round(parsed);
}

function number(value, fallback) {
    var parsed = parseFloat(value);
    return isNaN(parsed) ? fallback : parsed;
}

function integer(value, fallback) {
    var parsed = parseInt(value, 10);
    return isNaN(parsed) ? fallback : parsed;
}

function cleanLabel(value) {
    return String(value || 'Local').replace(/\s+/g, ' ').trim().substring(0, 28) || 'Local';
}
