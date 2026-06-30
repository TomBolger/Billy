package com.tombo.billyassistant.companion.agent.tools

import com.tombo.billyassistant.companion.google.GoogleKeepApiTools
import com.tombo.billyassistant.companion.google.GoogleKeepResult
import org.json.JSONArray
import org.json.JSONObject

class GoogleKeepCompanionTool(
    private val keepApiTools: GoogleKeepApiTools,
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        JSONObject()
            .put("name", "create_google_keep_note")
            .put("description", "Report that Google Keep note creation is unavailable because Google restricts Keep API access for personal OAuth.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "title" to stringSchema("Optional Keep note title."),
                        "text" to stringSchema("Keep note body text."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "list_google_keep_notes")
            .put("description", "Report that Google Keep note listing is unavailable because Google restricts Keep API access for personal OAuth.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "max_results" to integerSchema("Maximum notes to return. Defaults to 5."),
                    ),
                ),
            ),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        return when (name) {
            "create_google_keep_note" -> keepApiTools.createNote(
                title = args.optString("title"),
                text = args.optString("text"),
            ).toExecution(finalOnSuccess = true)
            "list_google_keep_notes" -> keepApiTools.listNotes(
                maxResults = args.optionalInt("max_results") ?: 5,
            ).toExecution(finalOnSuccess = true)
            else -> null
        }
    }
}

private fun GoogleKeepResult.toExecution(finalOnSuccess: Boolean = false): CompanionToolExecution {
    val response = toJson()
    return CompanionToolExecution(
        response = response,
        finalText = if (finalOnSuccess || response.optString("status") != "ok") keepWatchSummary(response, summary) else null,
    )
}

private fun GoogleKeepResult.toJson(): JSONObject {
    return when (this) {
        is GoogleKeepResult.Success -> payload
        is GoogleKeepResult.NeedsScope -> JSONObject()
            .put("status", "needs_scope")
            .put("summary", summary)
            .put("missing_scopes", JSONArray(scopes))
        is GoogleKeepResult.Rejected -> JSONObject()
            .put("status", "rejected")
            .put("summary", reason)
            .put("reason", reason)
        is GoogleKeepResult.Failed -> JSONObject()
            .put("status", "error")
            .put("summary", reason)
            .put("reason", reason)
    }
}

private fun keepWatchSummary(response: JSONObject, fallback: String): String {
    if (response.optString("status") != "ok") {
        return response.optString("summary").ifBlank { fallback }
    }
    if (response.has("note_name")) {
        return response.optString("summary").ifBlank { "Created Keep note." }
    }
    val notes = response.optJSONArray("notes") ?: JSONArray()
    if (notes.length() == 0) {
        return "No Keep notes found."
    }
    val limit = minOf(notes.length(), 5)
    val lines = mutableListOf("Keep:")
    for (i in 0 until limit) {
        val note = notes.optJSONObject(i) ?: continue
        lines += "- ${note.optString("title").ifBlank { "(untitled note)" }}"
    }
    if (notes.length() > limit) {
        lines += "- +${notes.length() - limit} more"
    }
    return lines.joinToString("\n")
}

private fun JSONObject.optionalInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}
