package com.tombo.billyassistant.companion.google

import java.util.Locale

enum class GoogleMapsTravelMode(
    val routesValue: String,
    val androidNavigationMode: String?,
    val watchLabel: String,
) {
    DRIVE("DRIVE", "d", "Driving"),
    WALK("WALK", "w", "Walking"),
    BICYCLE("BICYCLE", "b", "Bicycling"),
    TRANSIT("TRANSIT", null, "Transit"),
    TWO_WHEELER("TWO_WHEELER", "l", "Two-wheeler"),
}

val googleMapsTravelModeValues: List<String> = GoogleMapsTravelMode.entries.map { it.routesValue }

fun String?.toCanonicalGoogleMapsTravelMode(): GoogleMapsTravelMode? {
    val value = this?.trim()?.uppercase(Locale.US).orEmpty()
    if (value.isBlank()) {
        return null
    }
    return GoogleMapsTravelMode.entries.firstOrNull { it.routesValue == value }
}
