package com.tombo.billyassistant.companion.agent.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class WeatherCompanionTool(
    private val context: Context,
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        JSONObject()
            .put("name", "get_weather")
            .put("description", "Get current weather and today's forecast as a Pebble weather card. Use this for local weather, umbrella, temperature, wind, or weather-card requests. If latitude and longitude are omitted, Billy uses Android location permission.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "latitude" to numberSchema("Optional latitude for a named place. Omit for local weather."),
                        "longitude" to numberSchema("Optional longitude for a named place. Omit for local weather."),
                        "location_name" to stringSchema("Optional short place label for the weather card."),
                    ),
                ),
            ),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        return when (name) {
            "get_weather" -> getWeather(args)
            else -> null
        }
    }

    private fun getWeather(args: JSONObject): CompanionToolExecution {
        val providedLatitude = args.optionalDouble("latitude")
        val providedLongitude = args.optionalDouble("longitude")
        val location = if (providedLatitude != null && providedLongitude != null) {
            WeatherLocation(
                latitude = providedLatitude,
                longitude = providedLongitude,
                label = args.optString("location_name").ifBlank { "Weather" },
            )
        } else {
            if (!hasForegroundLocationPermission()) {
                val summary = "Grant Location in Billy Companion under Android access to use local weather."
                return CompanionToolExecution(
                    JSONObject()
                        .put("status", "needs_permission")
                        .put("summary", summary),
                    finalText = summary,
                )
            }
            if (!hasBackgroundLocationPermission()) {
                val summary = "Set Billy Companion Location to Allow all the time so weather works while your phone is locked."
                return CompanionToolExecution(
                    JSONObject()
                        .put("status", "needs_permission")
                        .put("summary", summary),
                    finalText = summary,
                )
            }
            val androidLocation = currentAndroidLocation()
                ?: return CompanionToolExecution(
                    JSONObject()
                        .put("status", "no_location")
                        .put("summary", "Android has not provided Billy a recent location yet. Check Location permission and phone location services."),
                    finalText = "I need a recent Android location. Check Location permission and phone location services.",
                )
            WeatherLocation(
                latitude = androidLocation.latitude,
                longitude = androidLocation.longitude,
                label = args.optString("location_name").ifBlank { reverseGeocodeLabel(androidLocation) ?: "Local" },
            )
        }

        return try {
            val report = fetchOpenMeteo(location)
            CompanionToolExecution(
                response = report.toJson(),
                finalText = report.summary,
                watchWeatherCurrent = WatchWeatherCurrent(
                    temperature = report.temperature,
                    feelsLike = report.feelsLike,
                    location = report.locationLabel,
                    description = report.cardDescription,
                    tempUnit = report.tempUnit,
                    windSpeed = report.windSpeed,
                    windSpeedUnit = report.windSpeedUnit,
                    condition = report.condition,
                ),
            )
        } catch (e: Exception) {
            val summary = "Weather lookup failed: ${e.message ?: e.javaClass.simpleName}"
            CompanionToolExecution(
                JSONObject()
                    .put("status", "error")
                    .put("summary", summary),
                finalText = summary.take(180),
            )
        }
    }

    private fun fetchOpenMeteo(location: WeatherLocation): WeatherReport {
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=${location.latitude}")
            append("&longitude=${location.longitude}")
            append("&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m")
            append("&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max")
            append("&forecast_days=1&timezone=auto")
            append("&temperature_unit=${temperatureUnitParameter()}")
            append("&wind_speed_unit=${windSpeedUnitParameter()}")
        }
        val json = JSONObject(httpText(url))
        val current = json.getJSONObject("current")
        val daily = json.optJSONObject("daily")
        val weatherCode = current.optInt("weather_code", daily?.arrayInt("weather_code") ?: 0)
        val condition = weatherCondition(weatherCode)
        val description = weatherDescription(weatherCode)
        val tempUnit = if (useFahrenheit()) "°F" else "°C"
        val windUnit = if (useFahrenheit()) "mph" else "km/h"
        val temperature = current.optDouble("temperature_2m").roundToInt()
        val feelsLike = current.optDouble("apparent_temperature").roundToInt()
        val windSpeed = current.optDouble("wind_speed_10m").roundToInt()
        val high = daily?.arrayDouble("temperature_2m_max")?.roundToInt()
        val low = daily?.arrayDouble("temperature_2m_min")?.roundToInt()
        val rainChance = daily?.arrayDouble("precipitation_probability_max")?.roundToInt()
        val summary = humanWeatherSummary(
            description = description,
            temperature = temperature,
            feelsLike = feelsLike,
            tempUnit = tempUnit,
            windSpeed = windSpeed,
            windUnit = windUnit,
            high = high,
            low = low,
            rainChance = rainChance,
        )
        return WeatherReport(
            latitude = location.latitude,
            longitude = location.longitude,
            locationLabel = location.label.weatherCardLabel(),
            temperature = temperature,
            feelsLike = feelsLike,
            tempUnit = tempUnit,
            windSpeed = windSpeed,
            windSpeedUnit = windUnit,
            condition = condition,
            cardDescription = description,
            summary = summary,
            high = high,
            low = low,
            rainChance = rainChance,
        )
    }

    private fun humanWeatherSummary(
        description: String,
        @Suppress("UNUSED_PARAMETER") temperature: Int,
        @Suppress("UNUSED_PARAMETER") feelsLike: Int,
        tempUnit: String,
        windSpeed: Int,
        windUnit: String,
        high: Int?,
        low: Int?,
        rainChance: Int?,
    ): String {
        val range = when {
            high != null && low != null -> "Today: high $high$tempUnit, low $low$tempUnit."
            high != null -> "Today: high near $high$tempUnit."
            low != null -> "Tonight: low near $low$tempUnit."
            else -> "Forecast: ${description.lowercase(Locale.US)}."
        }
        val rain = when {
            rainChance == null -> ""
            rainChance >= 50 -> " Rain is likely."
            rainChance >= 25 -> " Some rain risk."
            else -> " Rain is unlikely."
        }
        val wind = when {
            windSpeed >= if (useFahrenheit()) 25 else 40 -> " Strong wind: $windSpeed $windUnit."
            windSpeed >= if (useFahrenheit()) 12 else 20 -> " Breezy: $windSpeed $windUnit."
            else -> " Light wind."
        }
        return "$range$rain$wind ${description.takeIf { it.isNotBlank() } ?: "Weather"} overall."
    }

    private fun hasForegroundLocationPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun currentAndroidLocation(): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val lastKnown = manager.getProviders(true)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < RECENT_LOCATION_MS) {
            return lastKnown
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return lastKnown
        }
        val provider = when {
            runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) -> LocationManager.NETWORK_PROVIDER
            runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) -> LocationManager.GPS_PROVIDER
            else -> null
        } ?: return lastKnown

        val latch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var current: Location? = null
        val cancellation = CancellationSignal()
        try {
            manager.getCurrentLocation(provider, cancellation, executor) { location ->
                current = location
                latch.countDown()
            }
            latch.await(CURRENT_LOCATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: SecurityException) {
            return lastKnown
        } finally {
            cancellation.cancel()
            executor.shutdownNow()
        }
        return current ?: lastKnown
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocodeLabel(location: Location): String? {
        return runCatching {
            val address = Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
            listOf(address?.locality, address?.subAdminArea, address?.adminArea)
                .firstOrNull { !it.isNullOrBlank() }
        }.getOrNull()
    }

    private fun httpText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "BillyAssistant/0.1 Android companion")
        }
        val responseCode = connection.responseCode
        val body = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        if (responseCode !in 200..299) {
            error("HTTP $responseCode: ${body.take(160)}")
        }
        return body
    }

    private fun weatherCondition(code: Int): Int {
        return when (code) {
            0 -> WEATHER_CONDITION_SUN
            1, 2 -> WEATHER_CONDITION_PARTLY_CLOUDY
            3, 45, 48 -> WEATHER_CONDITION_CLOUDY_DAY
            51, 53, 55, 56, 57, 61, 80 -> WEATHER_CONDITION_LIGHT_RAIN
            63, 65, 66, 67, 81, 82, 95, 96, 99 -> WEATHER_CONDITION_HEAVY_RAIN
            71, 73, 77, 85 -> WEATHER_CONDITION_LIGHT_SNOW
            75, 86 -> WEATHER_CONDITION_HEAVY_SNOW
            else -> WEATHER_CONDITION_WEATHER_ICON
        }
    }

    private fun weatherDescription(code: Int): String {
        return when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Cloudy"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61 -> "Light rain"
            63 -> "Rain"
            65 -> "Heavy rain"
            66, 67 -> "Freezing rain"
            71, 73 -> "Snow"
            75, 86 -> "Heavy snow"
            77 -> "Snow grains"
            80 -> "Showers"
            81, 82 -> "Heavy showers"
            85 -> "Snow showers"
            95, 96, 99 -> "Thunderstorms"
            else -> "Weather"
        }
    }

    private fun useFahrenheit(): Boolean {
        return Locale.getDefault().country.equals("US", ignoreCase = true)
    }

    private fun temperatureUnitParameter(): String {
        return if (useFahrenheit()) "fahrenheit" else "celsius"
    }

    private fun windSpeedUnitParameter(): String {
        return if (useFahrenheit()) "mph" else "kmh"
    }

    private fun String.weatherCardLabel(): String {
        return trim()
            .ifBlank { "Local" }
            .replace(Regex("\\s+"), " ")
            .take(28)
    }

    private fun JSONObject.optionalDouble(name: String): Double? {
        return if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null
    }

    private fun JSONObject.arrayDouble(name: String): Double? {
        val value = optJSONArray(name)?.optDouble(0) ?: return null
        return value.takeIf { !it.isNaN() }
    }

    private fun JSONObject.arrayInt(name: String): Int? {
        return optJSONArray(name)?.optInt(0)
    }

    private data class WeatherLocation(
        val latitude: Double,
        val longitude: Double,
        val label: String,
    )

    private data class WeatherReport(
        val latitude: Double,
        val longitude: Double,
        val locationLabel: String,
        val temperature: Int,
        val feelsLike: Int,
        val tempUnit: String,
        val windSpeed: Int,
        val windSpeedUnit: String,
        val condition: Int,
        val cardDescription: String,
        val summary: String,
        val high: Int?,
        val low: Int?,
        val rainChance: Int?,
    ) {
        fun toJson(): JSONObject {
            return JSONObject()
                .put("status", "ok")
                .put("summary", summary)
                .put("latitude", latitude)
                .put("longitude", longitude)
                .put("location", locationLabel)
                .put("temperature", temperature)
                .put("feels_like", feelsLike)
                .put("temperature_unit", tempUnit)
                .put("wind_speed", windSpeed)
                .put("wind_speed_unit", windSpeedUnit)
                .put("condition", condition)
                .put("description", cardDescription)
                .put("high", high)
                .put("low", low)
                .put("rain_chance", rainChance)
        }
    }

    private companion object {
        private const val TIMEOUT_MS = 8_000
        private const val CURRENT_LOCATION_TIMEOUT_MS = 2_500L
        private const val RECENT_LOCATION_MS = 30L * 60L * 1000L

        private const val WEATHER_CONDITION_LIGHT_RAIN = 1
        private const val WEATHER_CONDITION_HEAVY_RAIN = 2
        private const val WEATHER_CONDITION_LIGHT_SNOW = 3
        private const val WEATHER_CONDITION_HEAVY_SNOW = 4
        private const val WEATHER_CONDITION_CLOUDY_DAY = 5
        private const val WEATHER_CONDITION_WEATHER_ICON = 6
        private const val WEATHER_CONDITION_PARTLY_CLOUDY = 7
        private const val WEATHER_CONDITION_SUN = 8
    }
}
