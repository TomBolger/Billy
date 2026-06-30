package com.tombo.billyassistant.companion.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore

private const val DEFAULT_MAX_RESULTS = 20
private const val MAX_RESULTS_LIMIT = 1_200

object AndroidPhotoTools {
    fun imageReadPermissionsForRuntimeRequest(): List<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
            )
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun checkImageReadAccess(context: Context): PhotoPermissionStatus {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                context.hasPermission(Manifest.permission.READ_MEDIA_IMAGES) -> {
                PhotoPermissionStatus.Authorized(PhotoAccessLevel.FullImages)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                context.hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> {
                PhotoPermissionStatus.Authorized(PhotoAccessLevel.SelectedImages)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.hasPermission(Manifest.permission.READ_MEDIA_IMAGES) -> {
                PhotoPermissionStatus.Authorized(PhotoAccessLevel.FullImages)
            }
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                context.hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE) -> {
                PhotoPermissionStatus.Authorized(PhotoAccessLevel.LegacyExternalStorage)
            }
            else -> PhotoPermissionStatus.NotAuthorized(
                missingPermissions = imageReadPermissionsForRuntimeRequest(),
            )
        }
    }

    fun listRecentPhotos(
        context: Context,
        request: RecentPhotoListRequest = RecentPhotoListRequest(),
    ): LocalPhotosResult {
        val boundedRequest = request.normalized()
        val permissionStatus = checkImageReadAccess(context)
        if (permissionStatus is PhotoPermissionStatus.NotAuthorized) {
            return LocalPhotosResult.NotAuthorized(
                missingPermissions = permissionStatus.missingPermissions,
                reason = permissionStatus.reason,
            )
        }

        return queryPhotos(
            context = context,
            request = PhotoQueryRequest(
                searchText = null,
                takenAfterMillis = boundedRequest.takenAfterMillis,
                takenBeforeMillis = boundedRequest.takenBeforeMillis,
                maxResults = boundedRequest.maxResults,
                mimeTypes = boundedRequest.mimeTypes,
                mediaPreference = boundedRequest.mediaPreference,
            ),
            accessLevel = (permissionStatus as PhotoPermissionStatus.Authorized).accessLevel,
        )
    }

    fun searchPhotos(context: Context, request: PhotoSearchRequest): LocalPhotosResult {
        val boundedRequest = request.normalized()
        if (boundedRequest.searchText.isBlank()) {
            return LocalPhotosResult.Rejected("Photo search text is blank.")
        }

        val permissionStatus = checkImageReadAccess(context)
        if (permissionStatus is PhotoPermissionStatus.NotAuthorized) {
            return LocalPhotosResult.NotAuthorized(
                missingPermissions = permissionStatus.missingPermissions,
                reason = permissionStatus.reason,
            )
        }

        return queryPhotos(
            context = context,
            request = PhotoQueryRequest(
                searchText = boundedRequest.searchText,
                takenAfterMillis = boundedRequest.takenAfterMillis,
                takenBeforeMillis = boundedRequest.takenBeforeMillis,
                maxResults = boundedRequest.maxResults,
                mimeTypes = boundedRequest.mimeTypes,
                mediaPreference = boundedRequest.mediaPreference,
            ),
            accessLevel = (permissionStatus as PhotoPermissionStatus.Authorized).accessLevel,
        )
    }

    private fun queryPhotos(
        context: Context,
        request: PhotoQueryRequest,
        accessLevel: PhotoAccessLevel,
    ): LocalPhotosResult {
        return try {
            val selection = buildSelection(request)
            val queryMultiplier = when (request.mediaPreference) {
                PhotoMediaPreference.CameraPhotos -> 64
                PhotoMediaPreference.SearchText -> 8
                PhotoMediaPreference.Screenshots,
                PhotoMediaPreference.AnyImage -> 4
            }
            val queryLimit = minOf(maxOf(request.maxResults * queryMultiplier, request.maxResults), MAX_RESULTS_LIMIT)
            val photos = linkedMapOf<Long, LocalPhotoSummary>()
            request.mediaPreference.sortColumnSets().forEach { sortColumns ->
                readPhotoRows(context, selection, sortColumns, queryLimit).forEach { photo ->
                    photos.putIfAbsent(photo.id, photo)
                }
            }

            val eligiblePhotos = if (request.mediaPreference == PhotoMediaPreference.CameraPhotos) {
                photos.values.filter { it.isLikelyCameraPhoto() }
            } else {
                photos.values.toList()
            }
            val rankedPhotos = eligiblePhotos
                .sortedWith(request.mediaPreference.comparator(request.searchText))
                .take(request.maxResults)
            LocalPhotosResult.Success(
                photos = rankedPhotos,
                accessLevel = accessLevel,
                queryLimit = queryLimit,
                rowsRead = photos.size,
                eligibleCount = eligiblePhotos.size,
                summary = when (rankedPhotos.size) {
                    0 -> "No local photos found."
                    1 -> "Found 1 local photo: ${rankedPhotos.first().toAgentSummary()}"
                    else -> "Found ${rankedPhotos.size} local photos."
                },
            )
        } catch (e: SecurityException) {
            LocalPhotosResult.NotAuthorized(
                missingPermissions = imageReadPermissionsForRuntimeRequest(),
                reason = "Photo read permission was denied by Android.",
            )
        } catch (e: Exception) {
            LocalPhotosResult.Failed("Photo lookup failed: ${e.shortMessage()}")
        }
    }

    private fun readPhotoRows(
        context: Context,
        selection: PhotoSelection,
        sortColumns: Array<String>,
        queryLimit: Int,
    ): List<LocalPhotoSummary> {
        val queryArgs = Bundle().apply {
            selection.sql?.let { sql ->
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sql)
                putStringArray(
                    android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    selection.args.toTypedArray(),
                )
            }
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, sortColumns)
            putInt(
                android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
            )
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, queryLimit)
        }
        val photos = mutableListOf<LocalPhotoSummary>()
        context.contentResolver.query(photoCollectionUri(), projection(), queryArgs, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val relativePathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val contentUri = ContentUris.withAppendedId(photoCollectionUri(), id)
                photos += LocalPhotoSummary(
                    id = id,
                    contentUri = contentUri,
                    displayName = cursor.getStringOrBlank(displayNameColumn),
                    mimeType = cursor.getStringOrNull(mimeTypeColumn),
                    width = cursor.getIntOrNull(widthColumn),
                    height = cursor.getIntOrNull(heightColumn),
                    sizeBytes = cursor.getLongOrNull(sizeColumn),
                    dateTakenMillis = cursor.getLongOrNull(dateTakenColumn).normalizedEpochMillis(),
                    dateAddedMillis = cursor.getLongOrNull(dateAddedColumn).normalizedEpochMillis(),
                    dateModifiedMillis = cursor.getLongOrNull(dateModifiedColumn).normalizedEpochMillis(),
                    bucketName = cursor.getStringOrNull(bucketNameColumn),
                    relativePath = if (relativePathColumn >= 0) {
                        cursor.getStringOrNull(relativePathColumn)
                    } else {
                        null
                    },
                )
            }
        }
        return photos
    }

    private fun buildSelection(request: PhotoQueryRequest): PhotoSelection {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (request.takenAfterMillis != null || request.takenBeforeMillis != null) {
            clauses += buildTakenRangeClause(
                takenAfterMillis = request.takenAfterMillis,
                takenBeforeMillis = request.takenBeforeMillis,
                args = args,
            )
        }

        if (request.mimeTypes.isNotEmpty()) {
            clauses += request.mimeTypes.joinToString(
                prefix = "${MediaStore.Images.Media.MIME_TYPE} IN (",
                postfix = ")",
                separator = ", ",
            ) { "?" }
            args += request.mimeTypes
        }

        if (request.mediaPreference == PhotoMediaPreference.CameraPhotos) {
            val cameraClauses = mutableListOf<String>()
            cameraClauses += "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} IN (?, ?, ?, ?, ?)"
            args += listOf("Camera", "Open Camera", "Pixel Camera", "100MEDIA", "100ANDRO")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cameraClauses += "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                cameraClauses += "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                cameraClauses += "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                cameraClauses += "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                cameraClauses += "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                args += listOf(
                    "DCIM/Camera/%",
                    "DCIM/OpenCamera/%",
                    "DCIM/Open Camera/%",
                    "DCIM/100MEDIA/%",
                    "DCIM/100ANDRO/%",
                )
            }
            clauses += "(${cameraClauses.joinToString(separator = " OR ")})"
        }

        request.searchText?.takeIf { it.isNotBlank() }?.let { searchText ->
            clauses += "(" +
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? ESCAPE '\\' OR " +
                "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ? ESCAPE '\\'" +
                ")"
            val likeArg = "%${searchText.escapeLikeWildcards()}%"
            args += likeArg
            args += likeArg
        }

        return PhotoSelection(
            sql = clauses.takeIf { it.isNotEmpty() }?.joinToString(separator = " AND "),
            args = args,
        )
    }

    private fun photoCollectionUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun projection(): Array<String> {
        val columns = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            columns += MediaStore.MediaColumns.RELATIVE_PATH
        }
        return columns.toTypedArray()
    }

}

data class RecentPhotoListRequest(
    val takenAfterMillis: Long? = null,
    val takenBeforeMillis: Long? = null,
    val maxResults: Int = DEFAULT_MAX_RESULTS,
    val mimeTypes: Set<String> = emptySet(),
    val mediaPreference: PhotoMediaPreference = PhotoMediaPreference.CameraPhotos,
) {
    fun normalized(): RecentPhotoListRequest {
        return copy(
            maxResults = maxResults.coerceIn(1, MAX_RESULTS_LIMIT),
            mimeTypes = mimeTypes.normalizedMimeTypes(),
        )
    }
}

data class PhotoSearchRequest(
    val searchText: String,
    val takenAfterMillis: Long? = null,
    val takenBeforeMillis: Long? = null,
    val maxResults: Int = DEFAULT_MAX_RESULTS,
    val mimeTypes: Set<String> = emptySet(),
    val mediaPreference: PhotoMediaPreference = PhotoMediaPreference.SearchText,
) {
    fun normalized(): PhotoSearchRequest {
        return copy(
            searchText = searchText.trim(),
            maxResults = maxResults.coerceIn(1, MAX_RESULTS_LIMIT),
            mimeTypes = mimeTypes.normalizedMimeTypes(),
        )
    }
}

data class LocalPhotoSummary(
    val id: Long,
    val contentUri: Uri,
    val displayName: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val sizeBytes: Long?,
    val dateTakenMillis: Long?,
    val dateAddedMillis: Long?,
    val dateModifiedMillis: Long?,
    val bucketName: String?,
    val relativePath: String?,
) {
    val contentUriString: String = contentUri.toString()

    fun toAgentSummary(): String {
        val dimensions = if (width != null && height != null) " ${width}x$height" else ""
        val mime = mimeType?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        val bucket = bucketName?.takeIf { it.isNotBlank() }?.let { " in $it" }.orEmpty()
        return "$displayName$dimensions$mime$bucket uri=$contentUriString"
    }

    fun bestSortMillis(): Long {
        return maxOf(dateTakenMillis ?: 0L, dateAddedMillis ?: 0L, dateModifiedMillis ?: 0L)
    }

    fun captureSortMillis(): Long {
        return dateTakenMillis?.takeIf { it > 0L }
            ?: dateAddedMillis?.takeIf { it > 0L }
            ?: dateModifiedMillis?.takeIf { it > 0L }
            ?: 0L
    }

    fun isLikelyScreenshot(): Boolean {
        val text = searchableText()
        return SCREENSHOT_MARKERS.any { marker -> text.contains(marker) }
    }

    fun isLikelyAppMedia(): Boolean {
        val text = searchableText()
        return APP_MEDIA_MARKERS.any { marker -> text.contains(marker) } ||
            APP_MEDIA_FILENAME_PATTERNS.any { pattern -> pattern.containsMatchIn(displayName) }
    }

    fun isLikelyCameraPhoto(): Boolean {
        if (isLikelyScreenshot()) {
            return false
        }
        if (isLikelyAppMedia()) {
            return false
        }
        return hasStrongCameraLocation()
    }

    fun cameraPreferenceRank(): Int {
        return when {
            isLikelyScreenshot() -> 0
            hasStrongCameraLocation() -> 4
            isLikelyAppMedia() -> 1
            else -> 0
        }
    }

    fun sourceProof(): String {
        val bucket = bucketName.orEmpty()
        val path = relativePath.orEmpty()
        return when {
            path.isNotBlank() -> "relative_path=$path"
            bucket.isNotBlank() -> "bucket=$bucket"
            else -> "no_source_metadata"
        }
    }

    private fun hasStrongCameraLocation(): Boolean {
        val text = searchableText()
        val bucket = bucketName.orEmpty().lowercase()
        val path = relativePath.orEmpty().lowercase()
        return CAMERA_LOCATION_MARKERS.any { marker -> text.contains(marker) } ||
            bucket == "camera" ||
            bucket == "open camera" ||
            path.endsWith("dcim/camera/") ||
            path.endsWith("dcim\\camera\\")
    }

    private fun searchableText(): String {
        return listOf(displayName, bucketName, relativePath)
            .filterNotNull()
            .joinToString(separator = " ")
            .lowercase()
    }
}

sealed interface LocalPhotosResult {
    val summary: String

    data class Success(
        val photos: List<LocalPhotoSummary>,
        val accessLevel: PhotoAccessLevel,
        val queryLimit: Int = photos.size,
        val rowsRead: Int = photos.size,
        val eligibleCount: Int = photos.size,
        override val summary: String,
    ) : LocalPhotosResult

    data class NotAuthorized(
        val missingPermissions: List<String>,
        val reason: String = "Photo read permission is required.",
    ) : LocalPhotosResult {
        override val summary: String = "Not authorized: ${missingPermissions.joinToString()}."
    }

    data class Rejected(val reason: String) : LocalPhotosResult {
        override val summary: String = reason
    }

    data class Failed(val reason: String) : LocalPhotosResult {
        override val summary: String = reason
    }
}

sealed interface PhotoPermissionStatus {
    data class Authorized(val accessLevel: PhotoAccessLevel) : PhotoPermissionStatus

    data class NotAuthorized(
        val missingPermissions: List<String>,
        val reason: String = "Photo read permission is required.",
    ) : PhotoPermissionStatus
}

enum class PhotoAccessLevel {
    FullImages,
    SelectedImages,
    LegacyExternalStorage,
}

enum class PhotoMediaPreference {
    CameraPhotos,
    Screenshots,
    SearchText,
    AnyImage,
}

private data class PhotoQueryRequest(
    val searchText: String?,
    val takenAfterMillis: Long?,
    val takenBeforeMillis: Long?,
    val maxResults: Int,
    val mimeTypes: Set<String>,
    val mediaPreference: PhotoMediaPreference,
)

private data class PhotoSelection(
    val sql: String?,
    val args: List<String>,
)

private fun Context.hasPermission(permission: String): Boolean {
    return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? {
    return if (isNull(index)) null else getString(index)
}

private fun android.database.Cursor.getStringOrBlank(index: Int): String {
    return getStringOrNull(index).orEmpty()
}

private fun android.database.Cursor.getLongOrNull(index: Int): Long? {
    return if (isNull(index)) null else getLong(index)
}

private fun android.database.Cursor.getIntOrNull(index: Int): Int? {
    return if (isNull(index)) null else getInt(index)
}

private fun String.escapeLikeWildcards(): String {
    return replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
}

private fun buildTakenRangeClause(
    takenAfterMillis: Long?,
    takenBeforeMillis: Long?,
    args: MutableList<String>,
): String {
    val dateTaken = MediaStore.Images.Media.DATE_TAKEN
    val dateAdded = MediaStore.Images.Media.DATE_ADDED
    val missingDateTaken = "($dateTaken IS NULL OR $dateTaken <= 0)"
    val secondsDateTaken = "($dateTaken > 0 AND $dateTaken < 100000000000)"
    return when {
        takenAfterMillis != null && takenBeforeMillis != null -> {
            args += takenAfterMillis.toString()
            args += takenBeforeMillis.toString()
            args += (takenAfterMillis / 1000L).toString()
            args += (takenBeforeMillis / 1000L).toString()
            args += (takenAfterMillis / 1000L).toString()
            args += (takenBeforeMillis / 1000L).toString()
            "(($dateTaken >= ? AND $dateTaken < ?) OR ($secondsDateTaken AND $dateTaken >= ? AND $dateTaken < ?) OR ($missingDateTaken AND $dateAdded >= ? AND $dateAdded < ?))"
        }
        takenAfterMillis != null -> {
            args += takenAfterMillis.toString()
            args += (takenAfterMillis / 1000L).toString()
            args += (takenAfterMillis / 1000L).toString()
            "($dateTaken >= ? OR ($secondsDateTaken AND $dateTaken >= ?) OR ($missingDateTaken AND $dateAdded >= ?))"
        }
        takenBeforeMillis != null -> {
            args += takenBeforeMillis.toString()
            args += (takenBeforeMillis / 1000L).toString()
            args += (takenBeforeMillis / 1000L).toString()
            "($dateTaken < ? OR ($secondsDateTaken AND $dateTaken < ?) OR ($missingDateTaken AND $dateAdded < ?))"
        }
        else -> "1"
    }
}

private fun Set<String>.normalizedMimeTypes(): Set<String> {
    return mapNotNull { mimeType ->
        mimeType.trim().lowercase().takeIf { it.isNotBlank() }
    }.toSet()
}

private fun Exception.shortMessage(): String {
    return message ?: javaClass.simpleName
}

private fun Long?.normalizedEpochMillis(): Long? {
    val value = this?.takeIf { it > 0L } ?: return null
    return if (value < 100_000_000_000L) value * 1000L else value
}

private val SCREENSHOT_MARKERS = listOf(
    "screenshot",
    "screen_shot",
    "screen-shot",
    "screenshots",
    "screenrecord",
    "screen_record",
    "screen recording",
)

private val CAMERA_LOCATION_MARKERS = listOf(
    "dcim/camera",
    "dcim\\camera",
    "dcim/open camera",
    "pixel camera",
    "open camera",
    "100media",
    "100andro",
)

private val APP_MEDIA_MARKERS = listOf(
    "whatsapp",
    "telegram",
    "signal",
    "messenger",
    "facebook",
    "instagram",
    "snapchat",
    "discord",
    "download",
    "downloads",
    "messages",
    "messaging",
    "mms",
    "com.google.android.apps.messaging",
    "com.samsung.android.messaging",
    "com.whatsapp",
    "whatsapp images",
    "bluetooth",
    "reddit",
    "twitter",
    "x/",
)

private val APP_MEDIA_FILENAME_PATTERNS = listOf(
    Regex("""^IMG-\d{8}-WA\d+""", RegexOption.IGNORE_CASE),
    Regex("""^VID-\d{8}-WA\d+""", RegexOption.IGNORE_CASE),
)

private val CAMERA_FILENAME_PATTERNS = listOf(
    Regex("""^IMG[_-]?\d+""", RegexOption.IGNORE_CASE),
    Regex("""^PXL[_-]?\d+""", RegexOption.IGNORE_CASE),
    Regex("""^MVIMG[_-]?\d+""", RegexOption.IGNORE_CASE),
    Regex("""^\d{8}[_-]\d{6}""", RegexOption.IGNORE_CASE),
)

private fun PhotoMediaPreference.comparator(searchText: String?): Comparator<LocalPhotoSummary> {
    val explicitScreenshot = this == PhotoMediaPreference.Screenshots ||
        searchText.orEmpty().contains(Regex("\\bscreenshots?\\b|screen\\s*shot", RegexOption.IGNORE_CASE))
    return compareByDescending<LocalPhotoSummary> { photo ->
        when {
            explicitScreenshot -> if (photo.isLikelyScreenshot()) 4 else 0
            this == PhotoMediaPreference.AnyImage -> 1
            this == PhotoMediaPreference.SearchText && searchText.orEmpty().isNotBlank() -> {
                if (!photo.isLikelyScreenshot() && !photo.isLikelyAppMedia()) 3 else 1
            }
            else -> photo.cameraPreferenceRank()
        }
    }.thenByDescending { photo ->
        when {
            explicitScreenshot -> photo.bestSortMillis()
            this == PhotoMediaPreference.CameraPhotos -> photo.captureSortMillis()
            photo.isLikelyScreenshot() -> 0L
            else -> photo.bestSortMillis() / 2L
        }
    }.thenByDescending { photo ->
        photo.dateTakenMillis ?: 0L
    }.thenByDescending { photo ->
        photo.dateAddedMillis ?: 0L
    }.thenByDescending { photo ->
        photo.dateModifiedMillis ?: 0L
    }.thenByDescending { photo ->
        photo.id
    }.thenBy { photo ->
        photo.displayName
    }
}

private fun PhotoMediaPreference.sortColumnSets(): List<Array<String>> {
    return when (this) {
        PhotoMediaPreference.CameraPhotos -> listOf(
            arrayOf(
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media._ID,
            ),
            arrayOf(
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media._ID,
            ),
            arrayOf(
                MediaStore.Images.Media._ID,
            ),
        )
        else -> listOf(
            arrayOf(
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media._ID,
            ),
            arrayOf(
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media._ID,
            ),
        )
    }
}
