package com.tombo.billyassistant.companion.settings

data class CompanionSettings(
    val geminiApiKey: String = "",
    val googleMapsApiKey: String = "",
    val pebbleBridgeEnabled: Boolean = true,
)
