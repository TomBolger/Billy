package com.tombo.billyassistant.companion.google

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GoogleMapsPlatformApiTools(
    private val apiKeyProvider: () -> String,
) {
    fun searchPlaces(query: String, maxResults: Int = 5): GoogleMapsPlatformResult {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            return GoogleMapsPlatformResult.NeedsApiKey
        }
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return GoogleMapsPlatformResult.Rejected("Place search query is blank.")
        }
        val body = JSONObject()
            .put("textQuery", cleanQuery)
            .put("pageSize", maxResults.coerceIn(1, 10))
        return when (val response = postJson(
            url = "$PLACES_BASE/places:searchText",
            body = body,
            headers = mapOf(
                "X-Goog-Api-Key" to key,
                "X-Goog-FieldMask" to "places.id,places.displayName,places.formattedAddress,places.shortFormattedAddress,places.location,places.rating,places.userRatingCount,places.currentOpeningHours,places.businessStatus,places.types,places.googleMapsUri,places.websiteUri,places.nationalPhoneNumber,places.priceLevel",
            ),
        )) {
            is MapsHttpResult.Success -> {
                val places = JSONObject(response.body).optJSONArray("places") ?: JSONArray()
                val compact = JSONArray()
                for (i in 0 until minOf(places.length(), maxResults.coerceIn(1, 10))) {
                    places.optJSONObject(i)?.let { compact.put(compactPlace(it)) }
                }
                val summary = when (compact.length()) {
                    0 -> "No Google Places found for \"$cleanQuery\"."
                    1 -> "Found 1 Google Place."
                    else -> "Found ${compact.length()} Google Places."
                }
                GoogleMapsPlatformResult.Success(
                    summary = summary,
                    payload = JSONObject()
                        .put("status", "ok")
                        .put("summary", summary)
                        .put("places", compact),
                )
            }
            is MapsHttpResult.HttpError -> GoogleMapsPlatformResult.Failed(response.toHumanMapsError("Places"))
            is MapsHttpResult.Failed -> GoogleMapsPlatformResult.Failed("Google Places failed: ${response.reason}")
        }
    }

    fun findNearbyPlaces(
        query: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = 50_000.0,
        maxResults: Int = 5,
        includedType: String? = null,
    ): GoogleMapsPlatformResult {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            return GoogleMapsPlatformResult.NeedsApiKey
        }
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return GoogleMapsPlatformResult.Rejected("Nearby place search query is blank.")
        }
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return GoogleMapsPlatformResult.Rejected("Current location is invalid.")
        }
        val radius = radiusMeters.coerceIn(100.0, 50_000.0)
        val body = JSONObject()
            .put("textQuery", cleanQuery)
            .put("pageSize", maxResults.coerceIn(1, 10))
            .put("rankPreference", "DISTANCE")
            .put(
                "locationRestriction",
                JSONObject().put(
                    "rectangle",
                    restrictionRectangle(latitude, longitude, radius),
                ),
            )
        includedType?.trim()?.takeIf { it.isNotBlank() }?.let { type ->
            body.put("includedType", type)
            body.put("strictTypeFiltering", true)
        }
        return when (val response = postJson(
            url = "$PLACES_BASE/places:searchText",
            body = body,
            headers = mapOf(
                "X-Goog-Api-Key" to key,
                "X-Goog-FieldMask" to "places.id,places.displayName,places.formattedAddress,places.shortFormattedAddress,places.location,places.rating,places.userRatingCount,places.currentOpeningHours,places.businessStatus,places.types,places.primaryType,places.googleMapsUri,places.websiteUri,places.nationalPhoneNumber,places.priceLevel",
            ),
        )) {
            is MapsHttpResult.Success -> {
                val places = JSONObject(response.body).optJSONArray("places") ?: JSONArray()
                val sorted = buildList {
                    for (i in 0 until places.length()) {
                        val place = places.optJSONObject(i) ?: continue
                        add(compactPlace(place).withDistanceFrom(latitude, longitude))
                    }
                }.sortedBy { it.optDouble("distance_meters", Double.MAX_VALUE) }
                val compact = JSONArray()
                sorted.take(maxResults.coerceIn(1, 10)).forEach { compact.put(it) }
                val summary = when (compact.length()) {
                    0 -> "No nearby Google Places found for \"$cleanQuery\" within ${(radius / 1000.0).toInt()} km."
                    1 -> "Found the nearest Google Place."
                    else -> "Found ${compact.length()} nearby Google Places."
                }
                GoogleMapsPlatformResult.Success(
                    summary = summary,
                    payload = JSONObject()
                        .put("status", "ok")
                        .put("summary", summary)
                        .put("query", cleanQuery)
                        .put("origin_latitude", latitude)
                        .put("origin_longitude", longitude)
                        .put("radius_meters", radius)
                        .put("included_type", includedType?.trim().orEmpty())
                        .put("places", compact),
                )
            }
            is MapsHttpResult.HttpError -> GoogleMapsPlatformResult.Failed(response.toHumanMapsError("Places"))
            is MapsHttpResult.Failed -> GoogleMapsPlatformResult.Failed("Google Places failed: ${response.reason}")
        }
    }

    fun geocodeAddress(query: String, maxResults: Int = 3): GoogleMapsPlatformResult {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            return GoogleMapsPlatformResult.NeedsApiKey
        }
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return GoogleMapsPlatformResult.Rejected("Geocode query is blank.")
        }
        val url = "$GEOCODING_BASE/json?address=${encode(cleanQuery)}&key=${encode(key)}"
        return when (val response = get(url)) {
            is MapsHttpResult.Success -> {
                val json = JSONObject(response.body)
                val status = json.optString("status")
                if (status != "OK") {
                    return GoogleMapsPlatformResult.Failed("Google Geocoding returned $status: ${json.optString("error_message").take(120)}")
                }
                val results = json.optJSONArray("results") ?: JSONArray()
                val compact = JSONArray()
                for (i in 0 until minOf(results.length(), maxResults.coerceIn(1, 10))) {
                    results.optJSONObject(i)?.let { compact.put(compactGeocode(it)) }
                }
                val summary = when (compact.length()) {
                    0 -> "No Google Geocoding result found."
                    1 -> "Found 1 Google Geocoding result."
                    else -> "Found ${compact.length()} Google Geocoding results."
                }
                GoogleMapsPlatformResult.Success(
                    summary = summary,
                    payload = JSONObject()
                        .put("status", "ok")
                        .put("summary", summary)
                        .put("results", compact),
                )
            }
            is MapsHttpResult.HttpError -> GoogleMapsPlatformResult.Failed(response.toHumanMapsError("Geocoding"))
            is MapsHttpResult.Failed -> GoogleMapsPlatformResult.Failed("Google Geocoding failed: ${response.reason}")
        }
    }

    fun getTimeZone(latitude: Double, longitude: Double, timestampMillis: Long? = null): GoogleMapsPlatformResult {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            return GoogleMapsPlatformResult.NeedsApiKey
        }
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return GoogleMapsPlatformResult.Rejected("Latitude or longitude is invalid.")
        }
        val seconds = ((timestampMillis ?: System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        val url = "$TIMEZONE_BASE/json?location=$latitude,$longitude&timestamp=$seconds&key=${encode(key)}"
        return when (val response = get(url)) {
            is MapsHttpResult.Success -> {
                val json = JSONObject(response.body)
                val status = json.optString("status")
                if (status != "OK") {
                    return GoogleMapsPlatformResult.Failed("Google Time Zone returned $status: ${json.optString("errorMessage").take(120)}")
                }
                val zoneName = json.optString("timeZoneName")
                val zoneId = json.optString("timeZoneId")
                val offsetSeconds = json.optLong("rawOffset") + json.optLong("dstOffset")
                val summary = "$zoneName ($zoneId), UTC${formatOffset(offsetSeconds)}."
                GoogleMapsPlatformResult.Success(
                    summary = summary,
                    payload = JSONObject()
                        .put("status", "ok")
                        .put("summary", summary)
                        .put("time_zone_id", zoneId)
                        .put("time_zone_name", zoneName)
                        .put("utc_offset_seconds", offsetSeconds),
                )
            }
            is MapsHttpResult.HttpError -> GoogleMapsPlatformResult.Failed(response.toHumanMapsError("Time Zone"))
            is MapsHttpResult.Failed -> GoogleMapsPlatformResult.Failed("Google Time Zone failed: ${response.reason}")
        }
    }

    fun findRoute(origin: String, destination: String, travelMode: String = "DRIVE"): GoogleMapsPlatformResult {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            return GoogleMapsPlatformResult.NeedsApiKey
        }
        val cleanOrigin = origin.trim()
        val cleanDestination = destination.trim()
        if (cleanOrigin.isBlank() || cleanDestination.isBlank()) {
            return GoogleMapsPlatformResult.Rejected("Route needs both origin and destination.")
        }
        val mode = canonicalRouteMode(travelMode)
            ?: return GoogleMapsPlatformResult.Rejected("Invalid travel_mode \"$travelMode\". Use DRIVE, WALK, BICYCLE, TRANSIT, or TWO_WHEELER.")
        val body = JSONObject()
            .put("origin", JSONObject().put("address", cleanOrigin))
            .put("destination", JSONObject().put("address", cleanDestination))
            .put("travelMode", mode)
            .put("polylineQuality", "OVERVIEW")
            .put("polylineEncoding", "ENCODED_POLYLINE")
            .put("languageCode", Locale.getDefault().toLanguageTag())
        return routeResponse(
            response = postJson(
                url = "$ROUTES_BASE/directions/v2:computeRoutes",
                body = body,
                headers = mapOf(
                    "X-Goog-Api-Key" to key,
                    "X-Goog-FieldMask" to ROUTE_FIELD_MASK,
                ),
            ),
            origin = cleanOrigin,
            destination = cleanDestination,
            travelMode = mode,
        )
    }

    fun findRouteFromPoint(
        originLatitude: Double,
        originLongitude: Double,
        destination: String,
        travelMode: String = "DRIVE",
    ): GoogleMapsPlatformResult {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            return GoogleMapsPlatformResult.NeedsApiKey
        }
        val cleanDestination = destination.trim()
        if (cleanDestination.isBlank()) {
            return GoogleMapsPlatformResult.Rejected("Route needs a destination.")
        }
        if (originLatitude !in -90.0..90.0 || originLongitude !in -180.0..180.0) {
            return GoogleMapsPlatformResult.Rejected("Current location is invalid.")
        }
        val mode = canonicalRouteMode(travelMode)
            ?: return GoogleMapsPlatformResult.Rejected("Invalid travel_mode \"$travelMode\". Use DRIVE, WALK, BICYCLE, TRANSIT, or TWO_WHEELER.")
        val body = JSONObject()
            .put(
                "origin",
                JSONObject().put(
                    "location",
                    JSONObject().put(
                        "latLng",
                        JSONObject()
                            .put("latitude", originLatitude)
                            .put("longitude", originLongitude),
                    ),
                ),
            )
            .put("destination", JSONObject().put("address", cleanDestination))
            .put("travelMode", mode)
            .put("polylineQuality", "OVERVIEW")
            .put("polylineEncoding", "ENCODED_POLYLINE")
            .put("languageCode", Locale.getDefault().toLanguageTag())
        return routeResponse(
            response = postJson(
                url = "$ROUTES_BASE/directions/v2:computeRoutes",
                body = body,
                headers = mapOf(
                    "X-Goog-Api-Key" to key,
                    "X-Goog-FieldMask" to ROUTE_FIELD_MASK,
                ),
            ),
            origin = "$originLatitude,$originLongitude",
            destination = cleanDestination,
            travelMode = mode,
        )
    }

    private fun routeResponse(
        response: MapsHttpResult,
        origin: String,
        destination: String,
        travelMode: String,
    ): GoogleMapsPlatformResult {
        return when (response) {
            is MapsHttpResult.Success -> {
                val routes = JSONObject(response.body).optJSONArray("routes") ?: JSONArray()
                val route = routes.optJSONObject(0)
                    ?: return GoogleMapsPlatformResult.Failed("Google Routes returned no routes.")
                val compact = compactRoute(route)
                val distance = compact.optString("distance_text").ifBlank { "${route.optLong("distanceMeters")} m" }
                val duration = compact.optString("duration_text").ifBlank { route.optString("duration").removeSuffix("s") + " sec" }
                val summary = "Route: $distance, $duration."
                GoogleMapsPlatformResult.Success(
                    summary = summary,
                    payload = JSONObject()
                        .put("status", "ok")
                        .put("summary", summary)
                        .put("origin", origin)
                        .put("destination", destination)
                        .put("travel_mode", travelMode)
                        .put("route", compact),
                )
            }
            is MapsHttpResult.HttpError -> GoogleMapsPlatformResult.Failed(response.toHumanMapsError("Routes"))
            is MapsHttpResult.Failed -> GoogleMapsPlatformResult.Failed("Google Routes failed: ${response.reason}")
        }
    }

    private fun compactPlace(place: JSONObject): JSONObject {
        val location = place.optJSONObject("location") ?: JSONObject()
        val name = place.optJSONObject("displayName")?.optString("text").orEmpty()
        val hours = place.optJSONObject("currentOpeningHours")
        return JSONObject()
            .put("id", place.optString("id"))
            .put("name", name)
            .put("address", place.optString("shortFormattedAddress").ifBlank { place.optString("formattedAddress") })
            .put("latitude", location.optDouble("latitude"))
            .put("longitude", location.optDouble("longitude"))
            .put("rating", place.optDouble("rating"))
            .put("rating_count", place.optInt("userRatingCount"))
            .put("open_now", hours?.optBoolean("openNow"))
            .put("primary_type", place.optString("primaryType"))
            .put("types", place.optJSONArray("types") ?: JSONArray())
            .put("phone", place.optString("nationalPhoneNumber"))
            .put("google_maps_uri", place.optString("googleMapsUri"))
            .put("website_uri", place.optString("websiteUri"))
            .put("label", listOf(name, place.optString("shortFormattedAddress")).filter { it.isNotBlank() }.joinToString(" - ").take(90))
    }

    private fun JSONObject.withDistanceFrom(latitude: Double, longitude: Double): JSONObject {
        val placeLatitude = optDouble("latitude")
        val placeLongitude = optDouble("longitude")
        if (placeLatitude in -90.0..90.0 && placeLongitude in -180.0..180.0) {
            put("distance_meters", haversineMeters(latitude, longitude, placeLatitude, placeLongitude).toLong())
        }
        return this
    }

    private fun haversineMeters(startLatitude: Double, startLongitude: Double, endLatitude: Double, endLongitude: Double): Double {
        val deltaLatitude = Math.toRadians(endLatitude - startLatitude)
        val deltaLongitude = Math.toRadians(endLongitude - startLongitude)
        val startLat = Math.toRadians(startLatitude)
        val endLat = Math.toRadians(endLatitude)
        val a = sin(deltaLatitude / 2).let { it * it } +
            cos(startLat) * cos(endLat) * sin(deltaLongitude / 2).let { it * it }
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    private fun restrictionRectangle(latitude: Double, longitude: Double, radiusMeters: Double): JSONObject {
        val latitudeDelta = Math.toDegrees(radiusMeters / EARTH_RADIUS_METERS)
        val longitudeDelta = Math.toDegrees(radiusMeters / (EARTH_RADIUS_METERS * cos(Math.toRadians(latitude)).coerceAtLeast(0.01)))
        val lowLatitude = (latitude - latitudeDelta).coerceIn(-90.0, 90.0)
        val highLatitude = (latitude + latitudeDelta).coerceIn(-90.0, 90.0)
        val lowLongitude = (longitude - longitudeDelta).coerceIn(-180.0, 180.0)
        val highLongitude = (longitude + longitudeDelta).coerceIn(-180.0, 180.0)
        return JSONObject()
            .put("low", JSONObject().put("latitude", lowLatitude).put("longitude", lowLongitude))
            .put("high", JSONObject().put("latitude", highLatitude).put("longitude", highLongitude))
    }

    private fun compactGeocode(result: JSONObject): JSONObject {
        val geometry = result.optJSONObject("geometry") ?: JSONObject()
        val location = geometry.optJSONObject("location") ?: JSONObject()
        return JSONObject()
            .put("formatted_address", result.optString("formatted_address"))
            .put("place_id", result.optString("place_id"))
            .put("latitude", location.optDouble("lat"))
            .put("longitude", location.optDouble("lng"))
            .put("location_type", geometry.optString("location_type"))
            .put("types", result.optJSONArray("types") ?: JSONArray())
    }

    private fun compactRoute(route: JSONObject): JSONObject {
        val localized = route.optJSONObject("localizedValues") ?: JSONObject()
        val steps = JSONArray()
        val rawSteps = route.optJSONArray("legs")
            ?.optJSONObject(0)
            ?.optJSONArray("steps")
            ?: JSONArray()
        for (i in 0 until minOf(rawSteps.length(), 6)) {
            val step = rawSteps.optJSONObject(i) ?: continue
            val instruction = step.optJSONObject("navigationInstruction") ?: JSONObject()
            val stepValues = step.optJSONObject("localizedValues") ?: JSONObject()
            steps.put(
                JSONObject()
                    .put("instruction", instruction.optString("instructions"))
                    .put("maneuver", instruction.optString("maneuver"))
                    .put("distance_text", stepValues.optJSONObject("distance")?.optString("text").orEmpty())
                    .put("duration_text", stepValues.optJSONObject("staticDuration")?.optString("text").orEmpty()),
            )
        }
        return JSONObject()
            .put("distance_meters", route.optLong("distanceMeters"))
            .put("distance_text", localized.optJSONObject("distance")?.optString("text").orEmpty())
            .put("duration", route.optString("duration"))
            .put("duration_text", localized.optJSONObject("duration")?.optString("text").orEmpty())
            .put("description", route.optString("description"))
            .put("encoded_polyline", route.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty())
            .put("steps", steps)
    }

    private fun get(url: String): MapsHttpResult {
        return request(method = "GET", url = url, body = null, headers = emptyMap())
    }

    private fun postJson(url: String, body: JSONObject, headers: Map<String, String>): MapsHttpResult {
        return request(method = "POST", url = url, body = body, headers = headers)
    }

    private fun request(method: String, url: String, body: JSONObject?, headers: Map<String, String>): MapsHttpResult {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body.toString())
                }
            }
            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (responseCode in 200..299) {
                MapsHttpResult.Success(responseText)
            } else {
                MapsHttpResult.HttpError(responseCode, extractError(responseText))
            }
        } catch (e: Exception) {
            MapsHttpResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun extractError(responseText: String): String {
        if (responseText.isBlank()) {
            return "empty Maps API error response"
        }
        return runCatching {
            val json = JSONObject(responseText)
            json.optJSONObject("error")?.optString("message").orEmpty()
                .ifBlank { json.optString("error_message") }
                .ifBlank { responseText.take(MAX_ERROR_LENGTH) }
        }.getOrDefault(responseText.take(MAX_ERROR_LENGTH))
    }

    private fun MapsHttpResult.HttpError.toHumanMapsError(service: String): String {
        val text = reason.lowercase(Locale.US)
        return when {
            responseCode == 403 && (text.contains("billing") || text.contains("billable")) -> "$service says billing is not enabled for the Cloud project that owns this pasted Maps key. Pay-as-you-go on another project will not work; create/paste a key from the billed project and enable this exact API there."
            responseCode == 403 && (text.contains("disabled") || text.contains("not been used")) -> "$service API is disabled for this Maps key project."
            responseCode == 403 && text.contains("api key") -> "$service rejected the Google Maps API key."
            responseCode == 429 -> "$service quota was exceeded for this Maps key."
            responseCode in 500..599 -> "$service is temporarily unavailable."
            else -> "$service returned HTTP $responseCode: ${reason.take(160)}"
        }
    }

    private fun canonicalRouteMode(value: String): String? {
        if (value.isBlank()) {
            return GoogleMapsTravelMode.DRIVE.routesValue
        }
        return value.toCanonicalGoogleMapsTravelMode()?.routesValue
    }

    private fun formatOffset(seconds: Long): String {
        val sign = if (seconds < 0) "-" else "+"
        val abs = kotlin.math.abs(seconds)
        val hours = abs / 3600L
        val minutes = (abs % 3600L) / 60L
        return "$sign%02d:%02d".format(hours, minutes)
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private sealed interface MapsHttpResult {
        data class Success(val body: String) : MapsHttpResult
        data class HttpError(val responseCode: Int, val reason: String) : MapsHttpResult
        data class Failed(val reason: String) : MapsHttpResult
    }

    private companion object {
        private const val PLACES_BASE = "https://places.googleapis.com/v1"
        private const val ROUTES_BASE = "https://routes.googleapis.com"
        private const val GEOCODING_BASE = "https://maps.googleapis.com/maps/api/geocode"
        private const val TIMEZONE_BASE = "https://maps.googleapis.com/maps/api/timezone"
        private const val ROUTE_FIELD_MASK = "routes.distanceMeters,routes.duration,routes.description,routes.polyline.encodedPolyline,routes.localizedValues,routes.legs.steps.navigationInstruction,routes.legs.steps.localizedValues"
        private const val TIMEOUT_MS = 8_000
        private const val MAX_ERROR_LENGTH = 220
        private const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}

sealed interface GoogleMapsPlatformResult {
    val summary: String

    data class Success(
        override val summary: String,
        val payload: JSONObject,
    ) : GoogleMapsPlatformResult

    data object NeedsApiKey : GoogleMapsPlatformResult {
        override val summary: String = "Add a Google Maps API key in Billy Companion for Places, Routes, Geocoding, and Time Zone."
    }

    data class Rejected(val reason: String) : GoogleMapsPlatformResult {
        override val summary: String = reason
    }

    data class Failed(val reason: String) : GoogleMapsPlatformResult {
        override val summary: String = reason
    }
}
