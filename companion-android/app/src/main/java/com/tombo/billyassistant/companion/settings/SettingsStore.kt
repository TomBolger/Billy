package com.tombo.billyassistant.companion.settings

import android.content.Context

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): CompanionSettings {
        return CompanionSettings(
            geminiApiKey = preferences.getString(KEY_GEMINI_API_KEY, "").orEmpty(),
            googleMapsApiKey = preferences.getString(KEY_GOOGLE_MAPS_API_KEY, "").orEmpty(),
            pebbleBridgeEnabled = preferences.getBoolean(KEY_PEBBLE_BRIDGE_ENABLED, true),
            modelOverride = preferences.getString(KEY_MODEL_OVERRIDE, "").orEmpty(),
            lastWatchModel = preferences.getString(KEY_LAST_WATCH_MODEL, "").orEmpty(),
        )
    }

    fun save(settings: CompanionSettings) {
        preferences.edit()
            .putString(KEY_GEMINI_API_KEY, settings.geminiApiKey)
            .putString(KEY_GOOGLE_MAPS_API_KEY, settings.googleMapsApiKey)
            .putBoolean(KEY_PEBBLE_BRIDGE_ENABLED, settings.pebbleBridgeEnabled)
            .putString(KEY_MODEL_OVERRIDE, settings.modelOverride)
            .putString(KEY_LAST_WATCH_MODEL, settings.lastWatchModel)
            .apply()
    }

    /** The model to use for a watch request that asked for [watchModel]. */
    fun effectiveModel(watchModel: String?): String? {
        val settings = load()
        if (!watchModel.isNullOrBlank() && watchModel != settings.lastWatchModel) {
            save(settings.copy(lastWatchModel = watchModel))
        }
        return settings.modelOverride.ifBlank { watchModel }
    }

    companion object {
        private const val PREFERENCES_NAME = "billy_companion_settings"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GOOGLE_MAPS_API_KEY = "google_maps_api_key"
        private const val KEY_PEBBLE_BRIDGE_ENABLED = "pebble_bridge_enabled"
        private const val KEY_MODEL_OVERRIDE = "model_override"
        private const val KEY_LAST_WATCH_MODEL = "last_watch_model"

        /** Models offered in the picker: id to label. Keep in sync with app/src/pkjs/config.json. */
        val MODELS = listOf(
            "gemini-3.8-flash" to "Gemini 3.8 Flash (recommended)",
            "gemini-3.7-flash" to "Gemini 3.7 Flash",
            "gemini-3.5-flash" to "Gemini 3.5 Flash",
            "gemini-3.5-flash-lite" to "Gemini 3.5 Flash-Lite (cheaper)",
            "gemini-3.1-flash-lite" to "Gemini 3.1 Flash-Lite (cheapest)",
            "gemini-3.1-pro-preview" to "Gemini 3.1 Pro preview (slow)",
        )
    }
}
