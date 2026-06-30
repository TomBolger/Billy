package com.tombo.billyassistant.companion.agent.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.util.Size
import com.tombo.billyassistant.companion.agent.GeminiClient
import com.tombo.billyassistant.companion.agent.GeminiImageCandidate
import com.tombo.billyassistant.companion.agent.GeminiImageChoice
import com.tombo.billyassistant.companion.media.AndroidPhotoTools
import com.tombo.billyassistant.companion.media.LocalPhotoSummary
import com.tombo.billyassistant.companion.media.LocalPhotosResult
import com.tombo.billyassistant.companion.media.PhotoAccessLevel
import com.tombo.billyassistant.companion.media.PhotoMediaPreference
import com.tombo.billyassistant.companion.media.PhotoSearchRequest
import com.tombo.billyassistant.companion.media.RecentPhotoListRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PhotoCompanionTool(
    private val context: Context,
    private val watchMediaSpec: WatchMediaSpec = WatchMediaSpec.Default,
    private val geminiClient: GeminiClient? = null,
    private val apiKeyProvider: () -> String = { "" },
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        JSONObject()
            .put("name", "list_recent_photos")
            .put("description", "List recent local or selected photos visible to this Android companion.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "max_results" to integerSchema("Maximum number of photos to return. Defaults to 5."),
                        "taken_after_millis" to integerSchema("Optional lower bound for photo capture time as Unix epoch milliseconds."),
                        "taken_before_millis" to integerSchema("Optional exclusive upper bound for photo capture time as Unix epoch milliseconds. Use with taken_after_millis for day/week/month date ranges."),
                        "media_type" to stringSchema("Which image class to prefer: photo, screenshot, or any. Defaults to photo."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "search_photos")
            .put("description", "Search local or selected Android photos. Filename/album matches are exact metadata search; person/place/subject requests should usually use attach_photo_for_analysis so Gemini can rank visible image content.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("search_text"),
                    properties = mapOf(
                        "search_text" to stringSchema("Filename, album, person, place, or subject text to search for."),
                        "max_results" to integerSchema("Maximum number of photos to return. Defaults to 5."),
                        "taken_after_millis" to integerSchema("Optional lower bound for photo capture time as Unix epoch milliseconds."),
                        "taken_before_millis" to integerSchema("Optional exclusive upper bound for photo capture time as Unix epoch milliseconds. Use with taken_after_millis for day/week/month date ranges."),
                        "media_type" to stringSchema("Which image class to prefer: photo, screenshot, or any. Defaults to search text."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "attach_photo_for_analysis")
            .put("description", "Attach the best matching local Android photo as inline image data so Gemini can answer questions or show it. For people, places, objects, pets, or subjects, pass that text so candidate camera-roll images can be visually ranked.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "search_text" to stringSchema("Optional filename, album, person, place, object, pet, or subject text. If omitted, attach the latest photo."),
                        "taken_after_millis" to integerSchema("Optional lower bound for photo capture time as Unix epoch milliseconds."),
                        "taken_before_millis" to integerSchema("Optional exclusive upper bound for photo capture time as Unix epoch milliseconds. Use with taken_after_millis for day/week/month date ranges."),
                        "media_type" to stringSchema("Which image class to attach: photo, screenshot, or any. Use photo for camera-roll/photo requests and screenshot only when the user explicitly asks for screenshots."),
                    ),
                ),
            ),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        return when (name) {
            "list_recent_photos" -> CompanionToolExecution(
                AndroidPhotoTools.listRecentPhotos(
                    context = context,
                    request = RecentPhotoListRequest(
                        takenAfterMillis = args.optionalLong("taken_after_millis"),
                        takenBeforeMillis = args.optionalLong("taken_before_millis"),
                        maxResults = args.optionalInt("max_results") ?: DEFAULT_LIST_LIMIT,
                        mediaPreference = args.mediaPreference(default = PhotoMediaPreference.CameraPhotos),
                    ),
                ).toJson(),
            )
            "search_photos" -> CompanionToolExecution(
                AndroidPhotoTools.searchPhotos(
                    context = context,
                    request = PhotoSearchRequest(
                        searchText = args.optString("search_text").trim(),
                        takenAfterMillis = args.optionalLong("taken_after_millis"),
                        takenBeforeMillis = args.optionalLong("taken_before_millis"),
                        maxResults = args.optionalInt("max_results") ?: DEFAULT_LIST_LIMIT,
                        mediaPreference = args.mediaPreference(default = PhotoMediaPreference.SearchText),
                    ),
                ).toJson(),
            )
            "attach_photo_for_analysis" -> attachPhotoForAnalysis(args)
            else -> null
        }
    }

    private fun attachPhotoForAnalysis(args: JSONObject): CompanionToolExecution {
        val searchText = args.optString("search_text").trim()
        val mediaPreference = args.mediaPreference(
            default = if (searchText.isBlank()) PhotoMediaPreference.CameraPhotos else PhotoMediaPreference.SearchText,
        )
        val candidateLimit = if (searchText.isBlank()) ATTACH_CANDIDATE_LIMIT else SEMANTIC_CANDIDATE_LIMIT
        val photosResult = if (searchText.isBlank()) {
            AndroidPhotoTools.listRecentPhotos(
                context = context,
                request = RecentPhotoListRequest(
                    takenAfterMillis = args.optionalLong("taken_after_millis"),
                    takenBeforeMillis = args.optionalLong("taken_before_millis"),
                    maxResults = candidateLimit,
                    mediaPreference = mediaPreference,
                ),
            )
        } else {
            val metadataResult = AndroidPhotoTools.searchPhotos(
                context = context,
                request = PhotoSearchRequest(
                    searchText = searchText,
                    takenAfterMillis = args.optionalLong("taken_after_millis"),
                    takenBeforeMillis = args.optionalLong("taken_before_millis"),
                    maxResults = candidateLimit,
                    mediaPreference = mediaPreference,
                ),
            )
            if (metadataResult is LocalPhotosResult.Success && metadataResult.photos.isNotEmpty()) {
                metadataResult
            } else {
                AndroidPhotoTools.listRecentPhotos(
                    context = context,
                    request = RecentPhotoListRequest(
                        takenAfterMillis = args.optionalLong("taken_after_millis"),
                        takenBeforeMillis = args.optionalLong("taken_before_millis"),
                        maxResults = candidateLimit,
                        mediaPreference = mediaPreference.semanticFallbackPreference(),
                    ),
                )
            }
        }

        if (photosResult !is LocalPhotosResult.Success) {
            return CompanionToolExecution(photosResult.toJson())
        }
        if (photosResult.accessLevel == PhotoAccessLevel.SelectedImages && mediaPreference == PhotoMediaPreference.CameraPhotos) {
            val summary = "Billy only has selected-photo access. Grant full Photos access in Android settings to use latest camera roll photos."
            return CompanionToolExecution(
                response = JSONObject()
                    .put("status", "limited_access")
                    .put("summary", summary)
                    .put("access_level", photosResult.accessLevel.name),
                finalText = summary,
            )
        }

        val photos = if (searchText.isBlank()) {
            photosResult.photos
        } else {
            rankSemanticCandidates(searchText, photosResult.photos)
        }
        photos.firstOrNull()
            ?: return CompanionToolExecution(
                JSONObject()
                    .put("status", "not_found")
                    .put("summary", if (searchText.isBlank()) "No matching photo found." else "No confident camera-roll match found for \"$searchText\"."),
                finalText = if (searchText.isBlank()) null else "I could not find a confident camera-roll match for \"$searchText\".",
            )

        val failures = mutableListOf<String>()
        photos.forEachIndexed { index, candidate ->
            try {
                val imageData = candidate.toInlineImageData()
                val label = candidate.displayName.ifBlank {
                    if (index == 0) "latest photo" else "recent photo ${index + 1}"
                }
                return CompanionToolExecution(
                    response = JSONObject()
                        .put("status", "ok")
                        .put("summary", "Attached $label for analysis.")
                        .put("photo", candidate.toJson())
                        .put("candidate_index", index),
                    followUpParts = JSONArray()
                        .put(JSONObject().put("text", "Analyze this photo for the user's watch request. Name concrete visible subjects, setting, text, colors, and anything notable. Keep it short but useful."))
                        .put(
                            JSONObject().put(
                                "inlineData",
                                JSONObject()
                                    .put("mimeType", imageData.mimeType)
                                    .put("data", imageData.base64Data),
                            ),
                        ),
                    watchImage = candidate.toWatchImage(),
                )
            } catch (e: Exception) {
                failures += "${candidate.displayName.ifBlank { candidate.contentUriString }}: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        return CompanionToolExecution(
            JSONObject()
                .put("status", "error")
                .put("summary", "Could not attach any of the ${photos.size} most recent matching photos.")
                .put("attempted_photos", JSONArray().also { array -> photos.forEach { array.put(it.toJson()) } })
                .put("failures", JSONArray(failures)),
        )
    }

    private fun LocalPhotoSummary.toWatchImage(): WatchImage {
        val bitmap = loadBitmapForWatch()
        try {
            var best: WatchImage? = null
            WATCH_SCALE_STEPS.forEach { scaleStep ->
                val maxWidth = maxOf(MIN_WATCH_IMAGE_EDGE, (watchMediaSpec.maxWidth * scaleStep).toInt())
                val maxHeight = maxOf(MIN_WATCH_IMAGE_EDGE, (watchMediaSpec.maxHeight * scaleStep).toInt())
                val preview = bitmap.resizeContain(maxWidth, maxHeight)
                try {
                    val data = preview.toPebbleBitmapData(watchMediaSpec.pbiDepth)
                    val watchImage = WatchImage(
                        width = preview.width,
                        height = preview.height,
                        data = data,
                    )
                    best = watchImage
                    if (data.size <= watchMediaSpec.maxBytes) {
                        return watchImage
                    }
                } finally {
                    preview.recycle()
                }
            }
            return best ?: error("Photo could not be resized for the watch.")
        } finally {
            bitmap.recycle()
        }
    }

    private fun LocalPhotoSummary.loadBitmapForWatch(): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(contentUri, Size(512, 512), null)
        } else {
            context.contentResolver.openInputStream(contentUri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: error("Photo stream could not be decoded.")
        }
    }

    private fun rankSemanticCandidates(searchText: String, photos: List<LocalPhotoSummary>): List<LocalPhotoSummary> {
        if (photos.size < 2 || geminiClient == null || apiKeyProvider().isBlank()) {
            return photos
        }
        val contenders = mutableListOf<Pair<Int, GeminiImageChoice>>()
        photos.take(SEMANTIC_CANDIDATE_LIMIT)
            .chunked(SEMANTIC_BATCH_SIZE)
            .forEachIndexed { batchIndex, batchPhotos ->
                val offset = batchIndex * SEMANTIC_BATCH_SIZE
                val imageCandidates = batchPhotos.mapIndexedNotNull { index, photo ->
                    runCatching {
                        val imageData = photo.toInlineImageData(thumbnailSize = 168, jpegQuality = 42)
                        GeminiImageCandidate(
                            index = offset + index,
                            label = photo.toAgentSummary(),
                            mimeType = imageData.mimeType,
                            base64Data = imageData.base64Data,
                        )
                    }.getOrNull()
                }
                val choice = geminiClient.chooseImageCandidate(
                    prompt = searchText,
                    apiKey = apiKeyProvider(),
                    candidates = imageCandidates,
                )
                if (choice != null && choice.confidence >= SEMANTIC_BATCH_MIN_CONFIDENCE) {
                    if (choice.confidence >= SEMANTIC_EARLY_STOP_CONFIDENCE) {
                        val selected = photos.getOrNull(choice.index)
                        if (selected != null) {
                            return listOf(selected) + photos.filterIndexed { index, _ -> index != choice.index }
                        }
                    }
                    contenders += choice.index to choice
                }
            }
        if (contenders.isEmpty()) {
            return emptyList()
        }

        val finalistPhotos = contenders
            .sortedByDescending { it.second.confidence }
            .take(SEMANTIC_FINALIST_LIMIT)
            .mapNotNull { (index, _) -> photos.getOrNull(index) to index }
        val finalCandidates = finalistPhotos.mapNotNull { (photo, originalIndex) ->
            photo ?: return@mapNotNull null
            runCatching {
                val imageData = photo.toInlineImageData(thumbnailSize = 256, jpegQuality = 55)
                GeminiImageCandidate(
                    index = originalIndex,
                    label = photo.toAgentSummary(),
                    mimeType = imageData.mimeType,
                    base64Data = imageData.base64Data,
                )
            }.getOrNull()
        }
        val finalChoice = if (finalCandidates.size > 1) {
            geminiClient.chooseImageCandidate(
                prompt = searchText,
                apiKey = apiKeyProvider(),
                candidates = finalCandidates,
            )
        } else {
            contenders.maxByOrNull { it.second.confidence }?.second
        }
        if (finalChoice == null || finalChoice.confidence < SEMANTIC_FINAL_MIN_CONFIDENCE) {
            return emptyList()
        }
        val selectedIndex = finalChoice.index
        val selected = photos.getOrNull(selectedIndex) ?: return emptyList()
        return listOf(selected) + photos.filterIndexed { index, _ -> index != selectedIndex }
    }

    private fun LocalPhotoSummary.toInlineImageData(
        thumbnailSize: Int = 512,
        jpegQuality: Int = 75,
    ): InlineImageData {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bitmap = context.contentResolver.loadThumbnail(contentUri, Size(thumbnailSize, thumbnailSize), null)
            val output = ByteArrayOutputStream()
            bitmap.useAfter { compress(Bitmap.CompressFormat.JPEG, jpegQuality, output) }
            return InlineImageData(
                mimeType = "image/jpeg",
                base64Data = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
            )
        }

        val data = context.contentResolver.openInputStream(contentUri)?.use { input ->
            input.readBytes(MAX_DIRECT_IMAGE_BYTES + 1)
        } ?: error("Photo stream could not be opened.")

        if (data.size > MAX_DIRECT_IMAGE_BYTES) {
            error("Photo is too large to attach on this Android version.")
        }
        return InlineImageData(
            mimeType = mimeType?.takeIf { it.startsWith("image/") } ?: "image/jpeg",
            base64Data = Base64.encodeToString(data, Base64.NO_WRAP),
        )
    }

    private companion object {
        private const val DEFAULT_LIST_LIMIT = 5
        private const val ATTACH_CANDIDATE_LIMIT = 5
        private const val SEMANTIC_CANDIDATE_LIMIT = 240
        private const val SEMANTIC_BATCH_SIZE = 16
        private const val SEMANTIC_FINALIST_LIMIT = 8
        private const val SEMANTIC_BATCH_MIN_CONFIDENCE = 24
        private const val SEMANTIC_FINAL_MIN_CONFIDENCE = 44
        private const val SEMANTIC_EARLY_STOP_CONFIDENCE = 84
        private const val MAX_DIRECT_IMAGE_BYTES = 4 * 1024 * 1024
        private const val MIN_WATCH_IMAGE_EDGE = 32
        private val WATCH_SCALE_STEPS = floatArrayOf(1f, 0.95f, 0.9f, 0.85f, 0.8f, 0.72f, 0.64f, 0.56f, 0.48f, 0.4f)
        private val PHOTO_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d h:mm a")
    }
}

private data class InlineImageData(
    val mimeType: String,
    val base64Data: String,
)

private fun LocalPhotosResult.toJson(): JSONObject {
    val base = JSONObject().put("summary", summary)
    return when (this) {
        is LocalPhotosResult.Success -> base
            .put("status", "ok")
            .put("access_level", accessLevel.name)
            .put("query_limit", queryLimit)
            .put("rows_read", rowsRead)
            .put("eligible_count", eligibleCount)
            .put(
                "photos",
                JSONArray().also { array ->
                    photos.forEach { photo -> array.put(photo.toJson()) }
                },
            )
        is LocalPhotosResult.NotAuthorized -> base
            .put("status", "not_authorized")
            .put("reason", reason)
            .put("missing_permissions", JSONArray(missingPermissions))
        is LocalPhotosResult.Rejected -> base
            .put("status", "rejected")
            .put("reason", reason)
        is LocalPhotosResult.Failed -> base
            .put("status", "error")
            .put("reason", reason)
    }
}

private fun LocalPhotoSummary.toJson(): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("content_uri", contentUriString)
        .put("display_name", displayName)
        .put("mime_type", mimeType)
        .put("width", width)
        .put("height", height)
        .put("size_bytes", sizeBytes)
        .put("date_taken_millis", dateTakenMillis)
        .put("date_added_millis", dateAddedMillis)
        .put("date_modified_millis", dateModifiedMillis)
        .put("bucket_name", bucketName)
        .put("relative_path", relativePath)
        .put("camera_source_proof", sourceProof())
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

private fun JSONObject.mediaPreference(default: PhotoMediaPreference): PhotoMediaPreference {
    val mediaType = optString("media_type").trim().lowercase()
    return when {
        mediaType.contains("screen") -> PhotoMediaPreference.Screenshots
        mediaType == "any" || mediaType == "media" || mediaType == "any image" -> PhotoMediaPreference.AnyImage
        mediaType == "image" -> PhotoMediaPreference.CameraPhotos
        mediaType == "photo" || mediaType == "camera" || mediaType == "camera_roll" || mediaType == "camera roll" -> PhotoMediaPreference.CameraPhotos
        else -> default
    }
}

private fun PhotoMediaPreference.semanticFallbackPreference(): PhotoMediaPreference {
    return when (this) {
        PhotoMediaPreference.Screenshots -> PhotoMediaPreference.Screenshots
        PhotoMediaPreference.AnyImage -> PhotoMediaPreference.AnyImage
        PhotoMediaPreference.CameraPhotos,
        PhotoMediaPreference.SearchText -> PhotoMediaPreference.CameraPhotos
    }
}

private inline fun Bitmap.useAfter(block: Bitmap.() -> Unit) {
    try {
        block()
    } finally {
        recycle()
    }
}

private fun Bitmap.resizeContain(maxWidth: Int, maxHeight: Int): Bitmap {
    val scale = minOf(maxWidth.toFloat() / width.toFloat(), maxHeight.toFloat() / height.toFloat())
    val scaledWidth = maxOf(1, (width * scale).toInt())
    val scaledHeight = maxOf(1, (height * scale).toInt())
    val output = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    1.08f, 0f, 0f, 0f, -4f,
                    0f, 1.08f, 0f, 0f, -4f,
                    0f, 0f, 1.08f, 0f, -4f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    canvas.drawBitmap(this, null, Rect(0, 0, scaledWidth, scaledHeight), paint)
    return output
}

private fun Bitmap.toPebbleBitmapData(pbiDepth: Int): ByteArray {
    return if (pbiDepth == 4) {
        toPebblePbi4()
    } else {
        toPebblePbi2()
    }
}

private fun Bitmap.toPebblePbi2(): ByteArray {
    val rowSize = (width + 3) / 4
    val dataSize = rowSize * height
    val colors = ByteArray(width * height)
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    pixels.forEachIndexed { index, pixel ->
        colors[index] = pixel.toPebbleArgb8().toByte()
    }
    val palette = intArrayOf(0xff, 0xc0, 0xea, 0xd5)
    val output = ByteArray(12 + dataSize + palette.size)
    output.writeUint16Le(0, rowSize)
    output.writeUint16Le(2, (1 shl 12) or (3 shl 1))
    output.writeUint16Le(4, 0)
    output.writeUint16Le(6, 0)
    output.writeUint16Le(8, width)
    output.writeUint16Le(10, height)
    for (y in 0 until height) {
        for (x in 0 until width step 4) {
            var packed = 0
            for (i in 0 until 4) {
                val paletteIndex = if (x + i < width) {
                    colors[(y * width) + x + i].toUnsignedInt().nearestPaletteIndex(palette)
                } else {
                    0
                }
                packed = packed or ((paletteIndex and 0x03) shl (6 - (i * 2)))
            }
            output[12 + (y * rowSize) + (x / 4)] = packed.toByte()
        }
    }
    palette.forEachIndexed { index, value ->
        output[12 + dataSize + index] = value.toByte()
    }
    return output
}

private fun Bitmap.toPebblePbi4(): ByteArray {
    val rowSize = (width + 1) / 2
    val dataSize = rowSize * height
    val colors = ByteArray(width * height)
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    pixels.forEachIndexed { index, pixel ->
        colors[index] = pixel.toPebbleArgb8().toByte()
    }
    val palette = colors.toPebblePalette16()
    val output = ByteArray(12 + dataSize + palette.size)
    output.writeUint16Le(0, rowSize)
    output.writeUint16Le(2, (1 shl 12) or (4 shl 1))
    output.writeUint16Le(4, 0)
    output.writeUint16Le(6, 0)
    output.writeUint16Le(8, width)
    output.writeUint16Le(10, height)
    for (y in 0 until height) {
        for (x in 0 until width step 2) {
            val high = colors[(y * width) + x].toUnsignedInt().nearestPaletteIndex(palette)
            val low = if (x + 1 < width) {
                colors[(y * width) + x + 1].toUnsignedInt().nearestPaletteIndex(palette)
            } else {
                0
            }
            output[12 + (y * rowSize) + (x / 2)] = ((high shl 4) or low).toByte()
        }
    }
    palette.forEachIndexed { index, value ->
        output[12 + dataSize + index] = value.toByte()
    }
    return output
}

private fun ByteArray.toPebblePalette16(): IntArray {
    val counts = LinkedHashMap<Int, Int>()
    forEach { color ->
        val key = color.toUnsignedInt()
        counts[key] = (counts[key] ?: 0) + 1
    }
    val palette = mutableListOf(0xff, 0xc0, 0xea, 0xd5)
    counts.entries.sortedByDescending { it.value }.forEach { entry ->
        if (palette.size < 16 && !palette.contains(entry.key)) {
            palette += entry.key
        }
    }
    while (palette.size < 16) {
        palette += 0
    }
    return palette.toIntArray()
}

private fun Int.toPebbleArgb8(): Int {
    val alpha = Color.alpha(this)
    val red = Color.red(this)
    val green = Color.green(this)
    val blue = Color.blue(this)
    val pebbleAlpha = if (alpha < 128) 0 else 3
    return (pebbleAlpha shl 6) or ((red / 85) shl 4) or ((green / 85) shl 2) or (blue / 85)
}

private fun Int.nearestPaletteIndex(palette: IntArray): Int {
    var bestIndex = 0
    var bestDistance = Int.MAX_VALUE
    palette.forEachIndexed { index, paletteColor ->
        val distance = pebbleArgb8Distance(this, paletteColor)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = index
            if (distance == 0) {
                return bestIndex
            }
        }
    }
    return bestIndex
}

private fun pebbleArgb8Distance(a: Int, b: Int): Int {
    val redDistance = (((a shr 4) and 3) - ((b shr 4) and 3)) * 85
    val greenDistance = (((a shr 2) and 3) - ((b shr 2) and 3)) * 85
    val blueDistance = ((a and 3) - (b and 3)) * 85
    return redDistance * redDistance + greenDistance * greenDistance + blueDistance * blueDistance
}

private fun Byte.toUnsignedInt(): Int = toInt() and 0xff

private fun ByteArray.writeUint16Le(offset: Int, value: Int) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value shr 8) and 0xff).toByte()
}

private fun java.io.InputStream.readBytes(maxBytes: Int): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(chunk)
        if (read == -1) {
            return buffer.toByteArray()
        }
        total += read
        if (total > maxBytes) {
            return buffer.toByteArray() + chunk.copyOf(read)
        }
        buffer.write(chunk, 0, read)
    }
}
