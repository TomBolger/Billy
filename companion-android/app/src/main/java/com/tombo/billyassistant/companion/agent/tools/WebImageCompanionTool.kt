package com.tombo.billyassistant.companion.agent.tools

import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class WebImageCompanionTool(
    private val watchMediaSpec: WatchMediaSpec = WatchMediaSpec.Default,
    private val onImageShown: (String) -> Unit = {},
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        JSONObject()
            .put("name", "show_image")
            .put(
                "description",
                "Show a picture of something on the watch (from Wikipedia/Wikimedia). Use it proactively, alongside your text answer, whenever the user asks about something with a recognizable look: landmarks, places, animals, plants, famous people, artworks, buildings, vehicles, dishes. " +
                    "Skip it when the answer depends on fine detail the tiny low-color screen can't show (charts, diagrams, text, maps; use show_map for maps). Not for the user's own photos (use find_photo).",
            )
            .put(
                "parameters",
                objectSchema(
                    required = listOf("query"),
                    properties = mapOf(
                        "query" to stringSchema("The subject, as a Wikipedia article title would name it, e.g. \"Eiffel Tower\", \"Golden retriever\"."),
                    ),
                ),
            ),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        return when (name) {
            "show_image", "show_web_image_search" -> search(args.optString("query"))
            else -> null
        }
    }

    fun search(query: String): CompanionToolExecution {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return CompanionToolExecution(
                JSONObject()
                    .put("status", "rejected")
                    .put("summary", "I need a subject for the web image search."),
                finalText = "What should I search an image of?",
            )
        }
        return try {
            val result = runCatching { wikipediaLeadImage(normalizedQuery) }.getOrNull()
                ?: searchCommons(normalizedQuery)
                ?: return CompanionToolExecution(
                    JSONObject()
                        .put("status", "not_found")
                        .put("summary", "No public web image found for $normalizedQuery."),
                    finalText = "No public web image found for $normalizedQuery.",
                )
            val bitmap = httpBitmap(result.imageUrl)
            val watchImage = try {
                bitmap.toWatchImage(watchMediaSpec)
            } finally {
                bitmap.recycle()
            }
            val title = result.title.removePrefix("File:").substringBeforeLast('.').take(60)
            val summary = "Showing a picture of $normalizedQuery."
            onImageShown(normalizedQuery)
            CompanionToolExecution(
                response = JSONObject()
                    .put("status", "ok")
                    .put("summary", summary)
                    .put("query", normalizedQuery)
                    .put("title", result.title)
                    .put("source_url", result.sourceUrl)
                    .put("image_url", result.imageUrl),
                watchImage = watchImage,
            )
        } catch (e: Exception) {
            CompanionToolExecution(
                JSONObject()
                    .put("status", "error")
                    .put("summary", "Web image lookup failed: ${e.message ?: e.javaClass.simpleName}"),
                finalText = "Web image lookup failed.",
            )
        }
    }

    /** The lead image of the best-matching Wikipedia article: usually the canonical picture. */
    private fun wikipediaLeadImage(query: String): WebImageResult? {
        val url = "https://en.wikipedia.org/w/api.php?action=query&format=json&prop=pageimages&piprop=thumbnail%7Cname" +
            "&pithumbsize=480&generator=search&gsrlimit=1&gsrsearch=${encode(query)}"
        val pages = JSONObject(httpText(url)).optJSONObject("query")?.optJSONObject("pages") ?: return null
        val keys = pages.keys()
        while (keys.hasNext()) {
            val page = pages.optJSONObject(keys.next()) ?: continue
            val thumb = page.optJSONObject("thumbnail")?.optString("source").orEmpty()
            if (thumb.isBlank() || thumb.endsWith(".svg", ignoreCase = true)) continue
            return WebImageResult(
                title = page.optString("title").ifBlank { query },
                sourceUrl = "https://en.wikipedia.org/wiki/${encode(page.optString("title"))}",
                imageUrl = thumb,
            )
        }
        return null
    }

    private fun searchCommons(query: String): WebImageResult? {
        val url = "https://commons.wikimedia.org/w/api.php" +
            "?action=query&generator=search&gsrnamespace=6&gsrlimit=10&gsrsearch=${encode(query)}" +
            "&prop=imageinfo&iiprop=url%7Cmime%7Csize&iiurlwidth=640&format=json"
        val root = JSONObject(httpText(url))
        val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val keys = pages.keys()
        while (keys.hasNext()) {
            val page = pages.optJSONObject(keys.next()) ?: continue
            val imageInfo = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val mime = imageInfo.optString("mime")
            if (!mime.startsWith("image/") || mime == "image/svg+xml") {
                continue
            }
            val imageUrl = imageInfo.optString("thumburl").ifBlank { imageInfo.optString("url") }
            if (imageUrl.isBlank()) {
                continue
            }
            return WebImageResult(
                title = page.optString("title").ifBlank { query },
                sourceUrl = imageInfo.optString("descriptionurl").ifBlank { imageInfo.optString("url") },
                imageUrl = imageUrl,
            )
        }
        return null
    }

    private fun httpText(url: String): String {
        val connection = openConnection(url)
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun httpBitmap(url: String): android.graphics.Bitmap {
        val connection = openConnection(url)
        return connection.inputStream.use { input ->
            BitmapFactory.decodeStream(input) ?: error("Web image could not be decoded.")
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json,image/*,*/*")
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private data class WebImageResult(
        val title: String,
        val sourceUrl: String,
        val imageUrl: String,
    )

    private companion object {
        private const val TIMEOUT_MS = 8_000
        private const val USER_AGENT = "BillyAssistant/0.1 Android companion"
    }
}
