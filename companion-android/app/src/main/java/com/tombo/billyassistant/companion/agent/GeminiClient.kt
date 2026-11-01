package com.tombo.billyassistant.companion.agent

import android.os.SystemClock
import com.tombo.billyassistant.companion.agent.tools.CompanionToolExecution
import com.tombo.billyassistant.companion.agent.tools.WatchImage
import com.tombo.billyassistant.companion.agent.tools.WatchToolsCompanionTool
import com.tombo.billyassistant.companion.agent.tools.WatchWeatherCurrent
import com.tombo.billyassistant.companion.diagnostics.DiagnosticsLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Gemini generateContent client and tool loop for Billy Companion.
 *
 * Rules that keep tool use reliable (mirrors app/src/pkjs/agent/gemini.js):
 * - One API surface (generateContent). No fallback that flattens tool results into text.
 * - Google Search + function declarations are sent together with
 *   toolConfig.includeServerSideToolInvocations, which Gemini 3 requires.
 * - One model per turn: the user's chosen one (or DEFAULT_MODEL). There is no
 *   fallback model and no dropping of built-in tools: if Gemini rejects or fails
 *   a request, the turn fails with a plain message (and a problem report), so a
 *   bug gets reported instead of quietly getting a weaker answer.
 * - Only these are retried: one re-ask on a completely empty answer, and one
 *   retry of the same model after a connection-level failure or HTTP 503. Every
 *   retry is written to DiagnosticsLog.
 * - Model content is replayed untouched, all function responses of one step go
 *   back in one user turn with their call ids.
 * - A tool's finalText is a suggested reply, not an early exit, so the model can
 *   chain further tools or recover from an error.
 */
class GeminiClient(preferredModel: String? = null, private val checkActive: () -> Unit = {}) {
    val primaryModel: String = preferredModel?.trim()?.takeIf { it.startsWith("gemini-") } ?: DEFAULT_MODEL

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
        val result = when (val http = post(key, primaryModel, body)) {
            is HttpResult.Ok -> http
            is HttpResult.Error -> {
                // Not "no match": ranking itself failed, so make sure it shows up in the problem report.
                DiagnosticsLog.problem("Gemini photo ranking failed: ${http.message}")
                return null
            }
        }
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
            val parsed = when (val response = generateStep(key, primaryModel, contents, toolDeclarations, forceText)) {
                is StepResult.Failed -> return CompanionAgentResult.Failed(response.reason)
                is StepResult.Ok -> response.parsed
            }
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
                if (text.isBlank() && watchImage == null && watchWeather == null) {
                    DiagnosticsLog.event("no usable text at step ${step + 1} (leakedToolCall=$leaked)")
                    return CompanionAgentResult.Failed("Gemini ($primaryModel) finished without giving Billy any words to show.")
                }
                return CompanionAgentResult.Passed(text = text, watchImage = watchImage, watchWeatherCurrent = watchWeather)
            }

            val responseParts = JSONArray()
            val extraParts = JSONArray()
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
                    val toolStartedAt = System.currentTimeMillis()
                    val ran = runCatching { toolExecutor(call.name, call.args) }
                    val toolMs = System.currentTimeMillis() - toolStartedAt
                    debugSink?.invoke("TIMING tool ${call.name}: $toolMs ms")
                    DiagnosticsLog.addTool(call.name, toolMs, ran.getOrNull()?.response?.optString("status", "ok") ?: "crashed")
                    ran.getOrElse { e ->
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

    /**
     * One generateContent call on [model] with the full tool set. Retries only:
     * a completely empty answer (once), and a connection-level failure or HTTP 503
     * (once, same model). Everything else fails with a plain message.
     */
    private fun generateStep(
        apiKey: String,
        model: String,
        contents: JSONArray,
        declarations: JSONArray,
        forceText: Boolean,
    ): StepResult {
        var emptyRetried = false
        var connectionRetried = false
        var isRetry = false
        while (true) {
            checkActive()
            val body = buildBody(model, contents, declarations, forceText)
            when (val http = post(apiKey, model, body, isRetry)) {
                is HttpResult.Ok -> {
                    val parsed = parseResponse(model, http.json)
                    if (parsed.calls.isEmpty() && parsed.text.isBlank()) {
                        val why = http.emptyReason
                        if (!emptyRetried) {
                            emptyRetried = true
                            isRetry = true
                            DiagnosticsLog.event("empty answer from $model ($why); asking once more")
                            continue
                        }
                        return StepResult.Failed("Gemini ($model) sent an empty answer twice ($why).")
                    }
                    return StepResult.Ok(parsed)
                }
                is HttpResult.Error -> {
                    if (http.retryable && !connectionRetried) {
                        connectionRetried = true
                        isRetry = true
                        DiagnosticsLog.event("retrying $model once after ${http.detail}")
                        Thread.sleep(RETRY_DELAY_MS)
                        continue
                    }
                    return StepResult.Failed(http.message)
                }
            }
        }
    }

    private fun buildBody(
        model: String,
        contents: JSONArray,
        declarations: JSONArray,
        forceText: Boolean,
    ): JSONObject {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction()))))
            .put("contents", contents)
            .put("generationConfig", generationConfig(model, forceJson = false))
        val tools = JSONArray()
        // Search + function calling together needs Gemini 3. The built-in tools are always sent in full.
        val builtIns = declarations.length() == 0 || isGemini3(model)
        val search = builtIns
        val extras = builtIns && declarations.length() > 0
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

    /** One timed, diagnosed HTTP call. Written to the TIMING debug line and to DiagnosticsLog. */
    private fun post(apiKey: String, model: String, body: JSONObject, isRetry: Boolean = false): HttpResult {
        val startedAt = SystemClock.elapsedRealtime()
        val call = JSONObject()
            .put("model", model)
            .put("retry", isRetry)
            .put("start", DiagnosticsLog.deviceState())
        val result = postTimed(apiKey, model, body, call)
        call.put("totalMs", SystemClock.elapsedRealtime() - startedAt)
        call.put("end", DiagnosticsLog.deviceState())
        DiagnosticsLog.addCall(call)
        val outcome = when (result) {
            is HttpResult.Ok -> "ok"
            is HttpResult.Error -> "error ${result.code} ${result.detail.take(40)}"
        }
        debugSink?.invoke(
            "TIMING model call $model: ${call.optLong("totalMs")} ms ($outcome) " +
                "[connect ${call.optLong("connectMs", -1)} ms, ttfb ${call.optLong("ttfbMs", -1)} ms, body ${call.optLong("bodyMs", -1)} ms]",
        )
        return result
    }

    /**
     * The request, split into connect / send / wait-for-headers / read-body so a stall
     * can be pinned on a phase. Fills [call] with the numbers as it goes.
     */
    private fun postTimed(apiKey: String, model: String, body: JSONObject, call: JSONObject): HttpResult {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        call.put("reqBytes", bytes.size)
        var phase = "connect"
        var connection: HttpURLConnection? = null
        try {
            val conn = URL("$BASE_URL$model:generateContent").openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            // Streaming mode makes the body go out on write, so "send" and "wait" are separate.
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)

            val t0 = SystemClock.elapsedRealtime()
            conn.connect()
            val t1 = SystemClock.elapsedRealtime()
            call.put("connectMs", t1 - t0)
            phase = "send"
            conn.outputStream.use { it.write(bytes) }
            val t2 = SystemClock.elapsedRealtime()
            call.put("sendMs", t2 - t1)
            phase = "wait"
            val code = conn.responseCode
            val t3 = SystemClock.elapsedRealtime()
            call.put("ttfbMs", t3 - t2)
            call.put("code", code)
            phase = "read"
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            call.put("bodyMs", SystemClock.elapsedRealtime() - t3)
            call.put("respBytes", text.toByteArray(Charsets.UTF_8).size)
            if (code !in 200..299) {
                val message = friendlyError(code, text, model)
                call.put("error", message.take(200))
                return HttpResult.Error(code, message, "HTTP $code", retryable = code == 503)
            }
            debugSink?.invoke("model $model reply: ${text.take(6000)}")
            val root = try {
                JSONObject(text)
            } catch (e: Exception) {
                val message = "Gemini ($model) sent a reply Billy couldn't read."
                call.put("error", message)
                return HttpResult.Error(code, message, "unreadable reply", retryable = false)
            }
            val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
            val finishReason = candidate?.optString("finishReason").orEmpty()
            val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            call.put("finishReason", finishReason)
            if (blockReason.isNotBlank()) call.put("blockReason", blockReason)
            root.optJSONObject("usageMetadata")?.let { usage ->
                call.put("promptTokens", usage.optInt("promptTokenCount", -1))
                call.put("thoughtsTokens", usage.optInt("thoughtsTokenCount", 0))
                call.put("candidatesTokens", usage.optInt("candidatesTokenCount", 0))
                call.put("totalTokens", usage.optInt("totalTokenCount", -1))
            }
            val emptyReason = when {
                blockReason.isNotBlank() -> "blocked: $blockReason"
                finishReason.isNotBlank() -> "finishReason $finishReason"
                else -> "no reason given"
            }
            return HttpResult.Ok(root, emptyReason)
        } catch (e: SocketTimeoutException) {
            val message = when (phase) {
                "connect" -> "Phone couldn't connect to Gemini within ${CONNECT_TIMEOUT_MS / 1000} seconds."
                "read" -> "Gemini ($model) stopped sending its answer partway through (${READ_TIMEOUT_MS / 1000} seconds with no data)."
                else -> "Gemini ($model) didn't respond within ${READ_TIMEOUT_MS / 1000} seconds."
            }
            call.put("error", "timeout in $phase phase")
            return HttpResult.Error(0, message, "timeout in $phase phase", retryable = false)
        } catch (e: Exception) {
            val detail = "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(120)
            call.put("error", "$detail ($phase phase)")
            val connectionLevel = e is UnknownHostException || e is ConnectException || e is SocketException ||
                listOf("reset", "refused", "broken pipe").any { it in e.message.orEmpty().lowercase() }
            val message = when {
                DiagnosticsLog.isOffline() -> "Phone has no internet connection."
                e is UnknownHostException -> "Phone couldn't look up Gemini's address. Its internet connection may be down."
                connectionLevel -> "The connection to Gemini dropped (${e.message.orEmpty().take(60).ifBlank { e.javaClass.simpleName }})."
                else -> "Billy couldn't reach Gemini (${e.message.orEmpty().take(60).ifBlank { e.javaClass.simpleName }})."
            }
            return HttpResult.Error(0, message, detail, retryable = connectionLevel)
        } finally {
            runCatching { connection?.disconnect() }
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
            code == 429 -> "Your free Gemini quota is used up for now (429)."
            code == 503 -> "Gemini is overloaded right now (503)."
            code == 404 -> "Gemini doesn't know the model $model (404): ${message.take(MAX_ERROR_LENGTH)}"
            code == 400 -> "Google rejected Billy's request to $model (400): ${message.take(MAX_ERROR_LENGTH)}"
            else -> "Gemini ($model) returned error $code: ${message.take(MAX_ERROR_LENGTH)}"
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
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val MAX_STEPS = 6
        private const val MAX_OUTPUT_TOKENS = 4096
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 25_000
        private const val RETRY_DELAY_MS = 900L
        private const val MAX_ERROR_LENGTH = 180
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
            "- If you have ask_my_gemini, it reaches the user's own Gemini account: Google Photos library, Gemini's saved info and past chats, Gems, Keep, YouTube, Google Home. If it errors, tell the user plainly that their Gemini account didn't answer and why; don't answer from other sources unless they ask.",
            "- Without ask_my_gemini, Google Keep isn't available to third-party apps; say so briefly if asked.",
            "",
            "INFO CARDS",
            "- Prefer a card when one fits: get_weather, show_number (one big number), set_timer (countdown), show_map, find_photo, show_image. When a card is shown, add context in text; don't repeat it.",
            "- Pictures are slow and push the text answer down the screen, so use show_image sparingly: only when the user asks to see something (\"show me\", \"what does X look like\", \"picture of\") or a picture is clearly the best answer. For plain facts, conversation, and general questions, answer in text only, even if the subject has a recognizable look. Skip it for fine detail the small, low-color screen can't show (charts, diagrams, text, close-up specifics).",
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
    data class Failed(val reason: String) : StepResult
}

private sealed interface HttpResult {
    /** [emptyReason] explains an answer with no text and no tool call (finishReason or blockReason). */
    data class Ok(val json: JSONObject, val emptyReason: String) : HttpResult
    /** [detail] is a short technical note for the diagnostics; [message] is what the user sees. */
    data class Error(val code: Int, val message: String, val detail: String, val retryable: Boolean) : HttpResult
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
