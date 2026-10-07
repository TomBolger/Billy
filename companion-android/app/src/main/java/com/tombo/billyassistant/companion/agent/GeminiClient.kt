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
class GeminiClient(preferredModel: String? = null, private val checkActive: () -> Unit = {}) {
    private val models: List<String> = buildList {
        preferredModel?.trim()?.takeIf { it.startsWith("gemini-") }?.let { add(it) }
        FALLBACK_MODELS.forEach { if (it !in this) add(it) }
    }

    val primaryModel: String get() = models.first()

    // Per-turn context; a GeminiClient serves one watch request at a time.
    private var turnContext: String = ""
    private var turnLatLng: Pair<Double, Double>? = null

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
        extraContext: String = "",
        latitude: Double? = null,
        longitude: Double? = null,
    ): CompanionAgentResult {
        turnContext = extraContext
        turnLatLng = if (latitude != null && longitude != null) latitude to longitude else null
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
        var nudgedLeak = false
        val toolNames = (0 until toolDeclarations.length()).mapNotNull { toolDeclarations.optJSONObject(it)?.optString("name") }.toSet()
        var lastOkSummary: String? = null
        val executed = mutableMapOf<String, JSONObject>()

        for (step in 0 until MAX_STEPS) {
            checkActive()
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

            val leaked = parsed.calls.isEmpty() && looksLikeLeakedToolCall(parsed.text, toolNames)
            if (leaked && !nudgedLeak && !forceText) {
                // The model wrote a tool request as text instead of calling it. Ask once more.
                nudgedLeak = true
                debugSink?.invoke("leaked tool call caught, retrying: ${parsed.text.take(300)}")
                contents.put(userText("(Billy system note: your last reply came out as a raw tool request in text. Call the tool properly, or answer the user's question in plain words.)"))
                continue
            }
            if (parsed.calls.isEmpty() || forceText) {
                val clean = if (leaked) "" else parsed.text
                val text = clean.ifBlank { suggestedReply ?: lastOkSummary.orEmpty() }
                    .ifBlank { if (watchImage != null || watchWeather != null) "" else "Sorry, I did not get an answer. Please try again." }
                return CompanionAgentResult.Passed(text = text, watchImage = watchImage, watchWeatherCurrent = watchWeather)
            }

            val responseParts = JSONArray()
            val extraParts = JSONArray()
            var endWith: String? = null
            for (call in parsed.calls) {
                checkActive()
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
                        if (e is kotlinx.coroutines.CancellationException) throw e
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
                    if (execution.endTurn && parsed.calls.size == 1) endWith = execution.finalText.orEmpty()
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
            endWith?.let { return CompanionAgentResult.Passed(text = it, watchImage = watchImage, watchWeatherCurrent = watchWeather) }
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
        // Built-in tool tiers: everything, then Search only, then none, in case a
        // model rejects a combination.
        var tier = 0
        var attempt = 0
        while (true) {
            checkActive()
            attempt++
            val body = buildBody(model, contents, declarations, tier, forceText)
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
                    if (tier < 2 && http.code == 400 &&
                        listOf("search", "tool", "server_side", "server-side", "combination", "function", "maps", "url", "code").any { it in lower }
                    ) {
                        tier++
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
        tier: Int,
        forceText: Boolean,
    ): JSONObject {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction()))))
            .put("contents", contents)
            .put("generationConfig", generationConfig(model, forceJson = false))
        val tools = JSONArray()
        val builtIns = tier < 2 && (declarations.length() == 0 || isGemini3(model))
        val search = builtIns
        val extras = builtIns && tier == 0 && declarations.length() > 0
        if (search) {
            tools.put(JSONObject().put("googleSearch", JSONObject()))
        }
        if (extras) {
            tools.put(JSONObject().put("googleMaps", JSONObject()))
            tools.put(JSONObject().put("urlContext", JSONObject()))
            tools.put(JSONObject().put("codeExecution", JSONObject()))
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
        val latLng = turnLatLng
        if (extras && latLng != null) {
            toolConfig.put(
                "retrievalConfig",
                JSONObject().put("latLng", JSONObject().put("latitude", latLng.first).put("longitude", latLng.second)),
            )
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
                debugSink?.invoke("model $model reply: ${text.take(6000)}")
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
            "Use this offset for every time you pass to a tool unless the user names another timezone." +
            (if (turnLatLng != null) "\n- The phone's current location is known; Google Maps grounding uses it for \"near me\" questions." else "") +
            (if (turnContext.isNotBlank()) "\n- " + turnContext.replace("\n", "\n- ") else "")
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

    /**
     * Gemini sometimes writes a tool call out as text ("call:default_api:show_image{...}",
     * "tool_code ...", "request: api_call: ...") instead of making it.
     */
    private fun looksLikeLeakedToolCall(text: String, toolNames: Set<String>): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val lower = t.lowercase()
        if ("default_api" in lower || lower.startsWith("tool_code") || lower.startsWith("call:") || lower.startsWith("```tool")) return true
        if (Regex("""^[\w ]{1,24}:\s*[\w ]{0,24}(api|call|tool)\w*\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        if (toolNames.any { name -> Regex("""(^|[\s:`])${Regex.escape(name)}\s*[({]""").containsMatchIn(t) }) return true
        // Mostly symbols and colons, few real words: not something to show the user.
        val words = Regex("""[A-Za-z]{3,}""").findAll(t).count()
        return t.length >= 12 && t.count { it == ':' } >= 3 && words < t.count { it == ':' } * 2
    }

    private fun normalizeApiKey(apiKey: String): String = apiKey.filterNot { it.isWhitespace() }

    private fun isGemini3(model: String): Boolean = model.startsWith("gemini-3")

    companion object {
        /** Receives raw model replies for the "Save last answer" debug file. */
        @Volatile var debugSink: ((String) -> Unit)? = null
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
            "create_google_doc", "create_google_sheet", "create_google_slides", "update_google_doc", "show_image",
            "update_calendar_event", "remember_billy_user_fact", "forget_billy_user_fact", "set_flashlight", "set_phone_volume",
        )

        private val SYSTEM_INSTRUCTION = listOf(
            // Core rules: keep in sync with app/src/pkjs/agent/prompt.js (CORE_RULES).
            "You are Billy, the user's personal assistant on their Pebble smartwatch, running through their Android phone. Be as capable as Gemini on a phone: answer anything, and act on the phone and the user's Google account.",
            "Input is voice dictation. Silently fix obvious transcription mistakes and never comment on them.",
            "",
            "TOOLS",
            "- You have real tools. When the user asks you to DO something, call the tool. Don't describe what you would do, don't tell the user to do it themselves, and don't claim success unless the tool returned status ok.",
            "- Chain tools when needed: find the event, then change or delete it; look up the contact, then text them; find the place, then navigate.",
            "- Follow-ups (\"cancel it\", \"move that to 4\", \"text her back\") refer to earlier turns and their [Actions taken], which include ids. Reuse them.",
            "- If a tool returns an error, fix the arguments and retry once, or say plainly what went wrong. needs_sign_in / needs_permission results: tell the user exactly what to turn on in Billy Companion.",
            "- Sending a text/email/reply, calling, and deleting events show a confirmation on the watch automatically; just call the tool.",
            "- Use ask_clarifying_question only when a wrong guess would send, create, or delete the wrong thing and there's no sensible default.",
            "- Built in: Google Search for anything current; Google Maps for places, hours, ratings, and travel questions (it knows the phone's location); reading web pages from URLs; and running code for exact math.",
            "",
            "WHICH TOOL",
            "- Timers = durations; alarms = clock times; \"remind me to...\" = set_reminder (watch timeline) unless the user says Google Tasks or Calendar.",
            "- Calendar: get_calendar_events to read or find; create_calendar_event; update_calendar_event to move/rename; delete_calendar_event to remove; find_free_time for availability.",
            "- Phone: send_text_message (SMS), reply_to_notification (WhatsApp/Signal/etc.), call_contact, get_notifications, control_media / play_music, set_phone_volume, set_flashlight, get_phone_status, find_my_phone, open_app, start_navigation (+ show_map for the watch). If a result says the phone is locked, tell the user to unlock it to finish.",
            "- Photos: if you have ask_my_gemini, use it for the user's photos (whole Google Photos library, shown on the watch); use find_photo only for photos from the last day or two, screenshots, or \"my last photo\". Without ask_my_gemini: find_photo searches the phone's camera roll (you'll see the photo, so describe it or answer questions about it); if it isn't found, open_google_photos_search opens the full library on the phone.",
            "- Google: Gmail (search_gmail, prepare_gmail_send to send, create_gmail_draft only when a draft is asked for), Tasks, Drive/Docs/Sheets/Slides.",
            "- If you have ask_my_gemini, it reaches the user's own Gemini account: Google Photos library, Gemini's saved info and past chats, Gems, Keep, YouTube, Google Home. If it errors, carry on with your normal tools.",
            "- Without ask_my_gemini, Google Keep isn't available to third-party apps; say so briefly if asked.",
            "",
            "INFO CARDS",
            "- Prefer a card when one fits: get_weather, show_number (one big number), set_timer (countdown), show_map, find_photo, show_image. When a card is shown, add context in text; don't repeat it.",
            "- Be generous with show_image as a visual aid: when the user asks about or mentions something with a recognizable look (a landmark, place, animal, plant, person, artwork, building, food), show a picture along with the answer, without being asked. Skip it when the point is fine detail the small, low-color screen can't show (charts, diagrams, text, close-up specifics).",
            "",
            "REPLIES",
            "- The watch screen is tiny: usually 1-4 short lines. Lead with the answer. Plain text only, no markdown, links, or citations. \"- \" bullets for short lists.",
            "- Content you write into emails, documents, texts, or events is not limited by the watch; write it fully and naturally.",
            "- Don't end with an open question. If you truly need an answer, use ask_clarifying_question.",
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
