package com.tombo.billyassistant.companion.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers the last few turns of each watch conversation so follow-ups like
 * "delete that one" or "make it 4pm instead" work. Turns are replayed to
 * Gemini as real user/model messages, with a note of the actions taken
 * (including event ids) so the model can act on the same items again.
 */
class ConversationStore(context: Context) {
    private val prefs = context.getSharedPreferences("billy_conversations_v2", Context.MODE_PRIVATE)

    fun history(threadId: String?): JSONArray {
        val contents = JSONArray()
        if (threadId.isNullOrBlank()) return contents
        val turns = load(threadId)
        for (i in 0 until turns.length()) {
            val turn = turns.optJSONObject(i) ?: continue
            val modelText = buildString {
                append(turn.optString("model").ifBlank { "(no reply)" })
                val actions = turn.optJSONArray("actions")
                if (actions != null && actions.length() > 0) {
                    append("\n[Actions taken: ")
                    append((0 until actions.length()).joinToString("; ") { actions.optString(it) })
                    append("]")
                }
            }
            contents.put(message("user", turn.optString("user")))
            contents.put(message("model", modelText))
        }
        return contents
    }

    fun record(threadId: String?, user: String, model: String, actions: List<String>) {
        if (threadId.isNullOrBlank() || user.isBlank()) return
        val turns = load(threadId)
        turns.put(
            JSONObject()
                .put("user", user.take(MAX_TEXT))
                .put("model", model.take(MAX_TEXT))
                .put("actions", JSONArray(actions.take(8).map { it.take(300) })),
        )
        val trimmed = JSONArray()
        for (i in maxOf(0, turns.length() - MAX_TURNS) until turns.length()) trimmed.put(turns.get(i))
        val order = JSONArray(prefs.getString(KEY_ORDER, "[]"))
        val ids = (0 until order.length()).map { order.optString(it) }.filter { it != threadId } + threadId
        val editor = prefs.edit().putString(threadId, trimmed.toString())
        ids.dropLast(MAX_THREADS).forEach { editor.remove(it) }
        editor.putString(KEY_ORDER, JSONArray(ids.takeLast(MAX_THREADS)).toString()).apply()
    }

    private fun load(threadId: String): JSONArray =
        runCatching { JSONArray(prefs.getString(threadId, "[]")) }.getOrDefault(JSONArray())

    private fun message(role: String, text: String) = JSONObject()
        .put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private companion object {
        const val KEY_ORDER = "__order"
        const val MAX_TURNS = 8
        const val MAX_THREADS = 12
        const val MAX_TEXT = 1200
    }
}
