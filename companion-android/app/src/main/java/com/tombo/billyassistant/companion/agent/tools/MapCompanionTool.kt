package com.tombo.billyassistant.companion.agent.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import com.tombo.billyassistant.companion.google.GoogleMapsTravelMode
import com.tombo.billyassistant.companion.google.googleMapsTravelModeValues
import com.tombo.billyassistant.companion.google.toCanonicalGoogleMapsTravelMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

class MapCompanionTool(
    private val context: Context,
    private val watchMediaSpec: WatchMediaSpec = WatchMediaSpec.Default,
    private val googleMapsApiKeyProvider: () -> String = { "" },
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        JSONObject()
            .put("name", "show_map_directions")
            .put("description", "Create a watch map card for an exact destination. Preserve the user's requested travel mode by passing the canonical travel_mode enum. This does not open phone navigation; use open_maps_directions separately for navigation. For nearest/nearby/local requests, first use find_nearby_google_places and pass the selected place coordinates here.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("destination"),
                    properties = mapOf(
                        "destination" to stringSchema("Destination, place name, or selected place label."),
                        "destination_latitude" to numberSchema("Optional destination latitude from a prior Places or Geocoding result. Prefer this when known."),
                        "destination_longitude" to numberSchema("Optional destination longitude from a prior Places or Geocoding result. Prefer this when known."),
                        "travel_mode" to enumStringSchema("Canonical travel mode selected from the user's intent. Use WALK for walking directions or on-foot requests.", googleMapsTravelModeValues),
                    ),
                ),
            ),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        return when (name) {
            "show_map_directions" -> showDestination(
                destination = args.optString("destination"),
                destinationLatitude = args.optionalDouble("destination_latitude"),
                destinationLongitude = args.optionalDouble("destination_longitude"),
                travelMode = args.canonicalTravelMode() ?: return CompanionToolExecution(
                    JSONObject()
                        .put("status", "rejected")
                        .put("summary", "Invalid travel_mode \"${args.optString("travel_mode")}\". Use DRIVE, WALK, BICYCLE, TRANSIT, or TWO_WHEELER."),
                    finalText = "Invalid Maps travel mode.",
                ),
            )
            else -> null
        }
    }

    fun showDestination(
        destination: String,
        destinationLatitude: Double? = null,
        destinationLongitude: Double? = null,
        travelMode: GoogleMapsTravelMode = GoogleMapsTravelMode.DRIVE,
    ): CompanionToolExecution {
        val query = destination.trim()
        if (query.isBlank() && (destinationLatitude == null || destinationLongitude == null)) {
            return CompanionToolExecution(
                JSONObject()
                    .put("status", "rejected")
                    .put("summary", "I need a destination for the map."),
                finalText = "I need a destination.",
            )
        }
        return try {
            val mapsKey = googleMapsApiKeyProvider().trim()
            if (mapsKey.isBlank()) {
                return showOpenStreetMapDestination(query, destinationLatitude, destinationLongitude)
            }
            showGoogleDestination(query, mapsKey, destinationLatitude, destinationLongitude, travelMode)
        } catch (e: Exception) {
            val summary = "Map lookup failed: ${e.message ?: e.javaClass.simpleName}"
            CompanionToolExecution(
                JSONObject()
                    .put("status", "error")
                    .put("summary", summary),
                finalText = summary.take(180),
            )
        }
    }

    private fun showGoogleDestination(
        query: String,
        mapsKey: String,
        destinationLatitude: Double?,
        destinationLongitude: Double?,
        travelMode: GoogleMapsTravelMode,
    ): CompanionToolExecution {
        val place = if (destinationLatitude != null && destinationLongitude != null) {
            MapPlace(
                displayName = query.ifBlank { "Destination" },
                latitude = destinationLatitude,
                longitude = destinationLongitude,
            )
        } else {
            when (val geocode = googleGeocode(query, mapsKey)) {
                is GoogleMapLookup.Found -> geocode.place
                is GoogleMapLookup.NotFound -> return CompanionToolExecution(
                    JSONObject()
                        .put("status", "not_found")
                        .put("summary", "No Google Maps result found for $query."),
                    finalText = "No Google Maps result found for $query.",
                )
                is GoogleMapLookup.Failed -> return CompanionToolExecution(
                    JSONObject()
                        .put("status", "error")
                        .put("summary", geocode.reason),
                    finalText = geocode.reason.take(180),
                )
            }
        }
        val origin = currentAndroidLocation(context)?.let { location ->
            MapPoint(latitude = location.latitude, longitude = location.longitude)
        }
        val route = origin?.let { start ->
            googleRoute(start, place, mapsKey, travelMode).getOrElse { error("Google Routes failed: ${it.message ?: it.javaClass.simpleName}") }
        }
        val bitmap = googleStaticMap(place, origin, route, mapsKey)
        val watchImage = try {
            bitmap.toWatchImage(watchMediaSpec)
        } finally {
            bitmap.recycle()
        }
        val shortName = place.displayName.substringBefore(",").ifBlank { query }
        val summary = buildString {
            if (route != null) {
                append("${travelMode.watchLabel}: ${route.distanceText.ifBlank { "distance unknown" }}, ${route.durationText.ifBlank { "time unknown" }}.")
                route.firstStep.takeIf { it.isNotBlank() }?.let { append("\nFirst: ${it.take(70)}") }
            } else {
                append("Map: $shortName")
                if (origin == null) {
                    append("\nNo current location.")
                }
            }
        }
        return CompanionToolExecution(
            response = JSONObject()
                .put("status", "ok")
                .put("summary", summary)
                .put("provider", "google_maps_platform")
                .put("destination", query)
                .put("display_name", place.displayName)
                .put("latitude", place.latitude)
                .put("longitude", place.longitude)
                .put("has_current_location", origin != null)
                .put("travel_mode", travelMode.routesValue)
                .put("route_distance", route?.distanceText)
                .put("route_duration", route?.durationText),
            finalText = summary,
            watchImage = watchImage,
        )
    }

    private fun showOpenStreetMapDestination(
        query: String,
        destinationLatitude: Double?,
        destinationLongitude: Double?,
    ): CompanionToolExecution {
        val place = if (destinationLatitude != null && destinationLongitude != null) {
            MapPlace(
                displayName = query.ifBlank { "Destination" },
                latitude = destinationLatitude,
                longitude = destinationLongitude,
            )
        } else {
            when (val geocode = openStreetMapGeocode(query)) {
                is MapLookup.Found -> geocode.place
                MapLookup.NotFound -> return CompanionToolExecution(
                    JSONObject()
                        .put("status", "not_found")
                        .put("summary", "No OpenStreetMap result found for $query."),
                    finalText = "No OpenStreetMap result found for $query.",
                )
                is MapLookup.Failed -> return CompanionToolExecution(
                    JSONObject()
                        .put("status", "error")
                        .put("summary", geocode.reason),
                    finalText = geocode.reason.take(180),
                )
            }
        }
        val origin = currentAndroidLocation(context)?.let { location ->
            MapPoint(latitude = location.latitude, longitude = location.longitude)
        }
        val bitmap = renderOpenStreetMap(place, origin)
        val watchImage = try {
            bitmap.toWatchImage(watchMediaSpec)
        } finally {
            bitmap.recycle()
        }
        val shortName = place.displayName.substringBefore(",").ifBlank { query }
        val summary = buildString {
            append("Map: $shortName")
            if (origin == null) {
                append("\nOpenStreetMap. No current location.")
            } else {
                append("\nOpenStreetMap preview.")
            }
        }
        return CompanionToolExecution(
            response = JSONObject()
                .put("status", "ok")
                .put("summary", summary)
                .put("provider", "openstreetmap")
                .put("provider_reason", "google_maps_api_key_missing")
                .put("destination", query)
                .put("display_name", place.displayName)
                .put("latitude", place.latitude)
                .put("longitude", place.longitude)
                .put("has_current_location", origin != null),
            finalText = summary,
            watchImage = watchImage,
        )
    }

    private fun openStreetMapGeocode(query: String): MapLookup {
        if (query.isBlank()) {
            return MapLookup.Failed("OpenStreetMap needs a destination.")
        }
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=${encode(query)}"
        return try {
            val results = JSONArray(httpText(url))
            val first = results.optJSONObject(0) ?: return MapLookup.NotFound
            val latitude = first.optString("lat").toDoubleOrNull()
            val longitude = first.optString("lon").toDoubleOrNull()
            if (latitude == null || longitude == null) {
                return MapLookup.NotFound
            }
            MapLookup.Found(
                MapPlace(
                    displayName = first.optString("display_name").ifBlank { query },
                    latitude = latitude,
                    longitude = longitude,
                ),
            )
        } catch (e: Exception) {
            MapLookup.Failed("OpenStreetMap geocoding failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun renderOpenStreetMap(destination: MapPlace, origin: MapPoint?): Bitmap {
        val width = maxOf(watchMediaSpec.maxWidth, 144)
        val height = maxOf(watchMediaSpec.maxHeight, 100)
        val zoom = chooseOpenStreetMapZoom(destination, origin, width, height)
        val destinationPixel = projectWebMercator(destination.latitude, destination.longitude, zoom)
        val originPixel = origin?.let { projectWebMercator(it.latitude, it.longitude, zoom) }
        val centerX = if (originPixel != null) (originPixel.x + destinationPixel.x) / 2.0 else destinationPixel.x
        val centerY = if (originPixel != null) (originPixel.y + destinationPixel.y) / 2.0 else destinationPixel.y
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(235, 238, 236))

        val minTileX = floor((centerX - width / 2.0) / OSM_TILE_SIZE).toInt()
        val maxTileX = floor((centerX + width / 2.0) / OSM_TILE_SIZE).toInt()
        val minTileY = floor((centerY - height / 2.0) / OSM_TILE_SIZE).toInt()
        val maxTileY = floor((centerY + height / 2.0) / OSM_TILE_SIZE).toInt()
        val tileCount = 1 shl zoom
        for (tileX in minTileX..maxTileX) {
            for (tileY in minTileY..maxTileY) {
                if (tileY !in 0 until tileCount) {
                    continue
                }
                val wrappedTileX = ((tileX % tileCount) + tileCount) % tileCount
                val tile = httpBitmap("https://tile.openstreetmap.org/$zoom/$wrappedTileX/$tileY.png")
                val left = (tileX * OSM_TILE_SIZE - centerX + width / 2.0).roundToInt()
                val top = (tileY * OSM_TILE_SIZE - centerY + height / 2.0).roundToInt()
                canvas.drawBitmap(tile, left.toFloat(), top.toFloat(), null)
                tile.recycle()
            }
        }

        val destinationPoint = PointF(
            (destinationPixel.x - centerX + width / 2.0).toFloat(),
            (destinationPixel.y - centerY + height / 2.0).toFloat(),
        )
        val originPoint = originPixel?.let {
            PointF(
                (it.x - centerX + width / 2.0).toFloat(),
                (it.y - centerY + height / 2.0).toFloat(),
            )
        }
        originPoint?.let { start ->
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(17, 153, 255)
                strokeWidth = 5f
                style = Paint.Style.STROKE
            }
            canvas.drawLine(start.x, start.y, destinationPoint.x, destinationPoint.y, linePaint)
            drawMapMarker(canvas, start, Color.rgb(28, 91, 214), "Y")
        }
        drawMapMarker(canvas, destinationPoint, Color.rgb(210, 30, 30), "D")
        return bitmap
    }

    private fun chooseOpenStreetMapZoom(destination: MapPlace, origin: MapPoint?, width: Int, height: Int): Int {
        if (origin == null) {
            return 13
        }
        for (zoom in 16 downTo 3) {
            val destinationPixel = projectWebMercator(destination.latitude, destination.longitude, zoom)
            val originPixel = projectWebMercator(origin.latitude, origin.longitude, zoom)
            if (abs(destinationPixel.x - originPixel.x) <= width - 48 &&
                abs(destinationPixel.y - originPixel.y) <= height - 44
            ) {
                return zoom
            }
        }
        return 3
    }

    private fun projectWebMercator(latitude: Double, longitude: Double, zoom: Int): MapPixel {
        val clampedLatitude = latitude.coerceIn(-85.05112878, 85.05112878)
        val scale = OSM_TILE_SIZE * 2.0.pow(zoom.toDouble())
        val latitudeRadians = Math.toRadians(clampedLatitude)
        val x = (longitude + 180.0) / 360.0 * scale
        val y = (1.0 - ln(tan(latitudeRadians) + 1.0 / cos(latitudeRadians)) / PI) / 2.0 * scale
        return MapPixel(x = x, y = y)
    }

    private fun drawMapMarker(canvas: Canvas, point: PointF, color: Int, label: String) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 14f
            isFakeBoldText = true
        }
        canvas.drawCircle(point.x, point.y, 9f, fill)
        canvas.drawCircle(point.x, point.y, 9f, stroke)
        canvas.drawText(label, point.x, point.y + 5f, text)
    }

    private fun googleGeocode(query: String, mapsKey: String): GoogleMapLookup {
        val url = "https://maps.googleapis.com/maps/api/geocode/json?address=${encode(query)}&key=${encode(mapsKey)}"
        val json = JSONObject(httpText(url))
        when (val status = json.optString("status")) {
            "OK" -> Unit
            "ZERO_RESULTS" -> return GoogleMapLookup.NotFound
            "REQUEST_DENIED" -> {
                val message = json.optString("error_message").ifBlank { "check key restrictions and enable Geocoding API" }
                val hint = if (message.contains("billing", ignoreCase = true)) {
                    " The pasted key must belong to the Cloud project with billing enabled."
                } else {
                    ""
                }
                return GoogleMapLookup.Failed("Google Geocoding rejected the Maps key: ${message.take(140)}$hint")
            }
            "OVER_DAILY_LIMIT",
            "OVER_QUERY_LIMIT" -> return GoogleMapLookup.Failed("Google Geocoding quota or billing limit was hit for this Maps key.")
            "INVALID_REQUEST" -> return GoogleMapLookup.Failed("Google Geocoding could not understand the destination.")
            else -> return GoogleMapLookup.Failed("Google Geocoding returned $status: ${json.optString("error_message").take(140)}")
        }
        val first = json.optJSONArray("results")?.optJSONObject(0) ?: return GoogleMapLookup.NotFound
        val location = first.optJSONObject("geometry")?.optJSONObject("location") ?: return GoogleMapLookup.NotFound
        return GoogleMapLookup.Found(
            MapPlace(
                displayName = first.optString("formatted_address").ifBlank { query },
                latitude = location.optDouble("lat"),
                longitude = location.optDouble("lng"),
            ),
        )
    }

    private fun googleRoute(origin: MapPoint, destination: MapPlace, mapsKey: String, travelMode: GoogleMapsTravelMode): Result<MapRoute> {
        return runCatching {
            val body = JSONObject()
                .put(
                    "origin",
                    JSONObject().put(
                        "location",
                        JSONObject().put(
                            "latLng",
                            JSONObject()
                                .put("latitude", origin.latitude)
                                .put("longitude", origin.longitude),
                        ),
                    ),
                )
                .put(
                    "destination",
                    JSONObject().put(
                        "location",
                        JSONObject().put(
                            "latLng",
                            JSONObject()
                                .put("latitude", destination.latitude)
                                .put("longitude", destination.longitude),
                        ),
                    ),
                )
                .put("travelMode", travelMode.routesValue)
                .put("polylineQuality", "OVERVIEW")
                .put("polylineEncoding", "ENCODED_POLYLINE")
            val json = JSONObject(
                postJson(
                    "https://routes.googleapis.com/directions/v2:computeRoutes",
                    body,
                    mapsKey,
                    "routes.distanceMeters,routes.duration,routes.localizedValues,routes.polyline.encodedPolyline,routes.legs.steps.navigationInstruction,routes.legs.steps.localizedValues",
                ),
            )
            val route = json.optJSONArray("routes")?.optJSONObject(0)
                ?: error("Routes returned no route.")
            val steps = route.optJSONArray("legs")
                ?.optJSONObject(0)
                ?.optJSONArray("steps")
                ?: JSONArray()
            val firstStep = steps.optJSONObject(0)
                ?.optJSONObject("navigationInstruction")
                ?.optString("instructions")
                .orEmpty()
            val localized = route.optJSONObject("localizedValues") ?: JSONObject()
            MapRoute(
                encodedPolyline = route.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty(),
                distanceText = localized.optJSONObject("distance")?.optString("text").orEmpty(),
                durationText = localized.optJSONObject("duration")?.optString("text").orEmpty(),
                firstStep = firstStep,
            )
        }
    }

    private fun googleStaticMap(place: MapPlace, origin: MapPoint?, route: MapRoute?, mapsKey: String): Bitmap {
        val width = maxOf(watchMediaSpec.maxWidth, 144)
        val height = maxOf(watchMediaSpec.maxHeight, 100)
        val destination = "${place.latitude},${place.longitude}"
        val url = buildString {
            append("https://maps.googleapis.com/maps/api/staticmap")
            append("?")
            if (origin == null) {
                append("center=${encode(destination)}")
                append("&zoom=11")
            } else {
                append("visible=${encode("${origin.latitude},${origin.longitude}|$destination")}")
            }
            append("&size=${width}x${height}")
            append("&scale=2")
            append("&format=png8")
            append("&maptype=roadmap")
            if (origin != null) {
                append("&markers=${encode("color:blue|label:Y|${origin.latitude},${origin.longitude}")}")
            }
            append("&markers=${encode("color:red|label:D|$destination")}")
            route?.encodedPolyline?.takeIf { it.isNotBlank() }?.let { polyline ->
                append("&path=${encode("color:0x1199ff|weight:5|enc:$polyline")}")
            } ?: origin?.let {
                append("&path=${encode("color:0x1199ff|weight:3|${it.latitude},${it.longitude}|$destination")}")
            }
            append("&key=${encode(mapsKey)}")
        }
        return httpBitmap(url)
    }

    private fun httpText(url: String): String {
        val connection = openConnection(url)
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (responseCode !in 200..299) {
            error("HTTP $responseCode: ${body.take(180)}")
        }
        return body
    }

    private fun httpBitmap(url: String): Bitmap {
        val connection = openConnection(url)
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use { input -> input.readBytes() } ?: ByteArray(0)
        if (responseCode !in 200..299) {
            error("map image HTTP $responseCode: ${readableMapError(bytes.toString(Charsets.UTF_8))}")
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("map image was not returned: ${bytes.toString(Charsets.UTF_8).take(160)}")
    }

    private fun readableMapError(raw: String): String {
        return raw
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { raw.take(160) }
            .take(220)
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json,image/png,*/*")
        }
    }

    private fun postJson(url: String, body: JSONObject, mapsKey: String, fieldMask: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Goog-Api-Key", mapsKey)
            setRequestProperty("X-Goog-FieldMask", fieldMask)
        }
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(body.toString())
        }
        val responseCode = connection.responseCode
        val responseText = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        if (responseCode !in 200..299) {
            error("Routes HTTP $responseCode: ${responseText.take(180)}")
        }
        return responseText
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun JSONObject.optionalDouble(name: String): Double? {
        return if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null
    }

    private fun JSONObject.canonicalTravelMode(): GoogleMapsTravelMode? {
        val value = optString("travel_mode")
        if (value.isBlank()) {
            return GoogleMapsTravelMode.DRIVE
        }
        return value.toCanonicalGoogleMapsTravelMode()
    }

    private data class MapPlace(
        val displayName: String,
        val latitude: Double,
        val longitude: Double,
    )

    private data class MapPoint(
        val latitude: Double,
        val longitude: Double,
    )

    private data class MapRoute(
        val encodedPolyline: String,
        val distanceText: String,
        val durationText: String,
        val firstStep: String,
    )

    private data class MapPixel(
        val x: Double,
        val y: Double,
    )

    private sealed interface MapLookup {
        data class Found(val place: MapPlace) : MapLookup
        data object NotFound : MapLookup
        data class Failed(val reason: String) : MapLookup
    }

    private sealed interface GoogleMapLookup {
        data class Found(val place: MapPlace) : GoogleMapLookup
        data object NotFound : GoogleMapLookup
        data class Failed(val reason: String) : GoogleMapLookup
    }

    private companion object {
        private const val OSM_TILE_SIZE = 256
        private const val TIMEOUT_MS = 8_000
        private const val USER_AGENT = "BillyAssistant/0.1 Android companion"
    }
}
