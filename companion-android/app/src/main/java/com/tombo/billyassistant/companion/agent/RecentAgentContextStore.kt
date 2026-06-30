package com.tombo.billyassistant.companion.agent

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class RecentAgentContextStore(context: Context) {
    private val preferences = context.getSharedPreferences("recent_agent_context", Context.MODE_PRIVATE)

    fun saveWebImageQuery(query: String, threadId: String? = null) {
        if (query.isBlank()) {
            return
        }
        preferences.edit()
            .putString(key(KEY_LAST_KIND, threadId), KIND_WEB_IMAGE)
            .putString(key(KEY_LAST_QUERY, threadId), query)
            .putString(KEY_LAST_KIND, KIND_WEB_IMAGE)
            .putString(KEY_LAST_QUERY, query)
            .apply()
    }

    fun lastWebImageQuery(threadId: String? = null): String? {
        if (preferences.getString(key(KEY_LAST_KIND, threadId), "") != KIND_WEB_IMAGE) {
            return null
        }
        return preferences.getString(key(KEY_LAST_QUERY, threadId), null)?.takeIf { it.isNotBlank() }
    }

    fun savePhotoContext(context: RecentPhotoContext, threadId: String? = null) {
        preferences.edit()
            .putString(key(KEY_LAST_KIND, threadId), KIND_PHOTO)
            .putString(key(KEY_LAST_PHOTO_CONTEXT, threadId), context.toJson().toString())
            .putString(KEY_LAST_KIND, KIND_PHOTO)
            .putString(KEY_LAST_PHOTO_CONTEXT, context.toJson().toString())
            .apply()
    }

    fun lastPhotoContext(threadId: String? = null, maxAgeMillis: Long = PHOTO_CONTEXT_MAX_AGE_MILLIS): RecentPhotoContext? {
        val json = if (threadId.isNullOrBlank()) {
            preferences.getString(KEY_LAST_PHOTO_CONTEXT, null)?.takeIf { it.isNotBlank() }
        } else {
            preferences.getString(key(KEY_LAST_PHOTO_CONTEXT, threadId), null)?.takeIf { it.isNotBlank() }
        } ?: return null
        val context = runCatching { RecentPhotoContext.fromJson(JSONObject(json)) }.getOrNull() ?: return null
        return context.takeIf { System.currentTimeMillis() - it.savedAtMillis <= maxAgeMillis }
    }

    fun saveTurn(prompt: String, assistantText: String, kind: String = KIND_GENERAL, threadId: String? = null) {
        if (prompt.isBlank() || assistantText.isBlank()) {
            return
        }
        val turn = RecentTurn(
            user = prompt.take(MAX_TURN_TEXT_LENGTH),
            assistant = assistantText.take(MAX_TURN_TEXT_LENGTH),
            kind = kind,
            savedAtMillis = System.currentTimeMillis(),
        )
        val updatedTurns = (recentTurns(threadId, TURN_CONTEXT_MAX_AGE_MILLIS) + turn).takeLast(MAX_TURNS)
        preferences.edit()
            .putString(key(KEY_TURNS, threadId), RecentTurn.toJsonArray(updatedTurns).toString())
            .putString(KEY_LAST_TURN_PROMPT, turn.user)
            .putString(KEY_LAST_TURN_RESPONSE, turn.assistant)
            .putString(KEY_LAST_TURN_KIND, turn.kind)
            .putLong(KEY_LAST_TURN_SAVED_AT, turn.savedAtMillis)
            .apply()
    }

    fun lastTurnSummary(maxAgeMillis: Long = TURN_CONTEXT_MAX_AGE_MILLIS): String? {
        val savedAt = preferences.getLong(KEY_LAST_TURN_SAVED_AT, 0L)
        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > maxAgeMillis) {
            return null
        }
        val prompt = preferences.getString(KEY_LAST_TURN_PROMPT, null)?.takeIf { it.isNotBlank() } ?: return null
        val response = preferences.getString(KEY_LAST_TURN_RESPONSE, null)?.takeIf { it.isNotBlank() } ?: return null
        val kind = preferences.getString(KEY_LAST_TURN_KIND, KIND_GENERAL).orEmpty()
        return "Previous Billy turn ($kind): user asked \"$prompt\"; Billy answered \"$response\"."
    }

    fun conversationContext(threadId: String? = null, maxAgeMillis: Long = TURN_CONTEXT_MAX_AGE_MILLIS): String? {
        val turns = recentTurns(threadId, maxAgeMillis)
        if (turns.isEmpty()) {
            return if (threadId.isNullOrBlank()) lastTurnSummary(maxAgeMillis) else null
        }
        return buildString {
            append("Recent Billy conversation context. Use it when the current request is a follow-up; ignore it if unrelated.\n")
            turns.forEach { turn ->
                append("User: ${turn.user}\n")
                append("Billy (${turn.kind}): ${turn.assistant}\n")
            }
        }.trim()
    }

    private fun recentTurns(threadId: String? = null, maxAgeMillis: Long): List<RecentTurn> {
        val raw = preferences.getString(key(KEY_TURNS, threadId), null)
            ?: return emptyList()
        val turns = runCatching { RecentTurn.fromJsonArray(org.json.JSONArray(raw)) }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        return turns.filter { now - it.savedAtMillis <= maxAgeMillis }.takeLast(MAX_TURNS)
    }

    private fun key(base: String, threadId: String?): String {
        val normalized = threadId?.trim().orEmpty()
        return if (normalized.isBlank()) base else "$base:$normalized"
    }

    private companion object {
        private const val KEY_LAST_KIND = "last_kind"
        private const val KEY_LAST_QUERY = "last_query"
        private const val KEY_LAST_PHOTO_CONTEXT = "last_photo_context"
        private const val KEY_TURNS = "turns"
        private const val KEY_LAST_TURN_PROMPT = "last_turn_prompt"
        private const val KEY_LAST_TURN_RESPONSE = "last_turn_response"
        private const val KEY_LAST_TURN_KIND = "last_turn_kind"
        private const val KEY_LAST_TURN_SAVED_AT = "last_turn_saved_at"
        private const val KIND_WEB_IMAGE = "web_image"
        private const val KIND_PHOTO = "photo"
        private const val KIND_GENERAL = "general"
        private const val MAX_TURN_TEXT_LENGTH = 220
        private const val MAX_TURNS = 6
        private const val PHOTO_CONTEXT_MAX_AGE_MILLIS = 30L * 60L * 1000L
        private const val TURN_CONTEXT_MAX_AGE_MILLIS = 60L * 60L * 1000L
    }
}

private data class RecentTurn(
    val user: String,
    val assistant: String,
    val kind: String,
    val savedAtMillis: Long,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("user", user)
            .put("assistant", assistant)
            .put("kind", kind)
            .put("saved_at_millis", savedAtMillis)
    }

    companion object {
        fun fromJsonArray(array: org.json.JSONArray): List<RecentTurn> {
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        RecentTurn(
                            user = item.optString("user"),
                            assistant = item.optString("assistant"),
                            kind = item.optString("kind", "general"),
                            savedAtMillis = item.optLong("saved_at_millis", 0L),
                        ),
                    )
                }
            }.filter { it.user.isNotBlank() && it.assistant.isNotBlank() && it.savedAtMillis > 0L }
        }

        fun toJsonArray(turns: List<RecentTurn>): org.json.JSONArray {
            return org.json.JSONArray().also { array -> turns.forEach { array.put(it.toJson()) } }
        }
    }
}

data class RecentPhotoContext(
    val prompt: String,
    val mediaType: String,
    val searchText: String,
    val rangeLabel: String?,
    val rangeStartMillis: Long?,
    val rangeEndMillis: Long?,
    val displayName: String,
    val photoDateMillis: Long?,
    val savedAtMillis: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("prompt", prompt)
            .put("media_type", mediaType)
            .put("search_text", searchText)
            .put("range_label", rangeLabel)
            .put("range_start_millis", rangeStartMillis)
            .put("range_end_millis", rangeEndMillis)
            .put("display_name", displayName)
            .put("photo_date_millis", photoDateMillis)
            .put("saved_at_millis", savedAtMillis)
    }

    fun humanSummary(): String {
        val date = photoDateMillis
            ?.let { PHOTO_CONTEXT_DATE_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
            .orEmpty()
        val range = rangeLabel?.takeIf { it.isNotBlank() }?.let { " for $it" }.orEmpty()
        val subject = searchText.takeIf { it.isNotBlank() }?.let { " matching \"$it\"" }.orEmpty()
        return "Previous photo request$range$subject selected ${displayName.ifBlank { "an image" }}${date.takeIf { it.isNotBlank() }?.let { " from $it" }.orEmpty()}."
    }

    companion object {
        fun fromJson(json: JSONObject): RecentPhotoContext {
            return RecentPhotoContext(
                prompt = json.optString("prompt"),
                mediaType = json.optString("media_type", "photo"),
                searchText = json.optString("search_text"),
                rangeLabel = json.optString("range_label").takeIf { it.isNotBlank() },
                rangeStartMillis = json.optionalPositiveLong("range_start_millis"),
                rangeEndMillis = json.optionalPositiveLong("range_end_millis"),
                displayName = json.optString("display_name"),
                photoDateMillis = json.optionalPositiveLong("photo_date_millis"),
                savedAtMillis = json.optionalPositiveLong("saved_at_millis") ?: System.currentTimeMillis(),
            )
        }
    }
}

private val PHOTO_CONTEXT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")

private fun JSONObject.optionalPositiveLong(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name).takeIf { it > 0L } else null
}
