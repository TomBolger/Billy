package com.tombo.billyassistant.companion.auth

import android.content.Context

class GoogleAuthStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun saveGrant(scopes: Collection<String>, accessToken: String?) {
        val updatedScopes = grantedScopes().toMutableSet().also { it.addAll(scopes) }
        preferences.edit()
            .putStringSet(KEY_GRANTED_SCOPES, updatedScopes)
            .putString(KEY_LAST_ACCESS_TOKEN, accessToken.orEmpty())
            .putLong(KEY_LAST_AUTH_TIME, System.currentTimeMillis())
            .apply()
    }

    fun grantedScopes(): Set<String> {
        return preferences.getStringSet(KEY_GRANTED_SCOPES, emptySet()).orEmpty()
    }

    fun hasScopes(scopes: Collection<String>): Boolean {
        return grantedScopes().containsAll(scopes)
    }

    fun lastToken(): String {
        return preferences.getString(KEY_LAST_ACCESS_TOKEN, "").orEmpty()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "billy_google_auth_v2"
        private const val KEY_GRANTED_SCOPES = "granted_scopes"
        private const val KEY_LAST_ACCESS_TOKEN = "last_access_token"
        private const val KEY_LAST_AUTH_TIME = "last_auth_time"
    }
}
