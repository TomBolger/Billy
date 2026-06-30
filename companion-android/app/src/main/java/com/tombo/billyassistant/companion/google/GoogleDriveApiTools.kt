package com.tombo.billyassistant.companion.google

import com.tombo.billyassistant.companion.auth.GoogleAccessTokenProvider
import com.tombo.billyassistant.companion.auth.GoogleAccessTokenResult
import com.tombo.billyassistant.companion.auth.GoogleApiScopes
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class GoogleDriveApiTools(
    private val tokenProvider: GoogleAccessTokenProvider,
    private val http: GoogleApiHttp = GoogleApiHttp(),
) {
    fun listRecentFiles(maxResults: Int = 10, mimeType: String? = null): GoogleDriveResult {
        return withToken(GoogleApiScopes.identity + listOf(GoogleApiScopes.DRIVE_METADATA_READONLY, GoogleApiScopes.DRIVE_READONLY)) { token ->
            val mimeClause = mimeType?.trim()?.takeIf { it.isNotBlank() }?.let { " and mimeType = '${it.replace("'", "\\'")}'" }.orEmpty()
            val q = "trashed = false$mimeClause"
            val fields = "files(id,name,mimeType,modifiedTime,webViewLink,owners(displayName,emailAddress))"
            val url = "$API_BASE/files?q=${encode(q)}&pageSize=${maxResults.coerceIn(1, 20)}&orderBy=modifiedTime desc&fields=${encode(fields)}"
            when (val result = http.get(url, token)) {
                is GoogleHttpResult.Success -> {
                    val files = JSONObject(result.body).optJSONArray("files") ?: JSONArray()
                    val summary = when (files.length()) {
                        0 -> "No recent Drive files found."
                        1 -> "Found 1 recent Drive file."
                        else -> "Found ${files.length()} recent Drive files."
                    }
                    GoogleDriveResult.Success(
                        summary = summary,
                        payload = JSONObject()
                            .put("status", "ok")
                            .put("summary", summary)
                            .put("files", files),
                    )
                }
                is GoogleHttpResult.HttpError -> GoogleDriveResult.Failed("Google Drive recent HTTP ${result.responseCode}: ${result.reason}")
                is GoogleHttpResult.Failed -> GoogleDriveResult.Failed("Google Drive recent failed: ${result.reason}")
            }
        }
    }

    fun searchFiles(query: String, maxResults: Int = 10, mimeTypes: List<String> = emptyList()): GoogleDriveResult {
        if (query.isBlank()) {
            return GoogleDriveResult.Rejected("Drive search query is blank.")
        }
        return withToken(GoogleApiScopes.identity + listOf(GoogleApiScopes.DRIVE_METADATA_READONLY, GoogleApiScopes.DRIVE_READONLY)) { token ->
            when (val result = searchFilesRaw(token, query, maxResults, mimeTypes)) {
                is GoogleHttpResult.Success -> {
                    val files = JSONObject(result.body).optJSONArray("files") ?: JSONArray()
                    val summary = when (files.length()) {
                        0 -> "No Drive files found for \"$query\"."
                        1 -> "Found 1 Drive file for \"$query\"."
                        else -> "Found ${files.length()} Drive files for \"$query\"."
                    }
                    GoogleDriveResult.Success(
                        summary = summary,
                        payload = JSONObject()
                            .put("status", "ok")
                            .put("summary", summary)
                            .put("files", files),
                    )
                }
                is GoogleHttpResult.HttpError -> GoogleDriveResult.Failed("Google Drive HTTP ${result.responseCode}: ${result.reason}")
                is GoogleHttpResult.Failed -> GoogleDriveResult.Failed("Google Drive failed: ${result.reason}")
            }
        }
    }

    fun readGoogleDoc(fileId: String?, query: String?, maxChars: Int = 1800): GoogleDriveResult {
        return withToken(GoogleApiScopes.identity + listOf(GoogleApiScopes.DRIVE_METADATA_READONLY, GoogleApiScopes.DOCS_READONLY)) { token ->
            val file = resolveFile(
                token = token,
                fileId = fileId,
                query = query,
                mimeTypes = listOf(MIME_GOOGLE_DOC),
            ) ?: return@withToken GoogleDriveResult.Rejected("I could not find a Google Doc to read.")
            when (val result = http.get("$DOCS_BASE/documents/${encode(file.id)}", token)) {
                is GoogleHttpResult.Success -> {
                    val doc = JSONObject(result.body)
                    val title = doc.optString("title").ifBlank { file.name }
                    val text = extractDocText(doc).trim().take(maxChars.coerceIn(200, MAX_TEXT_CHARS))
                    val summary = if (text.isBlank()) {
                        "Google Doc \"$title\" has no readable text."
                    } else {
                        "Google Doc: $title"
                    }
                    GoogleDriveResult.Success(
                        summary = summary,
                        payload = JSONObject()
                            .put("status", "ok")
                            .put("summary", summary)
                            .put("file", file.toJson())
                            .put("title", title)
                            .put("text", text),
                    )
                }
                is GoogleHttpResult.HttpError -> GoogleDriveResult.Failed("Google Docs HTTP ${result.responseCode}: ${result.reason}")
                is GoogleHttpResult.Failed -> GoogleDriveResult.Failed("Google Docs failed: ${result.reason}")
            }
        }
    }

    fun createGoogleDoc(title: String, text: String?): GoogleDriveResult {
        val cleanTitle = title.trim().ifBlank { "Billy note" }
        val cleanText = text?.trim().orEmpty()
        return withToken(GoogleApiScopes.identity + GoogleApiScopes.DOCS) { token ->
            when (val create = http.post("$DOCS_BASE/documents", token, JSONObject().put("title", cleanTitle.take(200)))) {
                is GoogleHttpResult.Success -> {
                    val doc = JSONObject(create.body)
                    val documentId = doc.optString("documentId")
                    if (documentId.isBlank()) {
                        return@withToken GoogleDriveResult.Failed("Google Docs did not return a document id.")
                    }
                    if (cleanText.isNotBlank()) {
                        val requests = JSONArray().put(
                            JSONObject().put(
                                "insertText",
                                JSONObject()
                                    .put("location", JSONObject().put("index", 1))
                                    .put("text", cleanText.take(MAX_TEXT_CHARS)),
                            ),
                        )
                        when (val update = http.post("$DOCS_BASE/documents/${encode(documentId)}:batchUpdate", token, JSONObject().put("requests", requests))) {
                            is GoogleHttpResult.HttpError -> return@withToken GoogleDriveResult.Failed("Created Doc, but text insert HTTP ${update.responseCode}: ${update.reason}")
                            is GoogleHttpResult.Failed -> return@withToken GoogleDriveResult.Failed("Created Doc, but text insert failed: ${update.reason}")
                            is GoogleHttpResult.Success -> Unit
                        }
                    }
                    val summary = "Created Google Doc:\n$cleanTitle"
                    GoogleDriveResult.Success(
                        summary = summary,
                        payload = JSONObject()
                            .put("status", "ok")
                            .put("summary", summary)
                            .put("document_id", documentId)
                            .put("title", cleanTitle),
                    )
                }
                is GoogleHttpResult.HttpError -> GoogleDriveResult.Failed("Google Docs create HTTP ${create.responseCode}: ${create.reason}")
                is GoogleHttpResult.Failed -> GoogleDriveResult.Failed("Google Docs create failed: ${create.reason}")
            }
        }
    }

    fun readGoogleSheet(fileId: String?, query: String?, range: String?, maxRows: Int = 12): GoogleDriveResult {
        return withToken(GoogleApiScopes.identity + listOf(GoogleApiScopes.DRIVE_METADATA_READONLY, GoogleApiScopes.SHEETS_READONLY)) { token ->
            val file = resolveFile(
                token = token,
                fileId = fileId,
                query = query,
                mimeTypes = listOf(MIME_GOOGLE_SHEET),
            ) ?: return@withToken GoogleDriveResult.Rejected("I could not find a Google Sheet to read.")
            val metadataUrl = "$SHEETS_BASE/spreadsheets/${encode(file.id)}?fields=${encode("properties(title),sheets(properties(title))")}"
            when (val metadata = http.get(metadataUrl, token)) {
                is GoogleHttpResult.Success -> {
                    val spreadsheet = JSONObject(metadata.body)
                    val title = spreadsheet.optJSONObject("properties")?.optString("title").orEmpty().ifBlank { file.name }
                    val firstSheet = spreadsheet.optJSONArray("sheets")
                        ?.optJSONObject(0)
                        ?.optJSONObject("properties")
                        ?.optString("title")
                        .orEmpty()
                        .ifBlank { "Sheet1" }
                    val requestedRange = range?.trim().orEmpty().ifBlank { "'${firstSheet.replace("'", "''")}'!A1:E${maxRows.coerceIn(1, 30)}" }
                    val valuesUrl = "$SHEETS_BASE/spreadsheets/${encode(file.id)}/values/${encodePath(requestedRange)}?majorDimension=ROWS"
                    when (val valuesResult = http.get(valuesUrl, token)) {
                        is GoogleHttpResult.Success -> {
                            val values = JSONObject(valuesResult.body).optJSONArray("values") ?: JSONArray()
                            val summary = "Google Sheet: $title\nRange: $requestedRange"
                            GoogleDriveResult.Success(
                                summary = summary,
                                payload = JSONObject()
                                    .put("status", "ok")
                                    .put("summary", summary)
                                    .put("file", file.toJson())
                                    .put("title", title)
                                    .put("range", requestedRange)
                                    .put("values", trimRows(values, maxRows.coerceIn(1, 30))),
                            )
                        }
                        is GoogleHttpResult.HttpError -> GoogleDriveResult.Failed("Google Sheets values HTTP ${valuesResult.responseCode}: ${valuesResult.reason}")
                        is GoogleHttpResult.Failed -> GoogleDriveResult.Failed("Google Sheets values failed: ${valuesResult.reason}")
                    }
                }
                is GoogleHttpResult.HttpError -> GoogleDriveResult.Failed("Google Sheets HTTP ${metadata.responseCode}: ${metadata.reason}")
                is GoogleHttpResult.Failed -> GoogleDriveResult.Failed("Google Sheets failed: ${metadata.reason}")
            }
        }
    }

    fun readGoogleSlides(fileId: String?, query: String?, maxChars: Int = 1800): GoogleDriveResult {
        return withToken(GoogleApiScopes.identity + listOf(GoogleApiScopes.DRIVE_METADATA_READONLY, GoogleApiScopes.SLIDES_READONLY)) { token ->
            val file = resolveFile(
                token = token,
                fileId = fileId,
                query = query,
                mimeTypes = listOf(MIME_GOOGLE_SLIDES),
            ) ?: return@withToken GoogleDriveResult.Rejected("I could not find a Google Slides deck to read.")
            when (val result = http.get("$SLIDES_BASE/presentations/${encode(file.id)}", token)) {
                is GoogleHttpResult.Success -> {
                    val deck = JSONObject(result.body)
                    val title = deck.optString("title").ifBlank { file.name }
                    val text = extractSlidesText(deck).trim().take(maxChars.coerceIn(200, MAX_TEXT_CHARS))
                    val summary = if (text.isBlank()) {
                        "Google Slides \"$title\" has no readable text."
                    } else {
                        "Google Slides: $title"
                    }
                    GoogleDriveResult.Success(
                        summary = summary,
                        payload = JSONObject()
                            .put("status", "ok")
                            .put("summary", summary)
                            .put("file", file.toJson())
                            .put("title", title)
                            .put("text", text),
                    )
                }
                is GoogleHttpResult.HttpError -> GoogleDriveResult.Failed("Google Slides HTTP ${result.responseCode}: ${result.reason}")
                is GoogleHttpResult.Failed -> GoogleDriveResult.Failed("Google Slides failed: ${result.reason}")
            }
        }
    }

    fun createGoogleSheet(title: String): GoogleDriveResult {
        val cleanTitle = title.trim().ifBlank { "Billy spreadsheet" }.take(200)
        return withToken(GoogleApiScopes.identity + GoogleApiScopes.SHEETS) { token ->
            val body = JSONObject().put("properties", JSONObject().put("title", cleanTitle))
            when (val create = http.post("$SHEETS_BASE/spreadsheets", token, body)) {
                is GoogleHttpResult.Success -> {
                    val sheet = JSONObject(create.body)
                    val spreadsheetId = sheet.optString("spreadsheetId")
                    val summary = "Created Google Sheet:\n$cleanTitle"
                    GoogleDriveResult.Success(
                        summary = summary,
                        payload = JSONObject()
                            .put("status", "ok")
                            .put("summary", summary)
                            .put("spreadsheet_id", spreadsheetId)
                            .put("title", cleanTitle)
                            .put("url", sheet.optString("spreadsheetUrl")),
                    )
                }
                is GoogleHttpResult.HttpError -> GoogleDriveResult.Failed("Google Sheets create HTTP ${create.responseCode}: ${create.reason}")
                is GoogleHttpResult.Failed -> GoogleDriveResult.Failed("Google Sheets create failed: ${create.reason}")
            }
        }
    }

    fun createGoogleSlides(title: String): GoogleDriveResult {
        val cleanTitle = title.trim().ifBlank { "Billy presentation" }.take(200)
        return withToken(GoogleApiScopes.identity + GoogleApiScopes.SLIDES) { token ->
            when (val create = http.post("$SLIDES_BASE/presentations", token, JSONObject().put("title", cleanTitle))) {
                is GoogleHttpResult.Success -> {
                    val deck = JSONObject(create.body)
                    val presentationId = deck.optString("presentationId")
                    val summary = "Created Google Slides:\n$cleanTitle"
                    GoogleDriveResult.Success(
                        summary = summary,
                        payload = JSONObject()
                            .put("status", "ok")
                            .put("summary", summary)
                            .put("presentation_id", presentationId)
                            .put("title", cleanTitle),
                    )
                }
                is GoogleHttpResult.HttpError -> GoogleDriveResult.Failed("Google Slides create HTTP ${create.responseCode}: ${create.reason}")
                is GoogleHttpResult.Failed -> GoogleDriveResult.Failed("Google Slides create failed: ${create.reason}")
            }
        }
    }

    fun readGoogleForm(fileId: String?, query: String?, maxItems: Int = 10): GoogleDriveResult {
        return withToken(GoogleApiScopes.identity + listOf(GoogleApiScopes.DRIVE_METADATA_READONLY, GoogleApiScopes.FORMS_BODY_READONLY)) { token ->
            val file = resolveFile(
                token = token,
                fileId = fileId,
                query = query,
                mimeTypes = listOf(MIME_GOOGLE_FORM),
            ) ?: return@withToken GoogleDriveResult.Rejected("I could not find a Google Form to read.")
            when (val result = http.get("$FORMS_BASE/forms/${encode(file.id)}", token)) {
                is GoogleHttpResult.Success -> {
                    val form = JSONObject(result.body)
                    val info = form.optJSONObject("info") ?: JSONObject()
                    val items = form.optJSONArray("items") ?: JSONArray()
                    val compactItems = JSONArray()
                    for (i in 0 until minOf(items.length(), maxItems.coerceIn(1, 30))) {
                        items.optJSONObject(i)?.let { item ->
                            compactItems.put(
                                JSONObject()
                                    .put("title", item.optString("title"))
                                    .put("description", item.optString("description"))
                                    .put("question", item.optJSONObject("questionItem")?.optJSONObject("question")?.let(::compactFormQuestion)),
                            )
                        }
                    }
                    val title = info.optString("title").ifBlank { file.name }
                    val summary = "Google Form: $title"
                    GoogleDriveResult.Success(
                        summary = summary,
                        payload = JSONObject()
                            .put("status", "ok")
                            .put("summary", summary)
                            .put("file", file.toJson())
                            .put("title", title)
                            .put("description", info.optString("description"))
                            .put("items", compactItems),
                    )
                }
                is GoogleHttpResult.HttpError -> GoogleDriveResult.Failed("Google Forms HTTP ${result.responseCode}: ${result.reason}")
                is GoogleHttpResult.Failed -> GoogleDriveResult.Failed("Google Forms failed: ${result.reason}")
            }
        }
    }

    private fun searchFilesRaw(
        token: String,
        query: String,
        maxResults: Int,
        mimeTypes: List<String>,
    ): GoogleHttpResult {
        val escaped = query.trim().replace("'", "\\'")
        val textClause = "(name contains '$escaped' or fullText contains '$escaped')"
        val mimeClause = mimeTypes
            .filter { it.isNotBlank() }
            .joinToString(separator = " or ", prefix = "(", postfix = ")") { "mimeType = '$it'" }
        val clauses = listOf("trashed = false", textClause, mimeClause.takeIf { mimeTypes.isNotEmpty() })
            .filterNotNull()
        val q = clauses.joinToString(" and ")
        val fields = "files(id,name,mimeType,modifiedTime,webViewLink,owners(displayName,emailAddress))"
        val url = "$API_BASE/files?q=${encode(q)}&pageSize=${maxResults.coerceIn(1, 20)}&fields=${encode(fields)}"
        return http.get(url, token)
    }

    private fun resolveFile(
        token: String,
        fileId: String?,
        query: String?,
        mimeTypes: List<String>,
    ): DriveFileSummary? {
        val cleanId = fileId?.trim().orEmpty()
        if (cleanId.isNotBlank()) {
            val fields = "id,name,mimeType,modifiedTime,webViewLink,owners(displayName,emailAddress)"
            return when (val result = http.get("$API_BASE/files/${encode(cleanId)}?fields=${encode(fields)}", token)) {
                is GoogleHttpResult.Success -> DriveFileSummary.fromJson(JSONObject(result.body))
                else -> null
            }
        }
        val cleanQuery = query?.trim().orEmpty()
        if (cleanQuery.isBlank()) {
            return null
        }
        return when (val result = searchFilesRaw(token, cleanQuery, 1, mimeTypes)) {
            is GoogleHttpResult.Success -> {
                val files = JSONObject(result.body).optJSONArray("files") ?: JSONArray()
                files.optJSONObject(0)?.let(DriveFileSummary::fromJson)
            }
            else -> null
        }
    }

    private fun extractDocText(doc: JSONObject): String {
        val content = doc.optJSONObject("body")?.optJSONArray("content") ?: JSONArray()
        val lines = mutableListOf<String>()
        for (i in 0 until content.length()) {
            val paragraph = content.optJSONObject(i)?.optJSONObject("paragraph") ?: continue
            val elements = paragraph.optJSONArray("elements") ?: continue
            val line = buildString {
                for (j in 0 until elements.length()) {
                    append(elements.optJSONObject(j)?.optJSONObject("textRun")?.optString("content").orEmpty())
                }
            }.trim()
            if (line.isNotBlank()) {
                lines += line
            }
        }
        return lines.joinToString("\n")
    }

    private fun extractSlidesText(deck: JSONObject): String {
        val slides = deck.optJSONArray("slides") ?: JSONArray()
        val lines = mutableListOf<String>()
        for (i in 0 until slides.length()) {
            val pageElements = slides.optJSONObject(i)?.optJSONArray("pageElements") ?: continue
            for (j in 0 until pageElements.length()) {
                val textElements = pageElements.optJSONObject(j)
                    ?.optJSONObject("shape")
                    ?.optJSONObject("text")
                    ?.optJSONArray("textElements")
                    ?: continue
                for (k in 0 until textElements.length()) {
                    val content = textElements.optJSONObject(k)
                        ?.optJSONObject("textRun")
                        ?.optString("content")
                        .orEmpty()
                        .trim()
                    if (content.isNotBlank()) {
                        lines += content
                    }
                }
            }
        }
        return lines.joinToString("\n")
    }

    private fun trimRows(values: JSONArray, maxRows: Int): JSONArray {
        val output = JSONArray()
        for (i in 0 until minOf(values.length(), maxRows)) {
            output.put(values.optJSONArray(i) ?: JSONArray())
        }
        return output
    }

    private fun compactFormQuestion(question: JSONObject): JSONObject {
        val choice = question.optJSONObject("choiceQuestion")
        val options = JSONArray()
        val rawOptions = choice?.optJSONArray("options") ?: JSONArray()
        for (i in 0 until minOf(rawOptions.length(), 8)) {
            rawOptions.optJSONObject(i)?.optString("value")?.takeIf { it.isNotBlank() }?.let { options.put(it) }
        }
        return JSONObject()
            .put("question_id", question.optString("questionId"))
            .put("required", question.optBoolean("required"))
            .put("type", when {
                choice != null -> choice.optString("type").ifBlank { "choice" }
                question.has("textQuestion") -> "text"
                question.has("scaleQuestion") -> "scale"
                question.has("dateQuestion") -> "date"
                question.has("timeQuestion") -> "time"
                else -> "question"
            })
            .put("options", options)
    }

    private fun withToken(scopes: List<String>, block: (String) -> GoogleDriveResult): GoogleDriveResult {
        return when (val token = tokenProvider.getAccessToken(scopes.distinct())) {
            is GoogleAccessTokenResult.Authorized -> block(token.accessToken)
            is GoogleAccessTokenResult.NeedsUserGrant -> GoogleDriveResult.NeedsScope(token.scopes)
            is GoogleAccessTokenResult.Failed -> GoogleDriveResult.Failed(token.reason)
        }
    }

    private data class DriveFileSummary(
        val id: String,
        val name: String,
        val mimeType: String,
        val modifiedTime: String,
        val webViewLink: String,
    ) {
        fun toJson(): JSONObject {
            return JSONObject()
                .put("id", id)
                .put("name", name)
                .put("mimeType", mimeType)
                .put("modifiedTime", modifiedTime)
                .put("webViewLink", webViewLink)
        }

        companion object {
            fun fromJson(file: JSONObject): DriveFileSummary {
                return DriveFileSummary(
                    id = file.optString("id"),
                    name = file.optString("name"),
                    mimeType = file.optString("mimeType"),
                    modifiedTime = file.optString("modifiedTime"),
                    webViewLink = file.optString("webViewLink"),
                )
            }
        }
    }

    private fun encodePath(value: String): String {
        return value.split("/")
            .joinToString("/") { part -> URLEncoder.encode(part, Charsets.UTF_8.name()).replace("+", "%20") }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private companion object {
        private const val API_BASE = "https://www.googleapis.com/drive/v3"
        private const val DOCS_BASE = "https://docs.googleapis.com/v1"
        private const val SHEETS_BASE = "https://sheets.googleapis.com/v4"
        private const val SLIDES_BASE = "https://slides.googleapis.com/v1"
        private const val FORMS_BASE = "https://forms.googleapis.com/v1"
        private const val MIME_GOOGLE_DOC = "application/vnd.google-apps.document"
        private const val MIME_GOOGLE_SHEET = "application/vnd.google-apps.spreadsheet"
        private const val MIME_GOOGLE_SLIDES = "application/vnd.google-apps.presentation"
        private const val MIME_GOOGLE_FORM = "application/vnd.google-apps.form"
        private const val MAX_TEXT_CHARS = 5_000
    }
}

sealed interface GoogleDriveResult {
    val summary: String

    data class Success(
        override val summary: String,
        val payload: JSONObject,
    ) : GoogleDriveResult

    data class NeedsScope(val scopes: List<String>) : GoogleDriveResult {
        override val summary: String = "Grant Google Drive access in the companion app."
    }

    data class Rejected(val reason: String) : GoogleDriveResult {
        override val summary: String = reason
    }

    data class Failed(val reason: String) : GoogleDriveResult {
        override val summary: String = reason
    }
}
