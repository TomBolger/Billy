package com.tombo.billyassistant.companion.agent.tools

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import com.tombo.billyassistant.companion.agent.GeminiClient
import com.tombo.billyassistant.companion.agent.GeminiImageCandidate
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * One photo tool: find a picture on the phone by when it was taken and/or
 * what is in it, show it on the watch, and let Gemini look at it.
 *
 * 1. MediaStore query for camera photos in the date window (newest first).
 * 2. If the user described the content, Gemini vision picks the best match
 *    from a grid of small thumbnails in a single call.
 * 3. The winner is sent to the watch and attached for Gemini to describe.
 */
class PhotoCompanionTool(
    private val context: Context,
    private val watchMediaSpec: WatchMediaSpec,
    private val geminiClient: GeminiClient,
    private val apiKeyProvider: () -> String,
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        JSONObject()
            .put("name", "find_photo")
            .put(
                "description",
                "Find one of the user's OWN photos from the phone's camera roll and show it on the watch. Only when the user asks for their photos; for a picture of a thing, place, or animal in general, use show_image. Use for \"show me my last photo\", \"a picture of the dog from last summer\", \"what was in the photo I took yesterday\". " +
                    "Searches photos stored on the phone (camera roll). Translate dates into an exact taken_after/taken_before window. Billy also sees the photo, so you can answer questions about it.",
            )
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "description" to stringSchema("What should be in the photo (\"my dog\", \"sunset at the beach\"). Omit for simply the latest photo."),
                        "taken_after" to stringSchema("Earliest capture time. ${TimeArgs.ISO_HELP}"),
                        "taken_before" to stringSchema("Latest capture time (exclusive)."),
                        "include_screenshots" to booleanSchema("True only if the user asks for screenshots or any image, not just camera photos."),
                        "skip" to integerSchema("For \"another one\" / \"the one before that\": how many earlier matches to skip. Default 0."),
                    ),
                ),
            ),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        if (name != "find_photo") return null
        if (!hasPermission()) {
            return CompanionToolExecution(GoogleAccess.error("Allow Photos in Billy Companion so I can see your camera roll."))
        }
        val after = TimeArgs.parse(args.optString("taken_after"))?.millis
        val before = TimeArgs.parse(args.optString("taken_before"))?.millis
        val description = args.optString("description").trim()
        val skip = args.optInt("skip", 0).coerceIn(0, 50)
        val all = query(after, before, args.optBoolean("include_screenshots", false), limit = if (description.isEmpty()) skip + 1 else MAX_SCAN)
        if (all.isEmpty()) {
            return CompanionToolExecution(notOnPhone("No photos on the phone${if (after != null || before != null) " from that time" else ""}."))
        }
        // For content searches over a long window, look at photos spread across
        // the whole window instead of only the newest ones.
        val candidates = if (description.isEmpty() || all.size <= MAX_CANDIDATES) {
            all
        } else {
            val step = all.size.toDouble() / MAX_CANDIDATES
            (0 until MAX_CANDIDATES).map { all[(it * step).toInt()] }
        }
        val chosen: Photo = if (description.isEmpty()) {
            candidates.getOrNull(skip) ?: candidates.last()
        } else {
            pickByContent(description, candidates, skip)
                ?: return CompanionToolExecution(
                    notOnPhone("I looked through ${candidates.size} photos on the phone but none clearly show $description."),
                )
        }
        val bitmap = loadBitmap(chosen.uri, 768) ?: return CompanionToolExecution(GoogleAccess.error("I couldn't open that photo."))
        return try {
            val watchImage = bitmap.toWatchImage(watchMediaSpec)
            val jpeg = bitmap.jpegBase64(85)
            val taken = Instant.ofEpochMilli(chosen.takenMillis).atZone(TimeArgs.zone())
            CompanionToolExecution(
                response = GoogleAccess.ok("Showing a photo taken ${TimeArgs.spoken(taken)}.")
                    .put("taken", taken.toOffsetDateTime().toString())
                    .put("album", chosen.album)
                    .put("watch_card", "The photo is on the watch screen. Say in a few words what it shows or answer the user's question about it."),
                followUpParts = JSONArray()
                    .put(JSONObject().put("text", "The photo Billy is showing (taken ${TimeArgs.spoken(taken)}):"))
                    .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", jpeg))),
                watchImage = watchImage,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun notOnPhone(summary: String): JSONObject = GoogleAccess.error(summary)
        .put("hint", "Photos only in Google Photos' cloud aren't on the phone. Offer open_google_photos_search to search the full library on the phone screen.")

    private fun pickByContent(description: String, candidates: List<Photo>, skip: Int): Photo? {
        val apiKey = apiKeyProvider()
        // Ask Gemini to rank in batches of 12 thumbnails, newest first.
        val matches = mutableListOf<Pair<Photo, Int>>()
        candidates.chunked(12).forEach { batch ->
            if (matches.size > skip) return@forEach // already found enough
            val images = batch.mapIndexedNotNull { index, photo ->
                val thumb = loadBitmap(photo.uri, 256) ?: return@mapIndexedNotNull null
                try {
                    val taken = Instant.ofEpochMilli(photo.takenMillis).atZone(TimeArgs.zone())
                    GeminiImageCandidate(index, "taken ${TimeArgs.spoken(taken)}", "image/jpeg", thumb.jpegBase64(70))
                } finally {
                    thumb.recycle()
                }
            }
            if (images.isEmpty()) return@forEach
            val choice = geminiClient.chooseImageCandidate(description, apiKey, images) ?: return@forEach
            if (choice.confidence >= MIN_CONFIDENCE) {
                batch.getOrNull(choice.index)?.let { matches += it to choice.confidence }
            }
        }
        return matches.getOrNull(skip)?.first ?: matches.lastOrNull()?.first
    }

    private fun query(after: Long?, before: Long?, includeScreenshots: Boolean, limit: Int): List<Photo> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) projection += MediaStore.Images.Media.RELATIVE_PATH
        val clauses = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()
        // DATE_TAKEN is millis; DATE_ADDED (seconds) stands in when a photo has no capture time.
        // Plain column comparisons only: MediaStore rejects most SQL functions.
        val taken = MediaStore.Images.Media.DATE_TAKEN
        val added = MediaStore.Images.Media.DATE_ADDED
        after?.let {
            clauses += "($taken >= ? OR (($taken IS NULL OR $taken = 0) AND $added >= ?))"
            selectionArgs += it.toString(); selectionArgs += (it / 1000).toString()
        }
        before?.let {
            clauses += "(($taken > 0 AND $taken < ?) OR (($taken IS NULL OR $taken = 0) AND $added < ?))"
            selectionArgs += it.toString(); selectionArgs += (it / 1000).toString()
        }
        val results = mutableListOf<Photo>()
        context.contentResolver.query(
            collection,
            projection.toTypedArray(),
            clauses.joinToString(" AND ").ifEmpty { null },
            selectionArgs.toTypedArray().ifEmpty { null },
            "$taken DESC, $added DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH) else -1
            while (cursor.moveToNext() && results.size < limit) {
                val bucket = cursor.getString(bucketCol).orEmpty()
                val path = if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else ""
                val name = cursor.getString(nameCol).orEmpty()
                if (!includeScreenshots && !isCameraPhoto(bucket, path, name)) continue
                val taken = cursor.getLong(takenCol).takeIf { it > 0 } ?: (cursor.getLong(addedCol) * 1000)
                results += Photo(ContentUris.withAppendedId(collection, cursor.getLong(idCol)), taken, bucket)
            }
        }
        return results
    }

    private fun isCameraPhoto(bucket: String, path: String, name: String): Boolean {
        val lower = "$bucket/$path/$name".lowercase()
        if (listOf("screenshot", "screen_recording", "whatsapp", "telegram", "download", "messenger", "signal", "facebook", "instagram").any { it in lower }) {
            return false
        }
        return path.startsWith("DCIM/", ignoreCase = true) || bucket.equals("Camera", ignoreCase = true) ||
            path.isEmpty() && bucket.isEmpty()
    }

    private fun loadBitmap(uri: Uri, edge: Int): Bitmap? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(edge, edge), null)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        }.getOrNull()
    }

    private fun hasPermission(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun Bitmap.jpegBase64(quality: Int): String {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private data class Photo(val uri: Uri, val takenMillis: Long, val album: String)

    private companion object {
        const val MAX_CANDIDATES = 48
        const val MAX_SCAN = 4000
        const val MIN_CONFIDENCE = 45
    }
}
