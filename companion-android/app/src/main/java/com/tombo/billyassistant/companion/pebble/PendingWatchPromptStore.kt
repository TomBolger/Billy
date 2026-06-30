package com.tombo.billyassistant.companion.pebble

import android.content.Context

class PendingWatchPromptStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(prompt: String) {
        preferences.edit().putString(KEY_PROMPT, prompt).apply()
    }

    fun peek(): String? {
        return preferences.getString(KEY_PROMPT, null)?.takeIf { it.isNotBlank() }
    }

    fun pop(): String? {
        val prompt = peek()
        if (prompt != null) {
            preferences.edit().remove(KEY_PROMPT).apply()
        }
        return prompt
    }

    fun clearIf(prompt: String) {
        if (peek() == prompt) {
            preferences.edit().remove(KEY_PROMPT).apply()
        }
    }

    private companion object {
        private const val PREFS = "billy_pending_watch_prompt"
        private const val KEY_PROMPT = "prompt"
    }
}
