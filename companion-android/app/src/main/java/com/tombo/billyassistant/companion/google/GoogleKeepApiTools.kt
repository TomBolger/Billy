package com.tombo.billyassistant.companion.google

import com.tombo.billyassistant.companion.auth.GoogleAccessTokenProvider
import org.json.JSONObject

class GoogleKeepApiTools(
    private val tokenProvider: GoogleAccessTokenProvider,
    private val http: GoogleApiHttp = GoogleApiHttp(),
) {
    fun createNote(title: String, text: String): GoogleKeepResult {
        val cleanTitle = title.trim()
        val cleanText = text.trim()
        if (cleanTitle.isBlank() && cleanText.isBlank()) {
            return GoogleKeepResult.Rejected("Keep note title or text is required.")
        }
        return GoogleKeepResult.Rejected(KEEP_UNAVAILABLE)
    }

    fun listNotes(maxResults: Int = 5): GoogleKeepResult {
        return GoogleKeepResult.Rejected(KEEP_UNAVAILABLE)
    }

    private companion object {
        private const val KEEP_UNAVAILABLE = "Google restricts Keep API access for personal OAuth. Billy did not create a substitute note."
    }
}

sealed interface GoogleKeepResult {
    val summary: String

    data class Success(
        override val summary: String,
        val payload: JSONObject,
    ) : GoogleKeepResult

    data class NeedsScope(val scopes: List<String>) : GoogleKeepResult {
        override val summary: String = "Grant Google Keep access in the companion app."
    }

    data class Rejected(val reason: String) : GoogleKeepResult {
        override val summary: String = reason
    }

    data class Failed(val reason: String) : GoogleKeepResult {
        override val summary: String = reason
    }
}
