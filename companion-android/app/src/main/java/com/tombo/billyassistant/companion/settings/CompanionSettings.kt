package com.tombo.billyassistant.companion.settings

data class CompanionSettings(
    val geminiApiKey: String = "",
    val googleMapsApiKey: String = "",
    val pebbleBridgeEnabled: Boolean = true,
    /** Model chosen in Billy Companion; blank = use the one picked in the watch app's settings. */
    val modelOverride: String = "",
    /** Model the watch app last asked for, shown on the setup screen. */
    val lastWatchModel: String = "",
)
