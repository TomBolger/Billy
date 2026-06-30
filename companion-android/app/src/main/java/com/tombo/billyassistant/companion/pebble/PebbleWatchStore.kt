package com.tombo.billyassistant.companion.pebble

import android.content.Context
import io.rebble.pebblekit2.common.model.WatchIdentifier

class PebbleWatchStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveLastWatch(watch: WatchIdentifier) {
        preferences.edit().putString(KEY_LAST_WATCH, watch.value).apply()
    }

    fun lastWatch(): WatchIdentifier? {
        val value = preferences.getString(KEY_LAST_WATCH, null)?.takeIf { it.isNotBlank() } ?: return null
        val normalized = normalize(value)
        if (normalized != value) {
            preferences.edit().putString(KEY_LAST_WATCH, normalized).apply()
        }
        return WatchIdentifier(normalized)
    }

    private fun normalize(value: String): String {
        val trimmed = value.trim()
        val match = WRAPPED_VALUE.matchEntire(trimmed)
        return match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: trimmed
    }

    private companion object {
        private const val PREFS = "billy_pebble_watch"
        private const val KEY_LAST_WATCH = "last_watch"
        private val WRAPPED_VALUE = Regex("""WatchIdentifier\(value=(.*)\)""")
    }
}
