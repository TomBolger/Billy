package com.tombo.billyassistant.companion.google

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tombo.billyassistant.companion.agent.tools.WatchImage
import com.tombo.billyassistant.companion.agent.tools.WatchMediaSpec
import com.tombo.billyassistant.companion.agent.tools.toWatchImage
import com.tombo.billyassistant.companion.auth.GoogleAccessTokenProvider
import com.tombo.billyassistant.companion.auth.GoogleAccessTokenResult
import com.tombo.billyassistant.companion.auth.GoogleApiScopes
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId

class GooglePhotosApiTools(
    private val tokenProvider: GoogleAccessTokenProvider,
    private val http: GoogleApiHttp = GoogleApiHttp(),
) {
    fun createPickerSession(): GooglePhotosPickerCreateResult {
        return when (val token = tokenProvider.getAccessToken(GoogleApiScopes.identity + GoogleApiScopes.PHOTOS_PICKER_READONLY)) {
            is GoogleAccessTokenResult.NeedsUserGrant -> GooglePhotosPickerCreateResult.NeedsScope(
                "Grant Google Photos Picker access in Billy Companion.",
                token.scopes,
            )
            is GoogleAccessTokenResult.Failed -> GooglePhotosPickerCreateResult.Failed(token.reason)
            is GoogleAccessTokenResult.Authorized -> when (val response = http.post(PICKER_SESSIONS_URL, token.accessToken, JSONObject())) {
                is GoogleHttpResult.Success -> {
                    val root = JSONObject(response.body)
                    val id = root.optString("id")
                    val pickerUri = root.optString("pickerUri")
                    if (id.isBlank() || pickerUri.isBlank()) {
                        GooglePhotosPickerCreateResult.Failed("Google Photos Picker returned no usable session.")
                    } else {
                        GooglePhotosPickerCreateResult.Success(
                            GooglePhotosPickerSession(
                                id = id,
                                pickerUri = pickerUri,
                                createdAtMillis = System.currentTimeMillis(),
                            ),
                            "Google Photos Picker session created. Select photos in Google Photos, then ask Billy to show the selected Google Photos.",
                        )
                    }
                }
                is GoogleHttpResult.HttpError -> GooglePhotosPickerCreateResult.Failed(
                    "Google Photos Picker HTTP ${response.responseCode}: ${response.reason}",
                )
                is GoogleHttpResult.Failed -> GooglePhotosPickerCreateResult.Failed(response.reason)
            }
        }
    }

    fun getPickerSession(sessionId: String): GooglePhotosPickerStatusResult {
        if (sessionId.isBlank()) {
            return GooglePhotosPickerStatusResult.Failed("No Google Photos Picker session is saved.")
        }
        return when (val token = tokenProvider.getAccessToken(GoogleApiScopes.identity + GoogleApiScopes.PHOTOS_PICKER_READONLY)) {
            is GoogleAccessTokenResult.NeedsUserGrant -> GooglePhotosPickerStatusResult.NeedsScope(
                "Grant Google Photos Picker access in Billy Companion.",
                token.scopes,
            )
            is GoogleAccessTokenResult.Failed -> GooglePhotosPickerStatusResult.Failed(token.reason)
            is GoogleAccessTokenResult.Authorized -> when (val response = http.get("$PICKER_SESSIONS_URL/${encode(sessionId)}", token.accessToken)) {
                is GoogleHttpResult.Success -> {
                    val root = JSONObject(response.body)
                    GooglePhotosPickerStatusResult.Success(
                        mediaItemsSet = root.optBoolean("mediaItemsSet", false),
                        summary = if (root.optBoolean("mediaItemsSet", false)) {
                            "Google Photos Picker selection is ready."
                        } else {
                            "Google Photos Picker is waiting for selected photos."
                        },
                    )
                }
                is GoogleHttpResult.HttpError -> GooglePhotosPickerStatusResult.Failed(
                    "Google Photos Picker HTTP ${response.responseCode}: ${response.reason}",
                )
                is GoogleHttpResult.Failed -> GooglePhotosPickerStatusResult.Failed(response.reason)
            }
        }
    }

    fun listPickedMediaItems(sessionId: String, maxResults: Int): GooglePhotosMediaResult {
        if (sessionId.isBlank()) {
            return GooglePhotosMediaResult.Failed("Open the Google Photos Picker in Billy Companion and select photos first.")
        }
        return when (val token = tokenProvider.getAccessToken(GoogleApiScopes.identity + GoogleApiScopes.PHOTOS_PICKER_READONLY)) {
            is GoogleAccessTokenResult.NeedsUserGrant -> GooglePhotosMediaResult.NeedsScope(
                "Grant Google Photos Picker access in Billy Companion.",
                token.scopes,
            )
            is GoogleAccessTokenResult.Failed -> GooglePhotosMediaResult.Failed(token.reason)
            is GoogleAccessTokenResult.Authorized -> {
                val status = getPickerSession(sessionId)
                if (status is GooglePhotosPickerStatusResult.Success && !status.mediaItemsSet) {
                    return GooglePhotosMediaResult.Rejected("Google Photos Picker is still waiting. Select photos in Google Photos, then ask again.")
                }
                val url = "$PICKER_MEDIA_ITEMS_URL?sessionId=${encode(sessionId)}&pageSize=${maxResults.coerceIn(1, 100)}"
                when (val response = http.get(url, token.accessToken)) {
                    is GoogleHttpResult.Success -> {
                        val items = JSONObject(response.body)
                            .optJSONArray("mediaItems")
                            .toMediaItems(source = GooglePhotosSource.PICKER)
                        GooglePhotosMediaResult.Success(
                            items = items,
                            source = GooglePhotosSource.PICKER,
                            accessToken = token.accessToken,
                            summary = if (items.isEmpty()) {
                                "Google Photos Picker returned no selected photos."
                            } else {
                                "Google Photos Picker returned ${items.size} selected photo${if (items.size == 1) "" else "s"}."
                            },
                        )
                    }
                    is GoogleHttpResult.HttpError -> GooglePhotosMediaResult.Failed(
                        "Google Photos Picker HTTP ${response.responseCode}: ${response.reason}",
                    )
                    is GoogleHttpResult.Failed -> GooglePhotosMediaResult.Failed(response.reason)
                }
            }
        }
    }

    fun searchLibrary(request: GooglePhotosLibrarySearchRequest): GooglePhotosMediaResult {
        return when (val token = tokenProvider.getAccessToken(GoogleApiScopes.identity + GoogleApiScopes.PHOTOS_LIBRARY_APP_CREATED_READONLY)) {
            is GoogleAccessTokenResult.NeedsUserGrant -> GooglePhotosMediaResult.NeedsScope(
                "Grant Google Photos Library access in Billy Companion.",
                token.scopes,
            )
            is GoogleAccessTokenResult.Failed -> GooglePhotosMediaResult.Failed(token.reason)
            is GoogleAccessTokenResult.Authorized -> {
                val body = request.toRequestBody()
                when (val response = http.post(LIBRARY_SEARCH_URL, token.accessToken, body)) {
                    is GoogleHttpResult.Success -> {
                        val items = JSONObject(response.body)
                            .optJSONArray("mediaItems")
                            .toMediaItems(source = GooglePhotosSource.LIBRARY_APP_CREATED)
                        GooglePhotosMediaResult.Success(
                            items = items,
                            source = GooglePhotosSource.LIBRARY_APP_CREATED,
                            accessToken = token.accessToken,
                            summary = if (items.isEmpty()) {
                                "Google Photos Library API returned no Billy-created matches. Google's current Library API does not expose your whole Google Photos library to third-party apps."
                            } else {
                                "Google Photos Library API returned ${items.size} Billy-created photo${if (items.size == 1) "" else "s"}."
                            },
                        )
                    }
                    is GoogleHttpResult.HttpError -> GooglePhotosMediaResult.Failed(
                        "Google Photos Library HTTP ${response.responseCode}: ${response.reason}",
                    )
                    is GoogleHttpResult.Failed -> GooglePhotosMediaResult.Failed(response.reason)
                }
            }
        }
    }

    fun watchImageFor(item: GooglePhotosMediaItem, accessToken: String, spec: WatchMediaSpec): WatchImage {
        val bitmap = downloadBitmap(item.watchContentUrl(), accessToken.takeIf { item.source == GooglePhotosSource.PICKER })
        try {
            return bitmap.toWatchImage(spec)
        } finally {
            bitmap.recycle()
        }
    }

    private fun GooglePhotosLibrarySearchRequest.toRequestBody(): JSONObject {
        val body = JSONObject().put("pageSize", maxResults.coerceIn(1, 100))
        val filters = JSONObject()
            .put("mediaTypeFilter", JSONObject().put("mediaTypes", JSONArray().put("PHOTO")))
        val categories = normalizedCategories()
        if (categories.isNotEmpty()) {
            filters.put(
                "contentFilter",
                JSONObject().put(
                    "includedContentCategories",
                    JSONArray().also { array -> categories.forEach { array.put(it) } },
                ),
            )
        }
        val start = takenAfterMillis?.toGooglePhotosDate()
        val end = takenBeforeMillis?.minus(1L)?.takeIf { it > 0L }?.toGooglePhotosDate()
        if (start != null && end != null) {
            filters.put(
                "dateFilter",
                JSONObject().put(
                    "ranges",
                    JSONArray().put(JSONObject().put("startDate", start).put("endDate", end)),
                ),
            )
            if (categories.isEmpty()) {
                body.put("orderBy", "MediaMetadata.creation_time desc")
            }
        }
        body.put("filters", filters)
        return body
    }

    private fun GooglePhotosLibrarySearchRequest.normalizedCategories(): List<String> {
        return contentCategories
            .flatMap { it.split(',', ' ') }
            .map { it.trim().uppercase() }
            .filter { it in CONTENT_CATEGORIES }
            .distinct()
            .take(10)
    }

    private fun JSONArray?.toMediaItems(source: GooglePhotosSource): List<GooglePhotosMediaItem> {
        if (this == null) {
            return emptyList()
        }
        return buildList {
            for (i in 0 until length()) {
                val item = optJSONObject(i) ?: continue
                val mediaFile = item.optJSONObject("mediaFile")
                val metadata = item.optJSONObject("mediaMetadata")
                    ?: mediaFile?.optJSONObject("mediaFileMetadata")
                val baseUrl = item.optString("baseUrl").ifBlank {
                    mediaFile?.optString("baseUrl").orEmpty()
                }
                if (baseUrl.isBlank()) {
                    continue
                }
                add(
                    GooglePhotosMediaItem(
                        id = item.optString("id"),
                        filename = item.optString("filename").ifBlank { mediaFile?.optString("filename").orEmpty() },
                        mimeType = item.optString("mimeType").ifBlank { mediaFile?.optString("mimeType").orEmpty() },
                        productUrl = item.optString("productUrl"),
                        baseUrl = baseUrl,
                        creationTime = metadata?.optString("creationTime").orEmpty(),
                        width = metadata?.optLong("width", 0L) ?: 0L,
                        height = metadata?.optLong("height", 0L) ?: 0L,
                        source = source,
                    ),
                )
            }
        }
    }

    private fun GooglePhotosMediaItem.watchContentUrl(): String {
        return if (baseUrl.contains("=")) {
            baseUrl.substringBeforeLast("=") + "=w640-h640"
        } else {
            "$baseUrl=w640-h640"
        }
    }

    private fun downloadBitmap(url: String, accessToken: String?): Bitmap {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "image/*,*/*")
            accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        return connection.inputStream.use { input ->
            BitmapFactory.decodeStream(input) ?: error("Google Photos image could not be decoded.")
        }
    }

    private fun Long.toGooglePhotosDate(): JSONObject {
        val date = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
        return JSONObject()
            .put("year", date.year)
            .put("month", date.monthValue)
            .put("day", date.dayOfMonth)
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private companion object {
        private const val TIMEOUT_MS = 12_000
        private const val PICKER_SESSIONS_URL = "https://photospicker.googleapis.com/v1/sessions"
        private const val PICKER_MEDIA_ITEMS_URL = "https://photospicker.googleapis.com/v1/mediaItems"
        private const val LIBRARY_SEARCH_URL = "https://photoslibrary.googleapis.com/v1/mediaItems:search"
        private val CONTENT_CATEGORIES = setOf(
            "NONE",
            "LANDSCAPES",
            "RECEIPTS",
            "CITYSCAPES",
            "LANDMARKS",
            "SELFIES",
            "PEOPLE",
            "PETS",
            "WEDDINGS",
            "BIRTHDAYS",
            "DOCUMENTS",
            "TRAVEL",
            "ANIMALS",
            "FOOD",
            "SPORT",
            "NIGHT",
            "PERFORMANCES",
            "WHITEBOARDS",
            "SCREENSHOTS",
            "UTILITY",
            "ARTS",
            "CRAFTS",
            "FASHION",
            "HOUSES",
            "GARDENS",
            "FLOWERS",
            "HOLIDAYS",
        )
    }
}

data class GooglePhotosLibrarySearchRequest(
    val searchText: String,
    val contentCategories: List<String>,
    val takenAfterMillis: Long?,
    val takenBeforeMillis: Long?,
    val maxResults: Int,
)

data class GooglePhotosMediaItem(
    val id: String,
    val filename: String,
    val mimeType: String,
    val productUrl: String,
    val baseUrl: String,
    val creationTime: String,
    val width: Long,
    val height: Long,
    val source: GooglePhotosSource,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("filename", filename)
            .put("mime_type", mimeType)
            .put("product_url", productUrl)
            .put("creation_time", creationTime)
            .put("width", width)
            .put("height", height)
            .put("source", source.name.lowercase())
    }
}

enum class GooglePhotosSource {
    LIBRARY_APP_CREATED,
    PICKER,
}

sealed interface GooglePhotosPickerCreateResult {
    data class Success(val session: GooglePhotosPickerSession, val summary: String) : GooglePhotosPickerCreateResult
    data class NeedsScope(val summary: String, val scopes: List<String>) : GooglePhotosPickerCreateResult
    data class Failed(val reason: String) : GooglePhotosPickerCreateResult
}

sealed interface GooglePhotosPickerStatusResult {
    data class Success(val mediaItemsSet: Boolean, val summary: String) : GooglePhotosPickerStatusResult
    data class NeedsScope(val summary: String, val scopes: List<String>) : GooglePhotosPickerStatusResult
    data class Failed(val reason: String) : GooglePhotosPickerStatusResult
}

sealed interface GooglePhotosMediaResult {
    data class Success(
        val items: List<GooglePhotosMediaItem>,
        val source: GooglePhotosSource,
        val accessToken: String,
        val summary: String,
    ) : GooglePhotosMediaResult
    data class NeedsScope(val summary: String, val scopes: List<String>) : GooglePhotosMediaResult
    data class Rejected(val reason: String) : GooglePhotosMediaResult
    data class Failed(val reason: String) : GooglePhotosMediaResult
}
