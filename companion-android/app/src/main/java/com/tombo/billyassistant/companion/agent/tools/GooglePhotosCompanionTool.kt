package com.tombo.billyassistant.companion.agent.tools

import com.tombo.billyassistant.companion.google.GooglePhotosApiTools
import com.tombo.billyassistant.companion.google.GooglePhotosLibrarySearchRequest
import com.tombo.billyassistant.companion.google.GooglePhotosMediaItem
import com.tombo.billyassistant.companion.google.GooglePhotosMediaResult
import com.tombo.billyassistant.companion.google.GooglePhotosPickerStatusResult
import com.tombo.billyassistant.companion.google.GooglePhotosPickerStore
import org.json.JSONArray
import org.json.JSONObject

class GooglePhotosCompanionTool(
    private val photosApiTools: GooglePhotosApiTools,
    private val pickerStore: GooglePhotosPickerStore,
    private val watchMediaSpec: WatchMediaSpec = WatchMediaSpec.Default,
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        JSONObject()
            .put("name", "search_google_photos_library")
            .put(
                "description",
                "Experimental Google Photos Library API search. This is not the consumer Google Photos app search: current Google API rules generally return only media created by Billy. Use for explicit Google Photos API/library tests and report limits honestly.",
            )
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "search_text" to stringSchema("User's requested subject, person, place, date phrase, or object. Stored for context; the public Photos Library API does not support full semantic search."),
                        "content_categories" to stringSchema("Optional comma-separated Photos API categories. Gemini must choose supported category names explicitly, such as PETS, PEOPLE, FOOD, LANDSCAPES, TRAVEL, SCREENSHOTS."),
                        "taken_after_millis" to integerSchema("Optional lower bound for capture date/time as Unix epoch milliseconds."),
                        "taken_before_millis" to integerSchema("Optional exclusive upper bound for capture date/time as Unix epoch milliseconds."),
                        "max_results" to integerSchema("Maximum number of media items to return. Defaults to 12."),
                        "show_first" to booleanSchema("If true, attach the first returned photo to the watch."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "show_google_photos_picker_selection")
            .put(
                "description",
                "Show an item from the latest Google Photos Picker selection. Use only after the user opened the picker in Billy Companion and selected photos.",
            )
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "index" to integerSchema("Zero-based selected item index to show. Defaults to 0."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "get_google_photos_picker_status")
            .put(
                "description",
                "Report whether the latest Google Photos Picker session has selected media ready.",
            )
            .put(
                "parameters",
                objectSchema(required = emptyList(), properties = emptyMap()),
            ),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        return when (name) {
            "search_google_photos_library" -> searchLibrary(args)
            "show_google_photos_picker_selection" -> showPickerSelection(args)
            "get_google_photos_picker_status" -> pickerStatus()
            else -> null
        }
    }

    private fun searchLibrary(args: JSONObject): CompanionToolExecution {
        val request = GooglePhotosLibrarySearchRequest(
            searchText = args.optString("search_text"),
            contentCategories = args.optString("content_categories")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() },
            takenAfterMillis = args.optionalLong("taken_after_millis"),
            takenBeforeMillis = args.optionalLong("taken_before_millis"),
            maxResults = args.optionalInt("max_results") ?: DEFAULT_LIBRARY_LIMIT,
        )
        return when (val result = photosApiTools.searchLibrary(request)) {
            is GooglePhotosMediaResult.Success -> {
                val response = result.toJson()
                val item = if (args.optBoolean("show_first", true)) result.items.firstOrNull() else null
                if (item == null) {
                    CompanionToolExecution(
                        response = response,
                        finalText = result.summary,
                    )
                } else {
                    response.put("shown_photo", item.toJson())
                    CompanionToolExecution(
                        response = response,
                        finalText = item.watchSummary(prefix = "Google Photos API"),
                        watchImage = photosApiTools.watchImageFor(item, result.accessToken, watchMediaSpec),
                    )
                }
            }
            is GooglePhotosMediaResult.NeedsScope -> CompanionToolExecution(
                JSONObject()
                    .put("status", "needs_scope")
                    .put("summary", result.summary)
                    .put("missing_scopes", JSONArray(result.scopes)),
                finalText = result.summary,
            )
            is GooglePhotosMediaResult.Rejected -> CompanionToolExecution(
                JSONObject()
                    .put("status", "rejected")
                    .put("summary", result.reason),
                finalText = result.reason,
            )
            is GooglePhotosMediaResult.Failed -> CompanionToolExecution(
                JSONObject()
                    .put("status", "error")
                    .put("summary", result.reason),
                finalText = result.reason,
            )
        }
    }

    private fun showPickerSelection(args: JSONObject): CompanionToolExecution {
        val session = pickerStore.latest()
            ?: return CompanionToolExecution(
                JSONObject()
                    .put("status", "not_ready")
                    .put("summary", "Open Google Photos Picker in Billy Companion and select photos first."),
                finalText = "Open Google Photos Picker in Billy Companion and select photos first.",
            )
        return when (val result = photosApiTools.listPickedMediaItems(session.id, DEFAULT_PICKER_LIMIT)) {
            is GooglePhotosMediaResult.Success -> {
                val index = (args.optionalInt("index") ?: 0).coerceAtLeast(0)
                val item = result.items.getOrNull(index)
                    ?: return CompanionToolExecution(
                        response = result.toJson().put("status", "not_found"),
                        finalText = "That Google Photos selection item was not found.",
                    )
                CompanionToolExecution(
                    response = result.toJson().put("shown_photo", item.toJson()),
                    finalText = item.watchSummary(prefix = "Selected Google Photos"),
                    watchImage = photosApiTools.watchImageFor(item, result.accessToken, watchMediaSpec),
                )
            }
            is GooglePhotosMediaResult.NeedsScope -> CompanionToolExecution(
                JSONObject()
                    .put("status", "needs_scope")
                    .put("summary", result.summary)
                    .put("missing_scopes", JSONArray(result.scopes)),
                finalText = result.summary,
            )
            is GooglePhotosMediaResult.Rejected -> CompanionToolExecution(
                JSONObject()
                    .put("status", "not_ready")
                    .put("summary", result.reason),
                finalText = result.reason,
            )
            is GooglePhotosMediaResult.Failed -> CompanionToolExecution(
                JSONObject()
                    .put("status", "error")
                    .put("summary", result.reason),
                finalText = result.reason,
            )
        }
    }

    private fun pickerStatus(): CompanionToolExecution {
        val session = pickerStore.latest()
            ?: return CompanionToolExecution(
                JSONObject()
                    .put("status", "not_ready")
                    .put("summary", "No Google Photos Picker session is saved."),
                finalText = "No Google Photos Picker session is saved.",
            )
        return when (val status = photosApiTools.getPickerSession(session.id)) {
            is GooglePhotosPickerStatusResult.Success -> CompanionToolExecution(
                JSONObject()
                    .put("status", if (status.mediaItemsSet) "ok" else "waiting")
                    .put("summary", status.summary)
                    .put("session_id", session.id),
                finalText = status.summary,
            )
            is GooglePhotosPickerStatusResult.NeedsScope -> CompanionToolExecution(
                JSONObject()
                    .put("status", "needs_scope")
                    .put("summary", status.summary)
                    .put("missing_scopes", JSONArray(status.scopes)),
                finalText = status.summary,
            )
            is GooglePhotosPickerStatusResult.Failed -> CompanionToolExecution(
                JSONObject()
                    .put("status", "error")
                    .put("summary", status.reason),
                finalText = status.reason,
            )
        }
    }

    private fun GooglePhotosMediaResult.Success.toJson(): JSONObject {
        return JSONObject()
            .put("status", if (items.isEmpty()) "not_found" else "ok")
            .put("summary", summary)
            .put("source", source.name.lowercase())
            .put(
                "photos",
                JSONArray().also { array -> items.forEach { array.put(it.toJson()) } },
            )
            .put(
                "api_limit",
                if (source.name == "LIBRARY_APP_CREATED") {
                    "Current Google Photos Library API search is limited to app-created media. It is not full Google Photos account search."
                } else {
                    "Picker media contains only photos the user explicitly selected in Google Photos."
                },
            )
    }

    private fun GooglePhotosMediaItem.watchSummary(prefix: String): String {
        val name = filename.ifBlank { "photo" }
        val time = creationTime.takeIf { it.isNotBlank() }?.substringBefore('T').orEmpty()
        return listOf(prefix, name, time).filter { it.isNotBlank() }.joinToString("\n")
    }

    private companion object {
        private const val DEFAULT_LIBRARY_LIMIT = 12
        private const val DEFAULT_PICKER_LIMIT = 25
    }
}

private fun JSONObject.optionalLong(name: String): Long? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return when (val value = opt(name)) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }?.takeIf { it > 0L }
}

private fun JSONObject.optionalInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}
