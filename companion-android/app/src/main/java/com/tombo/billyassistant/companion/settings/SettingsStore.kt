package com.tombo.billyassistant.companion.settings

import android.content.Context

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): CompanionSettings {
        return CompanionSettings(
            geminiApiKey = preferences.getString(KEY_GEMINI_API_KEY, "").orEmpty(),
            googleMapsApiKey = preferences.getString(KEY_GOOGLE_MAPS_API_KEY, "").orEmpty(),
            pebbleBridgeEnabled = preferences.getBoolean(KEY_PEBBLE_BRIDGE_ENABLED, true),
        )
    }

    fun save(settings: CompanionSettings) {
        preferences.edit()
            .putString(KEY_GEMINI_API_KEY, settings.geminiApiKey)
            .putString(KEY_GOOGLE_MAPS_API_KEY, settings.googleMapsApiKey)
            .putBoolean(KEY_PEBBLE_BRIDGE_ENABLED, settings.pebbleBridgeEnabled)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "billy_companion_settings"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GOOGLE_MAPS_API_KEY = "google_maps_api_key"
        private const val KEY_PEBBLE_BRIDGE_ENABLED = "pebble_bridge_enabled"
    }
}
