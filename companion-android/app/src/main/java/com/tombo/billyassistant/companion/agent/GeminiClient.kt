package com.tombo.billyassistant.companion.agent

import com.tombo.billyassistant.companion.agent.tools.CompanionToolExecution
import com.tombo.billyassistant.companion.agent.tools.WatchImage
import com.tombo.billyassistant.companion.agent.tools.WatchToolsCompanionTool
import com.tombo.billyassistant.companion.agent.tools.WatchWeatherCurrent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Gemini generateContent client and tool loop for Billy Companion.
 *
 * Rules that keep tool use reliable (mirrors app/src/pkjs/agent/gemini.js):
 * - One API surface (generateContent). No fallback that flattens tool results into text.
 * - Google Search + function declarations are sent together with
 *   toolConfig.includeServerSideToolInvocations, which Gemini 3 requires.
 * - The model is chosen on the first call of a turn and then pinned, because
 *   thought signatures are only valid for the model that produced them.
 * - Model content is replayed untouched, all function responses of one step go
 *   back in one user turn with their call ids.
 * - A tool's finalText is a suggested reply, not an early exit, so the model can
 *   chain further tools or recover from an error.
 */
class GeminiClient(preferredModel: String? = null) {
    private val models: List<String> = buildList {
        preferredModel?.trim()?.takeIf { it.startsWith("gemini-") }?.let { add(it) }
        FALLBACK_MODELS.forEach { if (it !in this) add(it) }
    }

    val primaryModel: String get() = models.first()

    fun describeConfiguration(apiKey: String): String {
        return if (apiKey.isBlank()) {
            "Gemini API key is not configured."
        } else {
            "Gemini API key is stored locally. Use Verify key to check it."
        }
    }

    fun testKey(apiKey: String): GeminiKeyTestResult {
        val key = normalizeApiKey(apiKey)
        if (key.isBlank()) {
            return GeminiKeyTestResult.Failed("Gemini API key is missing.")
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(userText("Reply with OK.")))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 16))
        return when (val result = post(key, primaryModel, body)) {
            is HttpResult.Ok -> GeminiKeyTestResult.Passed("Gemini key works with $primaryModel.")
            is HttpResult.Error -> GeminiKeyTestResult.Failed(result.message)
        }
    }

    fun chooseImageCandidate(
        prompt: String,
        apiKey: String,
        candidates: List<GeminiImageCandidate>,
    ): GeminiImageChoice? {
        val key = normalizeApiKey(apiKey)
        if (key.isBlank() || candidates.isEmpty()) {
            return null
        }
        val parts = JSONArray().put(
            JSONObject().put(
                "text",
                "Choose the single candidate photo that best matches this watch request: \"$prompt\". " +
                    "Judge visible content first, then filename/folder/date only as weak hints. " +
                    "If the request asks for a dog, choose a real dog, not a toy, doll, drawing, or person. " +
                    "If the request asks for family, children, or a named person, choose real people, not toys or dolls. " +
                    "If no candidate visibly matches, return confidence 0 instead of guessing. " +
                    "Reply only as JSON like {\"index\":0,\"confidence\":85,\"reason\":\"short reason\"}.",
            ),
        )
        candidates.forEach { candidate ->
            parts.put(JSONObject().put("text", "Candidate ${candidate.index}: ${candidate.label}"))
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", candidate.mimeType).put("data", candidate.base64Data),
                ),
            )
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", generationConfig(primaryModel, forceJson = true))
        val result = post(key, primaryModel, body) as? HttpResult.Ok ?: return null
        val text = parseResponse(primaryModel, result.json).text
        val json = runCatching { JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)) }.getOrNull()
        val index = json?.optInt("index", -1) ?: -1
        if (index >= 0 && candidates.any { it.index == index }) {
            return GeminiImageChoice(
                index = index,
                confidence = json?.optInt("confidence", 50)?.coerceIn(0, 100) ?: 50,
                reason = json?.optString("reason").orEmpty(),
            )
        }
        return null
    }

    fun generateWithTools(
        prompt: String,
        apiKey: String,
        toolDeclarations: JSONArray,
        toolExecutor: (String, JSONObject) -> CompanionToolExecution,
        history: JSONArray = JSONArray(),
    ): CompanionAgentResult {
        val key = normalizeApiKey(apiKey)
        if (key.isBlank()) {
            return CompanionAgentResult.Failed("Gemini API key is missing in Billy Companion.")
        }
        if (prompt.isBlank()) {
            return CompanionAgentResult.Failed("Prompt is blank.")
        }
        val contents = JSONArray()
        for (i in 0 until history.length()) {
            contents.put(history.get(i))
        }
        contents.put(userText(prompt.trim()))

        var pinnedModel: String? = null
        var watchImage: WatchImage? = null
        var watchWeather: WatchWeatherCurrent? = null
        var suggestedReply: String? = null
        var lastOkSummary: String? = null
        val executed = mutableMapOf<String, JSONObject>()

        for (step in 0 until MAX_STEPS) {
            val forceText = step == MAX_STEPS - 1
            val response = if (pinnedModel == null) {
                generateFirst(key, contents, toolDeclarations, forceText)
            } else {
                generateWithModel(key, pinnedModel, contents, toolDeclarations, forceText)
            }
            val parsed = when (response) {
                is StepResult.Failed -> return CompanionAgentResult.Failed(response.reason)
                is StepResult.Ok -> response.parsed
            }
            pinnedModel = parsed.model
            contents.put(parsed.modelContent)

            if (parsed.calls.isEmpty() || forceText) {
                val text = parsed.text.ifBlank { suggestedReply ?: lastOkSummary.orEmpty() }
                    .ifBlank { if (watchImage != null || watchWeather != null) "" else "Sorry, I did not get an answer. Please try again." }
                return CompanionAgentResult.Passed(text = text, watchImage = watchImage, watchWeatherCurrent = watchWeather)
            }

            val responseParts = JSONArray()
            val extraParts = JSONArray()
            for (call in parsed.calls) {
                val dedupeKey = call.name + ":" + call.args.toString()
                val previous = executed[dedupeKey]
                val execution = if (previous != null && call.name in MUTATING_TOOLS) {
                    CompanionToolExecution(
                        JSONObject()
                            .put("status", previous.optString("status", "ok"))
                            .put("summary", "Already done earlier in this turn; not repeated."),
                    )
                } else {
                    runCatching { toolExecutor(call.name, call.args) }.getOrElse { e ->
                        CompanionToolExecution(
                            JSONObject().put("status", "error").put("summary", "Tool ${call.name} crashed: ${e.message ?: e.javaClass.simpleName}"),
                        )
                    }
                }
                if (call.name in MUTATING_TOOLS) {
                    executed[dedupeKey] = execution.response
                }
                execution.watchImage?.let { watchImage = it }
                execution.watchWeatherCurrent?.let { watchWeather = it }
                execution.clarificationCard?.let { card ->
                    return CompanionAgentResult.Passed(
                        text = "",
                        watchImage = watchImage,
                        watchWeatherCurrent = watchWeather,
                        clarificationCard = card,
                    )
                }
                val toolResponse = JSONObject(execution.response.toString())
                val ok = toolResponse.optString("status", "ok").equals("ok", ignoreCase = true)
                execution.finalText?.takeIf { it.isNotBlank() }?.let { finalText ->
                    toolResponse.put("suggested_watch_reply", finalText)
                    if (ok) suggestedReply = finalText
                }
                if (ok) {
                    toolResponse.optString("summary").takeIf { it.isNotBlank() }?.let { lastOkSummary = it }
                }
                if (execution.watchImage != null || execution.watchWeatherCurrent != null) {
                    toolResponse.put("watch_card", "A card is already shown on the watch; add context, do not repeat it.")
                }
                val functionResponse = JSONObject()
                    .put("name", call.name)
                    .put("response", toolResponse)
                call.id?.let { functionResponse.put("id", it) }
                responseParts.put(JSONObject().put("functionResponse", functionResponse))
                for (i in 0 until execution.followUpParts.length()) {
                    extraParts.put(execution.followUpParts.get(i))
                }
            }
            if (extraParts.length() > 0) {
                responseParts.put(JSONObject().put("text", "Attached data for the tool results above. Use it to answer concretely."))
                for (i in 0 until extraParts.length()) {
                    responseParts.put(extraParts.get(i))
                }
            }
            contents.put(JSONObject().put("role", "user").put("parts", responseParts))
        }
        return CompanionAgentResult.Failed("Billy ran out of steps. Please try a simpler request.")
    }

    private fun generateFirst(
        apiKey: String,
        contents: JSONArray,
        declarations: JSONArray,
        forceText: Boolean,
    ): StepResult {
        var lastFailure: StepResult.Failed? = null
        for (model in models) {
            when (val result = generateWithModel(apiKey, model, contents, declarations, forceText)) {
                is StepResult.Ok -> return result
                is StepResult.Failed -> {
                    lastFailure = result
                    if (!result.tryNextModel) {
                        return result
                    }
                }
            }
        }
        return lastFailure ?: StepResult.Failed("Gemini did not answer. Try again.", tryNextModel = false)
    }

    private fun generateWithModel(
        apiKey: String,
        model: String,
        contents: JSONArray,
        declarations: JSONArray,
        forceText: Boolean,
    ): StepResult {
        var useSearch = true
        var attempt = 0
        while (true) {
            attempt++
            val body = buildBody(model, contents, declarations, useSearch, forceText)
            when (val http = post(apiKey, model, body)) {
                is HttpResult.Ok -> {
                    val parsed = parseResponse(model, http.json)
                    if (parsed.calls.isEmpty() && parsed.text.isBlank() && attempt < 2) {
                        continue
                    }
                    if (parsed.calls.isEmpty() && parsed.text.isBlank()) {
                        return StepResult.Failed("Gemini returned an empty answer.", tryNextModel = true)
                    }
                    return StepResult.Ok(parsed)
                }
                is HttpResult.Error -> {
                    val lower = http.raw.lowercase()
                    if (useSearch && http.code == 400 &&
                        listOf("search", "tool", "server_side", "server-side", "combination", "function").any { it in lower }
                    ) {
                        useSearch = false
                        continue
                    }
                    if (http.transient && attempt < 2) {
                        Thread.sleep(RETRY_DELAY_MS)
                        continue
                    }
                    val nextModel = http.transient || http.code == 404 ||
                        listOf("overloaded", "unavailable", "high demand", "not found", "not supported").any { it in lower }
                    return StepResult.Failed(http.message, tryNextModel = nextModel)
                }
            }
        }
    }

    private fun buildBody(
        model: String,
        contents: JSONArray,
        declarations: JSONArray,
        useSearch: Boolean,
        forceText: Boolean,
    ): JSONObject {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction()))))
            .put("contents", contents)
            .put("generationConfig", generationConfig(model, forceJson = false))
        val tools = JSONArray()
        val search = useSearch && (declarations.length() == 0 || isGemini3(model))
        if (search) {
            tools.put(JSONObject().put("googleSearch", JSONObject()))
        }
        if (declarations.length() > 0) {
            tools.put(JSONObject().put("functionDeclarations", declarations))
        }
        if (tools.length() > 0) {
            body.put("tools", tools)
        }
        val toolConfig = JSONObject()
        if (search && declarations.length() > 0) {
            toolConfig.put("includeServerSideToolInvocations", true)
        }
        if (forceText && declarations.length() > 0) {
            toolConfig.put("functionCallingConfig", JSONObject().put("mode", "NONE"))
        }
        if (toolConfig.length() > 0) {
            body.put("toolConfig", toolConfig)
        }
        return body
    }

    private fun generationConfig(model: String, forceJson: Boolean): JSONObject {
        val config = JSONObject()
            .put("candidateCount", 1)
            .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
        if (isGemini3(model)) {
            config.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        }
        if (forceJson) {
            config.put("responseMimeType", "application/json")
        }
        return config
    }

    private fun post(apiKey: String, model: String, body: JSONObject): HttpResult {
        return try {
            val connection = (URL("$BASE_URL$model:generateContent").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) {
                HttpResult.Ok(JSONObject(text))
            } else {
                HttpResult.Error(code, friendlyError(code, text, model), text, transient = code in TRANSIENT_CODES)
            }
        } catch (e: SocketTimeoutException) {
            HttpResult.Error(0, "Gemini took too long to answer.", "timeout", transient = true)
        } catch (e: Exception) {
            HttpResult.Error(0, "Could not reach Gemini: ${e.message ?: e.javaClass.simpleName}", "network", transient = true)
        }
    }

    private fun parseResponse(model: String, root: JSONObject): ParsedResponse {
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
        val content = candidate?.optJSONObject("content") ?: JSONObject().put("parts", JSONArray())
        if (content.optString("role").isBlank()) {
            content.put("role", "model")
        }
        val parts = content.optJSONArray("parts") ?: JSONArray()
        val text = StringBuilder()
        val calls = mutableListOf<GeminiFunctionCall>()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            val functionCall = part.optJSONObject("functionCall")
            if (functionCall != null) {
                val args = functionCall.optJSONObject("args")
                    ?: functionCall.optString("args").takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: JSONObject()
                calls.add(
                    GeminiFunctionCall(
                        id = functionCall.optString("id").takeIf { it.isNotBlank() },
                        name = functionCall.optString("name"),
                        args = args,
                    ),
                )
            } else if (part.has("text")) {
                text.append(part.optString("text"))
            }
        }
        return ParsedResponse(model, text.toString().trim(), calls, content)
    }

    private fun systemInstruction(): String {
        val now = ZonedDateTime.now()
        return SYSTEM_INSTRUCTION +
            "\n\nCONTEXT\n- Now: ${now.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} " +
            "${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.withNano(0))} (${now.zone.id}). " +
            "Use this offset for every time you pass to a tool unless the user names another timezone."
    }

    private fun friendlyError(code: Int, raw: String, model: String): String {
        val message = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: raw.ifBlank { "HTTP $code" }
        val lower = message.lowercase()
        return when {
            code == 403 && "blocked" in lower ->
                "Gemini API key is blocked. Allow the Generative Language API (and this app, if the key is restricted)."
            "api key not valid" in lower || "invalid authentication" in lower ->
                "Gemini did not accept the API key. Use a key from Google AI Studio."
            code == 429 -> "Gemini rate limit or quota reached. Try again shortly."
            else -> "Gemini error ($model): ${message.take(MAX_ERROR_LENGTH)}"
        }
    }

    private fun userText(text: String): JSONObject {
        return JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text)))
    }

    private fun normalizeApiKey(apiKey: String): String = apiKey.filterNot { it.isWhitespace() }

    private fun isGemini3(model: String): Boolean = model.startsWith("gemini-3")

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        private val FALLBACK_MODELS = listOf(DEFAULT_MODEL, "gemini-3.7-flash", "gemini-3.1-flash-lite")
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val MAX_STEPS = 8
        private const val MAX_OUTPUT_TOKENS = 4096
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 35_000
        private const val RETRY_DELAY_MS = 900L
        private const val MAX_ERROR_LENGTH = 180
        private val TRANSIENT_CODES = setOf(429, 500, 502, 503, 504)
        private val MUTATING_TOOLS = WatchToolsCompanionTool.MUTATING + setOf(
            "create_calendar_event", "create_google_task", "complete_google_task", "create_gmail_draft",
            "create_google_doc", "create_google_sheet", "create_google_slides", "update_google_doc",
            "create_google_keep_note", "remember_billy_user_fact", "forget_billy_user_fact",
        )

        private val SYSTEM_INSTRUCTION = listOf(
            // Core rules: keep in sync with app/src/pkjs/agent/prompt.js (CORE_RULES).
            "You are Billy, a helpful, capable assistant that lives on the user's Pebble smartwatch. Aim to be as useful as Gemini on a phone.",
            "Input is voice dictation. Silently fix obvious transcription mistakes and never comment on them.",
            "",
            "TOOLS",
            "- You have real tools. When the user asks you to DO something (set, start, add, remind, cancel, change, show, find, send, create), call the matching tool. Do not describe what you would do, do not tell the user to do it themselves, and do not claim success unless the tool returned status ok.",
            "- If a request needs several steps, call several tools in a row (e.g. list alarms, then delete the right one; find a place, then show directions).",
            "- Follow-ups like \"cancel it\", \"make that 10 minutes\", or \"one more for 8\" refer to earlier turns. Use the conversation context.",
            "- If a tool returns an error, fix the arguments and retry once, or tell the user plainly what went wrong. If a result has suggested_watch_reply you may use it as your reply.",
            "- Timers are durations (\"in 10 minutes\", \"for 5 min\"); alarms are clock times (\"at 7am\"); \"remind me to X\" uses set_reminder (watch timeline), not Calendar or Tasks, unless the user names Calendar or Tasks.",
            "- Ask with ask_clarifying_question only when a wrong guess would create, send, or delete the wrong thing and there is no sensible default. Otherwise pick the most reasonable reading and act.",
            "- Use Google Search for anything current or factual you are not sure about: news, sports, prices, hours, recent releases.",
            "",
            "INFO CARDS",
            "- Prefer a card when one fits: get_weather (weather card), show_number (one big number for calculations, conversions, counts, prices), set_timer (live countdown), show_map_directions (map), photo tools (photo). When a card is shown, your text should add context, not repeat the card.",
            "",
            "REPLIES",
            "- Replies appear on a tiny screen: usually 1-4 short lines. Lead with the answer. Plain text only: no markdown, bold, tables, headings, links, or citations. Use \"- \" bullets for short lists.",
            "- Text you pass into tools that create content elsewhere (emails, documents, tasks, events) is not limited by the watch screen; write it fully.",
            "- Do not end with an open question. If you truly need an answer, use ask_clarifying_question.",
            "",
            "PHONE AND GOOGLE SERVICES (this is the Android companion runtime)",
            "- Google Calendar, Tasks, Gmail, Drive, Docs, Sheets, Slides, Forms, Contacts, and Photos work through the provided tools using the user's Google sign-in in Billy Companion. If a tool reports needs_sign_in or needs_scope, say which service to grant in Billy Companion.",
            "- Calendar: to create, call create_calendar_event directly (pass calendar_hint if the user names a calendar; the tool asks if unclear). Use query_calendar_freebusy or find_calendar_availability for free/busy questions. Name events and times in answers, not just counts.",
            "- Tasks: create_google_task, list_google_tasks, complete_google_task (do not answer a completion request by listing).",
            "- Gmail: search_gmail to read; to send, use prepare_gmail_send (the watch asks the user to confirm). Only use create_gmail_draft when a draft is requested. Resolve contact names through the tools before asking for an address.",
            "- Drive/Docs: search_google_drive or list_recent_google_drive_files to find files; read_google_doc/sheet/slides/form to read. To edit a Doc, read it if needed, then update_google_doc with the complete new text. Create files only when asked.",
            "- Places and maps: \"near me\" -> find_nearby_google_places; near another origin (home, work, an address) -> find_google_places_near_address. Pass the travel_mode enum (DRIVE, WALK, BICYCLE, TRANSIT, TWO_WHEELER) for any route. Navigate: open_maps_directions then show_map_directions. Travel time: get_google_route. Map preview only: show_map_directions. If a Maps tool says needs_api_key, say so.",
            "- Photos: local camera roll via the photo tools; for date requests pass both taken_after_millis and taken_before_millis. Use Google Photos tools only when the user says Google Photos. Open-web pictures: show_web_image_search.",
            "- Google Keep is not available for personal accounts; say so briefly and do not substitute another app unless asked.",
            "- What Billy remembers: get_billy_user_profile; save only when asked with remember_billy_user_fact; forget with forget_billy_user_fact.",
            "- Some app tools only open a draft or screen on the phone; describe those as opened, never as completed.",
        ).joinToString("\n")
    }
}

private data class GeminiFunctionCall(
    val id: String?,
    val name: String,
    val args: JSONObject,
)

private data class ParsedResponse(
    val model: String,
    val text: String,
    val calls: List<GeminiFunctionCall>,
    val modelContent: JSONObject,
)

private sealed interface StepResult {
    data class Ok(val parsed: ParsedResponse) : StepResult
    data class Failed(val reason: String, val tryNextModel: Boolean) : StepResult
}

private sealed interface HttpResult {
    data class Ok(val json: JSONObject) : HttpResult
    data class Error(val code: Int, val message: String, val raw: String, val transient: Boolean) : HttpResult
}

sealed interface GeminiKeyTestResult {
    data class Passed(val message: String) : GeminiKeyTestResult
    data class Failed(val reason: String) : GeminiKeyTestResult
}

data class GeminiImageCandidate(
    val index: Int,
    val label: String,
    val mimeType: String,
    val base64Data: String,
)

data class GeminiImageChoice(
    val index: Int,
    val confidence: Int,
    val reason: String,
)
