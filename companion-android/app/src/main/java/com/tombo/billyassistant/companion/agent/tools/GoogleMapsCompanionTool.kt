package com.tombo.billyassistant.companion.agent.tools

import android.content.Context
import com.tombo.billyassistant.companion.google.GoogleMapsPlatformApiTools
import com.tombo.billyassistant.companion.google.GoogleMapsPlatformResult
import com.tombo.billyassistant.companion.google.googleMapsTravelModeValues
import org.json.JSONArray
import org.json.JSONObject

class GoogleMapsCompanionTool(
    private val context: Context,
    private val mapsApiTools: GoogleMapsPlatformApiTools,
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        JSONObject()
            .put("name", "find_nearby_google_places")
            .put("description", "Find places near the Android phone's current location. Use this for nearest, nearby, near me, local, closest, or around me place requests before opening navigation or showing a map. Requires location permission and a Google Maps API key.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("query"),
                    properties = mapOf(
                        "query" to stringSchema("Place category or search text, such as train station, pizza, pharmacy, airport, hotel, coffee, restaurant, gas station, museum, or grocery store."),
                        "included_type" to stringSchema("Optional Places type when known. Examples: train_station, transit_station, airport, restaurant, cafe, gas_station, pharmacy, hospital, hotel, grocery_store, parking."),
                        "radius_meters" to numberSchema("Optional search radius around current location. Defaults to 50000 and cannot exceed 50000."),
                        "max_results" to integerSchema("Maximum number of places to return. Defaults to 5."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "search_google_places")
            .put("description", "Search Google Maps Places by text when the user supplied an explicit city, address, or named area. Do not use for nearest, nearby, near me, closest, local, or around me requests; use find_nearby_google_places for those. Requires a Google Maps API key in Billy Companion.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("query"),
                    properties = mapOf(
                        "query" to stringSchema("Place search query."),
                        "max_results" to integerSchema("Maximum number of places to return. Defaults to 5."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "geocode_google_maps")
            .put("description", "Resolve an address or place query to Google Maps coordinates. Requires a Google Maps API key in Billy Companion.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("query"),
                    properties = mapOf(
                        "query" to stringSchema("Address or place to geocode."),
                        "max_results" to integerSchema("Maximum number of geocoding results. Defaults to 3."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "get_google_route")
            .put("description", "Get a Google Routes travel summary and first steps. Use for travel time, route comparison, directions reasoning, how-to-get-there questions, or navigation reasoning. Requires a Google Maps API key in Billy Companion.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("destination"),
                    properties = mapOf(
                        "origin" to stringSchema("Route origin address or place. For the phone's current location, omit this or set use_current_location=true."),
                        "use_current_location" to booleanSchema("Use the Android phone's current location as the origin. Use this for 'from here' or 'from current location'."),
                        "destination" to stringSchema("Route destination address or place."),
                        "travel_mode" to enumStringSchema("Canonical travel mode selected from the user's intent. Defaults to DRIVE when the user did not specify one.", googleMapsTravelModeValues),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "get_google_time_zone")
            .put("description", "Look up the Google Maps time zone for latitude and longitude. Requires a Google Maps API key in Billy Companion.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("latitude", "longitude"),
                    properties = mapOf(
                        "latitude" to numberSchema("Latitude."),
                        "longitude" to numberSchema("Longitude."),
                        "timestamp_millis" to integerSchema("Optional Unix epoch milliseconds for the time-zone offset. Defaults to now."),
                    ),
                ),
            ),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        return when (name) {
            "find_nearby_google_places" -> findNearbyPlaces(args)
            "search_google_places" -> mapsApiTools.searchPlaces(
                query = args.optString("query"),
                maxResults = args.optionalInt("max_results") ?: 5,
            ).toExecution(finalOnSuccess = false)
            "geocode_google_maps" -> mapsApiTools.geocodeAddress(
                query = args.optString("query"),
                maxResults = args.optionalInt("max_results") ?: 3,
            ).toExecution(finalOnSuccess = false)
            "get_google_route" -> findRoute(args)
            "get_google_time_zone" -> mapsApiTools.getTimeZone(
                latitude = args.optDouble("latitude"),
                longitude = args.optDouble("longitude"),
                timestampMillis = args.optionalLong("timestamp_millis"),
            ).toExecution(finalOnSuccess = false)
            else -> null
        }
    }

    private fun findNearbyPlaces(args: JSONObject): CompanionToolExecution {
        val location = currentAndroidLocation(context)
            ?: return CompanionToolExecution(
                response = JSONObject()
                    .put("status", "error")
                    .put("summary", "Current location unavailable. Grant Billy location access before nearby Maps searches."),
                finalText = "Current location unavailable. Grant Billy location access.",
            )
        return mapsApiTools.findNearbyPlaces(
            query = args.optString("query"),
            latitude = location.latitude,
            longitude = location.longitude,
            radiusMeters = args.optionalDouble("radius_meters") ?: 50_000.0,
            maxResults = args.optionalInt("max_results") ?: 5,
            includedType = args.optString("included_type"),
        ).toExecution(finalOnSuccess = false)
    }

    private fun findRoute(args: JSONObject): CompanionToolExecution {
        val destination = args.optString("destination")
        val travelMode = args.optString("travel_mode").ifBlank { "DRIVE" }
        val useCurrentLocation = args.optBoolean("use_current_location") || args.optString("origin").isBlank()
        if (!useCurrentLocation) {
            return mapsApiTools.findRoute(
                origin = args.optString("origin"),
                destination = destination,
                travelMode = travelMode,
            ).toExecution(finalOnSuccess = false)
        }
        val location = currentAndroidLocation(context)
            ?: return CompanionToolExecution(
                response = JSONObject()
                    .put("status", "error")
                    .put("summary", "Current location unavailable. Grant Billy location access before routing from here."),
                finalText = "Current location unavailable. Grant Billy location access.",
            )
        return mapsApiTools.findRouteFromPoint(
            originLatitude = location.latitude,
            originLongitude = location.longitude,
            destination = destination,
            travelMode = travelMode,
        ).toExecution(finalOnSuccess = false)
    }
}

private fun GoogleMapsPlatformResult.toExecution(finalOnSuccess: Boolean = false): CompanionToolExecution {
    val response = toJson()
    return CompanionToolExecution(
        response = response,
        finalText = if (finalOnSuccess || response.optString("status") != "ok") mapsWatchSummary(response, summary) else null,
    )
}

private fun GoogleMapsPlatformResult.toJson(): JSONObject {
    return when (this) {
        is GoogleMapsPlatformResult.Success -> payload
        GoogleMapsPlatformResult.NeedsApiKey -> JSONObject()
            .put("status", "needs_api_key")
            .put("summary", summary)
        is GoogleMapsPlatformResult.Rejected -> JSONObject()
            .put("status", "rejected")
            .put("summary", reason)
            .put("reason", reason)
        is GoogleMapsPlatformResult.Failed -> JSONObject()
            .put("status", "error")
            .put("summary", reason)
            .put("reason", reason)
    }
}

private fun mapsWatchSummary(response: JSONObject, fallback: String): String {
    if (response.optString("status") != "ok") {
        return response.optString("summary").ifBlank { fallback }
    }
    response.optJSONArray("places")?.let { places ->
        if (places.length() == 0) {
            return response.optString("summary").ifBlank { fallback }
        }
        val lines = mutableListOf("Places:")
        for (i in 0 until minOf(places.length(), 4)) {
            val place = places.optJSONObject(i) ?: continue
            val name = place.optString("name").ifBlank { "(unnamed)" }
            val address = place.optString("address")
            val distance = distanceLabel(place)
            val open = when {
                place.isNull("open_now") -> ""
                place.optBoolean("open_now") -> " open"
                else -> " closed"
            }
            lines += "- ${listOf(name, distance, address).filter { it.isNotBlank() }.joinToString(" - ").take(88)}$open"
        }
        return lines.joinToString("\n")
    }
    response.optJSONArray("results")?.let { results ->
        if (results.length() == 0) {
            return response.optString("summary").ifBlank { fallback }
        }
        val first = results.optJSONObject(0) ?: return fallback
        return "Location:\n${first.optString("formatted_address").take(120)}\n${first.optDouble("latitude")}, ${first.optDouble("longitude")}"
    }
    response.optJSONObject("route")?.let { route ->
        val lines = mutableListOf(
            "Route: ${route.optString("distance_text").ifBlank { route.optString("distance_meters") + " m" }}, ${route.optString("duration_text").ifBlank { route.optString("duration") }}",
        )
        val steps = route.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until minOf(steps.length(), 3)) {
            val step = steps.optJSONObject(i) ?: continue
            val instruction = step.optString("instruction")
            if (instruction.isNotBlank()) {
                lines += "- ${instruction.take(86)}"
            }
        }
        return lines.joinToString("\n")
    }
    return response.optString("summary").ifBlank { fallback }
}

private fun distanceLabel(place: JSONObject): String {
    if (!place.has("distance_meters") || place.isNull("distance_meters")) {
        return ""
    }
    val meters = place.optDouble("distance_meters")
    if (meters.isNaN() || meters <= 0.0) {
        return ""
    }
    val miles = meters / 1609.344
    return if (miles < 10.0) {
        "%.1f mi".format(java.util.Locale.US, miles)
    } else {
        "${miles.toInt()} mi"
    }
}

private fun JSONObject.optionalInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

private fun JSONObject.optionalLong(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}

private fun JSONObject.optionalDouble(name: String): Double? {
    return if (has(name) && !isNull(name)) optDouble(name) else null
}
