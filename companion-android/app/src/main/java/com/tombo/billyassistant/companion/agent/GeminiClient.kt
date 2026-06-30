package com.tombo.billyassistant.companion.agent

import com.tombo.billyassistant.companion.agent.tools.ClarificationCard
import com.tombo.billyassistant.companion.agent.tools.CompanionToolExecution
import com.tombo.billyassistant.companion.agent.tools.WatchWeatherCurrent
import com.tombo.billyassistant.companion.agent.tools.WatchImage
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class GeminiClient {
    fun describeConfiguration(apiKey: String): String {
        return if (apiKey.isBlank()) {
            "Gemini API key is not configured."
        } else {
            "Gemini API key is stored locally. Use Verify key to check it."
        }
    }

    fun createRequest(prompt: String, apiKey: String): GeminiRequestPlan {
        if (prompt.isBlank()) {
            return GeminiRequestPlan.Invalid("Prompt is blank.")
        }
        if (normalizeApiKey(apiKey).isBlank()) {
            return GeminiRequestPlan.Invalid("Gemini API key is missing.")
        }

        return GeminiRequestPlan.Ready(
            model = DEFAULT_MODEL,
            prompt = prompt.trim(),
        )
    }

    fun testKey(apiKey: String): GeminiKeyTestResult {
        val normalizedKey = normalizeApiKey(apiKey)
        if (normalizedKey.isBlank()) {
            return GeminiKeyTestResult.Failed("Gemini API key is missing.")
        }

        return try {
            val connection = (URL(generateContentUrl(DEFAULT_MODEL)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", normalizedKey)
            }
            val body = JSONObject()
                .put("contents", JSONArray().put(userTextContent("Reply with OK.")))
                .put("generationConfig", JSONObject().put("candidateCount", 1).put("maxOutputTokens", 8))
                .toString()
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (responseCode in 200..299) {
                GeminiKeyTestResult.Passed("Gemini key works with $DEFAULT_MODEL.")
            } else {
                GeminiKeyTestResult.Failed(
                    "Gemini returned HTTP $responseCode: ${extractErrorMessage(responseText)}",
                )
            }
        } catch (e: Exception) {
            GeminiKeyTestResult.Failed("Gemini key verification failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun generateText(prompt: String, apiKey: String): GeminiTextResult {
        val result = generateWithTools(
            prompt = prompt,
            apiKey = apiKey,
            toolDeclarations = JSONArray(),
            toolExecutor = { name, _ ->
                CompanionToolExecution(JSONObject().put("error", "Tool not available: $name"))
            },
        )
        return when (result) {
            is CompanionAgentResult.Passed -> GeminiTextResult.Passed(result.text)
            is CompanionAgentResult.Failed -> GeminiTextResult.Failed(result.reason)
        }
    }

    fun chooseImageCandidate(
        prompt: String,
        apiKey: String,
        candidates: List<GeminiImageCandidate>,
    ): GeminiImageChoice? {
        val normalizedKey = normalizeApiKey(apiKey)
        if (normalizedKey.isBlank() || candidates.isEmpty()) {
            return null
        }
        val parts = JSONArray()
            .put(
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
                    JSONObject()
                        .put("mimeType", candidate.mimeType)
                        .put("data", candidate.base64Data),
                ),
            )
        }
        val response = generateContentWithContents(
            apiKey = normalizedKey,
            model = DEFAULT_MODEL,
            contents = JSONArray().put(userContent(parts)),
            toolDeclarations = JSONArray(),
        )
        if (response !is GeminiContentResult.Passed) {
            return null
        }
        val text = extractResponseText(response.raw)
        val json = runCatching { JSONObject(text) }.getOrNull()
        val jsonIndex = json?.optInt("index", -1) ?: -1
        if (jsonIndex >= 0 && candidates.any { it.index == jsonIndex }) {
            return GeminiImageChoice(
                index = jsonIndex,
                confidence = json?.optInt("confidence", 50)?.coerceIn(0, 100) ?: 50,
                reason = json?.optString("reason").orEmpty(),
            )
        }
        val regexIndex = Regex("""\b(\d{1,2})\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return regexIndex
            ?.takeIf { index -> candidates.any { it.index == index } }
            ?.let { index -> GeminiImageChoice(index = index, confidence = 45, reason = text.take(80)) }
    }

    fun generateWithTools(
        prompt: String,
        apiKey: String,
        toolDeclarations: JSONArray,
        toolExecutor: (String, JSONObject) -> CompanionToolExecution,
    ): CompanionAgentResult {
        val normalizedKey = normalizeApiKey(apiKey)
        if (normalizedKey.isBlank()) {
            return CompanionAgentResult.Failed("Gemini API key is missing in the companion app.")
        }
        if (prompt.isBlank()) {
            return CompanionAgentResult.Failed("Prompt is blank.")
        }

        val contents = JSONArray().put(
            userTextContent(
                localTimeInstruction() +
                    " If this request uses relative dates or times, use that current date/time. User request: " +
                    prompt.trim(),
            ),
        )
        var pendingWatchImage: WatchImage? = null
        var pendingWatchWeather: WatchWeatherCurrent? = null
        repeat(MAX_TOOL_ITERATIONS) {
            val response = generateStructuredBestEffort(normalizedKey, contents, toolDeclarations)
            if (response is GeminiContentResult.Failed) {
                return CompanionAgentResult.Failed(response.reason)
            }
            response as GeminiContentResult.Passed

            val functionCalls = extractFunctionCalls(response.raw)
            if (functionCalls.isEmpty()) {
                val text = extractResponseText(response.raw).ifBlank {
                    "I did not receive a usable answer."
                }
                maybeClarificationCardFromText(text, prompt)?.let { card ->
                    return CompanionAgentResult.Passed(
                        text = "",
                        watchImage = pendingWatchImage,
                        watchWeatherCurrent = pendingWatchWeather,
                        clarificationCard = card,
                    )
                }
                return CompanionAgentResult.Passed(
                    text = text,
                    watchImage = pendingWatchImage,
                    watchWeatherCurrent = pendingWatchWeather,
                )
            }
            extractFirstCandidateContent(response.raw)?.let { modelContent ->
                contents.put(modelContent.withRole("model"))
            }
            functionCalls.forEach { call ->
                val result = toolExecutor(call.name, call.args)
                result.watchImage?.let { pendingWatchImage = it }
                result.watchWeatherCurrent?.let { pendingWatchWeather = it }
                result.clarificationCard?.let {
                    return CompanionAgentResult.Passed(
                        text = "",
                        watchImage = pendingWatchImage,
                        watchWeatherCurrent = pendingWatchWeather,
                        clarificationCard = it,
                    )
                }
                contents.put(functionResponseContent(call.name, result.response))
                if (result.followUpParts.length() > 0) {
                    contents.put(
                        userContent(
                            JSONArray()
                                .put(JSONObject().put("text", "Use the tool result and attached data to answer the user's watch request. Be concrete and do not answer with only a count."))
                                .appendAll(result.followUpParts),
                        ),
                    )
                }
                result.finalText?.let {
                    return CompanionAgentResult.Passed(
                        text = it,
                        watchImage = pendingWatchImage,
                        watchWeatherCurrent = pendingWatchWeather,
                    )
                }
            }
        }

        return CompanionAgentResult.Failed("I used too many tool steps and stopped before finishing.")
    }

    private fun maybeClarificationCardFromText(text: String, prompt: String): ClarificationCard? {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            return null
        }
        val question = extractUserFacingQuestion(normalized) ?: return null
        val optionLines = normalized
            .lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                Regex("""^(?:[-*+•‣◦]|\d+[.)]|[A-Da-d][.)])\s+(.+)$""").find(line)?.groupValues?.getOrNull(1)?.trim()
            }
            .map { it.trim().trimEnd('.') }
            .filter { it.isNotBlank() }
            .map { it.take(64) }
            .distinct()
            .take(3)
            .toList()
        return ClarificationCard(
            question = question,
            context = prompt.trim().take(180),
            options = optionLines.takeIf { it.size >= 2 }.orEmpty(),
        )
    }

    private fun extractUserFacingQuestion(text: String): String? {
        val questionLine = text
            .lineSequence()
            .map { it.trim() }
            .filter { it.contains("?") }
            .lastOrNull()
            ?: return null
        val question = questionLine.substringBeforeLast("?").trim().plus("?").take(120)
        val lower = question.lowercase()
        val directQuestion = listOf(
            "which ",
            "what ",
            "when ",
            "where ",
            "who ",
            "how ",
            "do you ",
            "would you ",
            "should i ",
            "could you ",
            "can you ",
            "please clarify",
            "clarify",
            "choose ",
            "select ",
            "want ",
            "need ",
        ).any { it in lower }
        return if (directQuestion || text.trimEnd().endsWith("?")) question else null
    }

    private fun generateStructuredBestEffort(
        apiKey: String,
        contents: JSONArray,
        toolDeclarations: JSONArray,
    ): GeminiContentResult {
        var lastFailure: GeminiContentResult.Failed? = null
        MODEL_CANDIDATES.forEach { model ->
            val content = generateContentWithContents(apiKey, model, contents, toolDeclarations)
            if (content is GeminiContentResult.Passed && content.isUsable()) {
                return content
            }
            if (content is GeminiContentResult.Failed) {
                lastFailure = content
                if (!shouldTryNextModel(content.reason)) {
                    return content
                }
            } else {
                lastFailure = GeminiContentResult.Failed("Gemini returned an empty answer.")
            }
        }
        return lastFailure ?: GeminiContentResult.Failed("Gemini returned an empty answer.")
    }

    private fun generateBestEffort(
        apiKey: String,
        input: String,
        toolDeclarations: JSONArray,
    ): GeminiContentResult {
        return generateBestEffort(apiKey, JSONArray().put(JSONObject().put("text", input)), input, toolDeclarations)
    }

    private fun generateBestEffort(
        apiKey: String,
        inputParts: JSONArray,
        textFallback: String,
        toolDeclarations: JSONArray,
    ): GeminiContentResult {
        var lastFailure: GeminiContentResult.Failed? = null
        val canUseInteractionFallback = !inputParts.hasInlineData()
        MODEL_CANDIDATES.forEach { model ->
            val content = generateContent(apiKey, model, inputParts, toolDeclarations)
            if (content is GeminiContentResult.Passed && content.isUsable()) {
                return content
            }
            if (content is GeminiContentResult.Failed) {
                lastFailure = content
                if (canUseInteractionFallback && shouldTryInteractionFallback(content.reason)) {
                    val interaction = generateInteraction(apiKey, model, textFallback, toolDeclarations)
                    if (interaction is GeminiContentResult.Passed && interaction.isUsable()) {
                        return interaction
                    }
                    if (interaction is GeminiContentResult.Failed) {
                        lastFailure = interaction
                    }
                }
                if (!shouldTryNextModel(content.reason) && !shouldTryInteractionFallback(content.reason)) {
                    return content
                }
            } else {
                lastFailure = GeminiContentResult.Failed("Gemini returned an empty answer.")
            }
        }
        return lastFailure ?: GeminiContentResult.Failed("Gemini returned an empty answer.")
    }

    private fun generateInteraction(
        apiKey: String,
        model: String,
        input: String,
        toolDeclarations: JSONArray,
    ): GeminiContentResult {
        val first = postInteraction(apiKey, model, input, toolDeclarations)
        return if (first is GeminiContentResult.Failed && shouldRetryInteractionWithContentList(first.reason)) {
            postInteraction(apiKey, model, interactionInput(input), toolDeclarations)
        } else {
            first
        }
    }

    private fun postInteraction(
        apiKey: String,
        model: String,
        input: Any,
        toolDeclarations: JSONArray,
    ): GeminiContentResult {
        return try {
            val connection = (URL(INTERACTIONS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = REQUEST_TIMEOUT_MS
                readTimeout = REQUEST_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }
            val body = JSONObject()
                .put("model", model)
                .put("input", input)
                .put("system_instruction", SYSTEM_INSTRUCTION + " " + localTimeInstruction())
            val tools = interactionTools(toolDeclarations)
            if (toolDeclarations.length() > 0) {
                body.put("tools", tools)
            }
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (responseCode in 200..299) {
                GeminiContentResult.Passed(responseText)
            } else {
                GeminiContentResult.Failed(
                    "Gemini returned HTTP $responseCode: ${extractErrorMessage(responseText)}",
                )
            }
        } catch (e: Exception) {
            GeminiContentResult.Failed("Gemini request failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun generateContent(
        apiKey: String,
        model: String,
        inputParts: JSONArray,
        toolDeclarations: JSONArray,
    ): GeminiContentResult {
        return generateContentWithContents(
            apiKey = apiKey,
            model = model,
            contents = JSONArray().put(userContent(inputParts)),
            toolDeclarations = toolDeclarations,
        )
    }

    private fun generateContentWithContents(
        apiKey: String,
        model: String,
        contents: JSONArray,
        toolDeclarations: JSONArray,
    ): GeminiContentResult {
        return try {
            val connection = (URL(generateContentUrl(model)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = REQUEST_TIMEOUT_MS
                readTimeout = REQUEST_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }
            val body = JSONObject()
                .put("systemInstruction", systemInstruction())
                .put("contents", contents)
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("candidateCount", 1)
                        .put("maxOutputTokens", MAX_OUTPUT_TOKENS),
                )
            if (toolDeclarations.length() > 0) {
                body.put(
                    "tools",
                    JSONArray().put(JSONObject().put("function_declarations", toolDeclarations)),
                )
            }
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (responseCode in 200..299) {
                GeminiContentResult.Passed(responseText)
            } else {
                GeminiContentResult.Failed(
                    "Gemini returned HTTP $responseCode: ${extractErrorMessage(responseText)}",
                )
            }
        } catch (e: Exception) {
            GeminiContentResult.Failed("Gemini request failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun interactionTools(toolDeclarations: JSONArray): JSONArray {
        val tools = JSONArray()
        for (i in 0 until toolDeclarations.length()) {
            val declaration = toolDeclarations.optJSONObject(i) ?: continue
            tools.put(JSONObject(declaration.toString()).put("type", "function"))
        }
        return tools
    }

    private fun interactionInput(text: String): JSONArray {
        return JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", text),
        )
    }

    private fun extractErrorMessage(responseText: String): String {
        if (responseText.isBlank()) {
            return "empty error response"
        }
        val message = try {
            val message = JSONObject(responseText)
                .optJSONObject("error")
                ?.optString("message")
                .orEmpty()
            message.ifBlank { responseText }
        } catch (_: Exception) {
            responseText
        }
        if (
            message.contains("generativelanguage.googleapis.com", ignoreCase = true) &&
            message.contains("blocked", ignoreCase = true)
        ) {
            return "Gemini API key is blocked. Enable the Gemini API and allow generativelanguage.googleapis.com. If restricted to Android apps, use Billy Companion package/SHA."
                .take(MAX_ERROR_LENGTH)
        }
        if (message.contains("invalid authentication credentials", ignoreCase = true)) {
            return "Gemini did not accept this value as an API key. Use a Gemini API key from Google AI Studio, not an OAuth client ID, client secret, or access token."
                .take(MAX_ERROR_LENGTH)
        }
        return message.take(MAX_ERROR_LENGTH)
    }

    private fun extractResponseText(responseText: String): String {
        return try {
            val root = JSONObject(responseText)
            root.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
            val output = root.optJSONArray("output")
            if (output != null) {
                buildString {
                    for (i in 0 until output.length()) {
                        append(output.optJSONObject(i)?.optString("text").orEmpty())
                    }
                }.takeIf { it.isNotBlank() }?.let { return it }
            }
            val steps = root.optJSONArray("steps")
            if (steps != null) {
                buildString {
                    for (i in 0 until steps.length()) {
                        val step = steps.optJSONObject(i) ?: continue
                        val modelOutput = step.optJSONObject("model_output")
                            ?: step.optJSONObject("modelOutput")
                            ?: if (step.optString("type") == "model_output") step else null
                        val content = modelOutput?.optJSONArray("content") ?: continue
                        for (j in 0 until content.length()) {
                            append(content.optJSONObject(j)?.optString("text").orEmpty())
                        }
                    }
                }.takeIf { it.isNotBlank() }?.let { return it }
            }
            val candidates = root.optJSONArray("candidates")
            val parts = candidates
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
            buildString {
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        append(parts.optJSONObject(i)?.optString("text").orEmpty())
                    }
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractFunctionCalls(responseText: String): List<GeminiFunctionCall> {
        return try {
            val root = JSONObject(responseText)
            val stepCalls = extractStepFunctionCalls(root)
            if (stepCalls.isNotEmpty()) {
                return stepCalls
            }
            val parts = root
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?: return emptyList()
            buildList {
                for (i in 0 until parts.length()) {
                    val functionCall = parts.optJSONObject(i)?.optJSONObject("functionCall") ?: continue
                    add(
                        GeminiFunctionCall(
                            name = functionCall.optString("name"),
                            args = functionCall.optJSONObject("args") ?: JSONObject(),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractStepFunctionCalls(root: JSONObject): List<GeminiFunctionCall> {
        val steps = root.optJSONArray("steps") ?: return emptyList()
        return buildList {
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                val functionCall = step.optJSONObject("function_call")
                    ?: step.optJSONObject("functionCall")
                    ?: step.takeIf { it.optString("type") == "function_call" }
                    ?: continue
                val name = functionCall.optString("name")
                if (name.isBlank()) {
                    continue
                }
                add(
                    GeminiFunctionCall(
                        name = name,
                        args = functionCall.optJSONObject("arguments")
                            ?: functionCall.optJSONObject("args")
                            ?: JSONObject(),
                    ),
                )
            }
        }
    }

    private fun extractFirstCandidateContent(responseText: String): JSONObject? {
        return try {
            JSONObject(responseText)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
        } catch (_: Exception) {
            null
        }
    }

    private fun systemInstruction(): JSONObject {
        return JSONObject().put(
            "parts",
            JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION + " " + localTimeInstruction())),
        )
    }

    private fun localTimeInstruction(): String {
        val now = ZonedDateTime.now()
        return "The Android phone local time is ${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now)}. " +
            "The Android timezone is ${now.zone.id}. Interpret relative dates using this timezone unless the user explicitly names another timezone."
    }

    private fun userTextContent(text: String): JSONObject {
        return userContent(JSONArray().put(JSONObject().put("text", text)))
    }

    private fun userContent(parts: JSONArray): JSONObject {
        return JSONObject()
            .put("role", "user")
            .put("parts", parts)
    }

    private fun functionResponseContent(name: String, result: JSONObject): JSONObject {
        return JSONObject()
            .put("role", "user")
            .put(
                "parts",
                JSONArray().put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject()
                            .put("name", name)
                            .put("response", result),
                    ),
                ),
            )
    }

    private fun JSONObject.withRole(role: String): JSONObject {
        val copy = JSONObject(toString())
        if (copy.optString("role").isBlank()) {
            copy.put("role", role)
        }
        return copy
    }

    private fun JSONArray.appendAll(items: JSONArray): JSONArray {
        for (i in 0 until items.length()) {
            put(items.get(i))
        }
        return this
    }

    private fun JSONArray.hasInlineData(): Boolean {
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            if (item.has("inlineData") || item.has("inline_data")) {
                return true
            }
        }
        return false
    }

    private fun normalizeApiKey(apiKey: String): String {
        return apiKey.filterNot { it.isWhitespace() }
    }

    private fun GeminiContentResult.Passed.isUsable(): Boolean {
        return extractResponseText(raw).isNotBlank() || extractFunctionCalls(raw).isNotEmpty()
    }

    private fun shouldRetryInteractionWithContentList(reason: String): Boolean {
        val text = reason.lowercase()
        return "400" in text &&
            ("input" in text || "top-level" in text || "top level" in text || "list" in text)
    }

    private fun shouldTryInteractionFallback(reason: String): Boolean {
        val text = reason.lowercase()
        return "400" in text &&
            ("tool" in text || "function" in text || "input" in text || "top-level" in text || "top level" in text || "list" in text || "unsupported" in text)
    }

    private fun shouldTryNextModel(reason: String): Boolean {
        val text = reason.lowercase()
        return "429" in text ||
            "500" in text ||
            "502" in text ||
            "503" in text ||
            "empty answer" in text ||
            "api_error" in text ||
            "high demand" in text ||
            "overloaded" in text ||
            "unavailable" in text ||
            "timed out" in text ||
            "timeout" in text
    }

    private fun generateContentUrl(model: String): String {
        return "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.1-flash-lite"
        private val MODEL_CANDIDATES = listOf(DEFAULT_MODEL, "gemini-3.5-flash", "gemini-2.5-flash")
        private const val INTERACTIONS_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
        private const val MAX_ERROR_LENGTH = 220
        private const val MAX_OUTPUT_TOKENS = 260
        private const val MAX_TOOL_ITERATIONS = 4
        private const val REQUEST_TIMEOUT_MS = 18_000
        private const val SYSTEM_INSTRUCTION =
            "You are Billy, a concise assistant answering on a Pebble watch. " +
                "Default to enough detail to be useful: usually 2-4 short watch lines. Avoid vague one-line answers. Use fragments when they are clear. " +
                "Use Pebble-safe formatting only: short lines, line breaks, and '- ' bullets. Do not use markdown asterisks, code fences, tables, headings, citations, or long lists unless the user asks. " +
                "Never ask the user an open-ended question in final text. If you need any user answer or decision, call ask_clarifying_question and provide 1-3 short likely options; the watch automatically adds Dictate for answers not listed. This includes yes/no questions, missing details, ambiguous choices, and follow-up questions. Ask only one question at a time. Do not end a normal text answer with a question. " +
                "When the request is ambiguous and a wrong guess could create, change, delete, message, navigate, spend time, or use private data incorrectly, call ask_clarifying_question instead of guessing. Prefer clarification for missing event time, calendar/account, reminder date, contact/person, destination, app/service, or which private result the user means. Do not ask if a safe default is obvious. Do not offer notes as a clarification option unless the user explicitly asks for Keep or notes. " +
                "For weather, local forecast, umbrella, temperature, wind, or weather-card requests, call get_weather; do not claim Billy lacks local weather data unless the tool says location permission is missing. Translate numbers into plain human guidance. For Calendar and Tasks, list useful names and times, not just counts. For photo analysis, mention concrete visible details. " +
                "Silently correct likely dictation errors. " +
                "For open-web image requests, use show_web_image_search; do not search Drive unless the user asks for their Drive files. For nearest, nearby, near me, closest, local, or around me place requests, call find_nearby_google_places first. Use included_type when clear, such as train_station for train/Amtrak stations, transit_station for transit stops, airport for airports, restaurant for restaurants, cafe for coffee, gas_station for gas, pharmacy for pharmacies, and hotel for hotels. Before any Maps tool call, infer the user's intended travel mode semantically and pass the canonical travel_mode enum: DRIVE, WALK, BICYCLE, TRANSIT, or TWO_WHEELER. Do not omit travel_mode when the user implies a non-driving mode. For navigation to a nearby place, pass the selected nearby result label/name/address and travel_mode to open_maps_directions, then pass the same result name/address, destination_latitude, destination_longitude, and travel_mode to show_map_directions; do not geocode a selected result when coordinates are already available. For a specific destination navigation request, call open_maps_directions with the user's destination text and travel_mode first, then call show_map_directions with the same travel_mode and the destination coordinates if known or destination text if not. For route summaries, travel-time questions, or how-to-get-there reasoning, call get_google_route with the same travel_mode. For map-card-only or preview-map requests, call show_map_directions and do not open phone navigation. `show_map_directions` can create an OpenStreetMap card when no Maps key is configured. Google Maps Platform tools such as nearby search, routes, geocoding, and time-zone lookup require a Maps key; if one of those returns needs_api_key, say that briefly instead of pretending. If a Maps key is configured and a Google Maps Platform call fails, report the failure instead of substituting OpenStreetMap. " +
                "Use the provided companion tools for private phone or Google-synced data when relevant. " +
                "Calendar reads should prefer Google Calendar API results when available; Android Calendar Provider is fallback and may contain local ghosts. For calendar creation, call create_calendar_event with title/start/end. If the user names a calendar in words such as personal, primary, work, family, or an account email, pass that phrase as calendar_hint; do not list calendars first. If the destination calendar is unclear, omit calendar_id and calendar_hint so the tool can ask with a picker. Set create_meet_link=true when the user asks for a meeting link, video call, Google Meet, or conference call. For availability, free/busy, or scheduling options, use query_calendar_freebusy or find_calendar_availability before answering. Do not answer a create-event request by merely listing calendars. If the user asks to remove Billy/Bobby ghost calendar events, use delete_billy_calendar_ghosts. Photo tools access local/selected Android photos when permission is granted. For photo requests with dates like yesterday, Tuesday, last week, last month, or this day last year, pass both taken_after_millis and taken_before_millis as a closed local-time range; never represent a day/week/month request with only a lower bound. Use media_type=photo for camera-roll photos and screenshot only when explicitly requested. " +
                "For explicit Google Photos, Google Photos API, Photos Library, cloud/account photos, or Photos Picker requests, use search_google_photos_library or show_google_photos_picker_selection instead of local camera-roll tools. If using Google Photos Library categories, pass explicit supported category names in content_categories. Be honest: the current Google Photos Library API is generally app-created-only and cannot perform full consumer Google Photos semantic/person/place search; the Picker API only exposes photos the user selected in Google Photos. " +
                "Google Calendar, Tasks, Gmail, Drive, Contacts, Docs, Sheets, Slides, and Forms tools may use Google OAuth grants from the Android companion. " +
                "Google Keep personal OAuth is not available here. If the user asks for Keep, state briefly that Google restricts Keep API access for personal OAuth and do not create a Google Doc, Task, email draft, or other substitute unless the user explicitly asks for that substitute. " +
                "For Google Tasks requests, use create_google_task for new tasks, list_google_tasks for reads, and complete_google_task for complete/mark done/check off requests. Do not satisfy a completion request by listing tasks. " +
                "For Drive requests, prefer search_google_drive or list_recent_google_drive_files. For Docs, Sheets, Slides, and Forms, use read_google_doc, read_google_sheet, read_google_slides, or read_google_form when the user asks to read, summarize, find inside, or inspect content. Use create_google_doc, create_google_sheet, or create_google_slides only when the user explicitly asks to create those file types. " +
                "For Gmail reads, prefer search_gmail. If the user asks to email/send a message, use prepare_gmail_send so the watch can confirm before sending. Use create_gmail_draft only when the user explicitly asks for a draft. If a recipient is a contact name rather than an email address, try Google Contacts resolution through prepare_gmail_send or resolve_google_contact_email before asking for the address. " +
                "When a calendar create tool succeeds, include the exact calendar name and account returned by the tool. " +
                "Some Android app tools can only open a draft or app screen on the phone; describe those as drafts, never as completed silent actions. " +
                "Use get_google_service_status when the user asks for a Google service that may not be implemented. " +
                "A Gemini API key does not automatically inherit consumer Gemini Connected Apps access; if Gmail, Drive, Docs, Sheets, Slides, Keep, Tasks, or another service lacks a provided tool, say briefly whether it is draft-only, open-only, or needs OAuth. " +
                "For watch reminders, alarms, and timers, do not use Calendar; those are handled by the watch app."
    }
}

private data class GeminiFunctionCall(
    val name: String,
    val args: JSONObject,
)

private sealed interface GeminiContentResult {
    data class Passed(val raw: String) : GeminiContentResult
    data class Failed(val reason: String) : GeminiContentResult
}

sealed interface GeminiRequestPlan {
    data class Ready(val model: String, val prompt: String) : GeminiRequestPlan
    data class Invalid(val reason: String) : GeminiRequestPlan
}

sealed interface GeminiKeyTestResult {
    data class Passed(val message: String) : GeminiKeyTestResult
    data class Failed(val reason: String) : GeminiKeyTestResult
}

sealed interface GeminiTextResult {
    data class Passed(val text: String) : GeminiTextResult
    data class Failed(val reason: String) : GeminiTextResult
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
