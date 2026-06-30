package com.tombo.billyassistant.companion.google

import android.content.Context

class GooglePhotosPickerStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(session: GooglePhotosPickerSession) {
        preferences.edit()
            .putString(KEY_SESSION_ID, session.id)
            .putString(KEY_PICKER_URI, session.pickerUri)
            .putLong(KEY_CREATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun latest(): GooglePhotosPickerSession? {
        val id = preferences.getString(KEY_SESSION_ID, "").orEmpty()
        val pickerUri = preferences.getString(KEY_PICKER_URI, "").orEmpty()
        if (id.isBlank() || pickerUri.isBlank()) {
            return null
        }
        return GooglePhotosPickerSession(
            id = id,
            pickerUri = pickerUri,
            createdAtMillis = preferences.getLong(KEY_CREATED_AT, 0L),
        )
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        private const val PREFERENCES_NAME = "billy_google_photos_picker"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_PICKER_URI = "picker_uri"
        private const val KEY_CREATED_AT = "created_at"
    }
}

data class GooglePhotosPickerSession(
    val id: String,
    val pickerUri: String,
    val createdAtMillis: Long,
)
