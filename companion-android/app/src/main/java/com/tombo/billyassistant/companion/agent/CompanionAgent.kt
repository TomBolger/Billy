package com.tombo.billyassistant.companion.agent

import android.content.Context
import com.tombo.billyassistant.companion.agent.tools.AppIntentCompanionTool
import com.tombo.billyassistant.companion.agent.tools.CalendarCompanionTool
import com.tombo.billyassistant.companion.agent.tools.ClarificationCompanionTool
import com.tombo.billyassistant.companion.agent.tools.ClarificationCard
import com.tombo.billyassistant.companion.agent.tools.CompanionToolRegistry
import com.tombo.billyassistant.companion.agent.tools.GoogleKeepCompanionTool
import com.tombo.billyassistant.companion.agent.tools.GoogleMapsCompanionTool
import com.tombo.billyassistant.companion.agent.tools.GooglePhotosCompanionTool
import com.tombo.billyassistant.companion.agent.tools.GoogleTasksCompanionTool
import com.tombo.billyassistant.companion.agent.tools.GoogleWorkspaceCompanionTool
import com.tombo.billyassistant.companion.agent.tools.GoogleWorkspaceStatusCompanionTool
import com.tombo.billyassistant.companion.agent.tools.MapCompanionTool
import com.tombo.billyassistant.companion.agent.tools.PendingCalendarClarifications
import com.tombo.billyassistant.companion.agent.tools.PendingGmailSend
import com.tombo.billyassistant.companion.agent.tools.PendingGmailSends
import com.tombo.billyassistant.companion.agent.tools.PendingGmailRecipientChoices
import com.tombo.billyassistant.companion.agent.tools.PendingTaskCompletions
import com.tombo.billyassistant.companion.agent.tools.PhotoCompanionTool
import com.tombo.billyassistant.companion.agent.tools.UserProfileCompanionTool
import com.tombo.billyassistant.companion.agent.tools.WeatherCompanionTool
import com.tombo.billyassistant.companion.agent.tools.WebImageCompanionTool
import com.tombo.billyassistant.companion.agent.tools.WatchWeatherCurrent
import com.tombo.billyassistant.companion.agent.tools.WatchMediaSpec
import com.tombo.billyassistant.companion.agent.tools.WatchImage
import com.tombo.billyassistant.companion.agent.tools.shortPickerLabel
import com.tombo.billyassistant.companion.auth.GoogleApiScopes
import com.tombo.billyassistant.companion.auth.GoogleAuthStore
import com.tombo.billyassistant.companion.auth.GoogleAccessTokenProvider
import com.tombo.billyassistant.companion.calendar.AndroidCalendarTools
import com.tombo.billyassistant.companion.google.GoogleCalendarApiTools
import com.tombo.billyassistant.companion.google.GoogleCalendarResult
import com.tombo.billyassistant.companion.google.GoogleDriveApiTools
import com.tombo.billyassistant.companion.google.GoogleGmailApiTools
import com.tombo.billyassistant.companion.google.GoogleGmailResult
import com.tombo.billyassistant.companion.google.GoogleKeepApiTools
import com.tombo.billyassistant.companion.google.GoogleMapsPlatformApiTools
import com.tombo.billyassistant.companion.google.GooglePeopleApiTools
import com.tombo.billyassistant.companion.google.GooglePeopleResult
import com.tombo.billyassistant.companion.google.GooglePhotosApiTools
import com.tombo.billyassistant.companion.google.GooglePhotosPickerStore
import com.tombo.billyassistant.companion.google.GoogleTasksApiTools
import com.tombo.billyassistant.companion.profile.BillyUserProfileStore
import com.tombo.billyassistant.companion.settings.SettingsStore
import org.json.JSONObject
import java.time.Instant
import java.util.TimeZone

class CompanionAgent(
    private val context: Context,
    private val settingsStore: SettingsStore = SettingsStore(context),
    private val geminiClient: GeminiClient = GeminiClient(),
    private val watchMediaSpec: WatchMediaSpec = WatchMediaSpec.Default,
    calendarTools: AndroidCalendarTools = AndroidCalendarTools(context),
    private val threadId: String? = null,
) {
    private val googleAccessTokenProvider = GoogleAccessTokenProvider(context)
    private val googleAuthStore = GoogleAuthStore(context)
    private val googlePeopleApiTools = GooglePeopleApiTools(googleAccessTokenProvider)
    private val recentContextStore = RecentAgentContextStore(context)
    private val userProfileStore = BillyUserProfileStore(context)
    private val photoTool = PhotoCompanionTool(
        context = context,
        watchMediaSpec = watchMediaSpec,
        geminiClient = geminiClient,
        apiKeyProvider = { settingsStore.load().geminiApiKey },
    )
    private val mapTool = MapCompanionTool(
        context = context,
        watchMediaSpec = watchMediaSpec,
        googleMapsApiKeyProvider = { settingsStore.load().googleMapsApiKey },
    )
    private val webImageTool = WebImageCompanionTool(watchMediaSpec) { query ->
        recentContextStore.saveWebImageQuery(query, threadId)
    }
    private var activePrompt: String = ""
    private var activeClarificationOriginalPrompt: String? = null
    private var activeClarificationAnswers: List<ClarificationAnswer> = emptyList()

    private val toolRegistry = CompanionToolRegistry(
        listOf(
            ClarificationCompanionTool(
                optionLabelMaxChars = watchMediaSpec.pickerOptionChars,
            ) { activePrompt },
            UserProfileCompanionTool(userProfileStore),
            CalendarCompanionTool(
                calendarTools = calendarTools,
                googleCalendarApiTools = GoogleCalendarApiTools(googleAccessTokenProvider),
            ),
            GoogleKeepCompanionTool(GoogleKeepApiTools(googleAccessTokenProvider)),
            GoogleTasksCompanionTool(GoogleTasksApiTools(googleAccessTokenProvider)),
            GoogleMapsCompanionTool(
                context = context,
                mapsApiTools = GoogleMapsPlatformApiTools { settingsStore.load().googleMapsApiKey },
            ),
            WeatherCompanionTool(context),
            mapTool,
            webImageTool,
            GoogleWorkspaceCompanionTool(
                driveApiTools = GoogleDriveApiTools(googleAccessTokenProvider),
                gmailApiTools = GoogleGmailApiTools(googleAccessTokenProvider),
                peopleApiTools = googlePeopleApiTools,
            ),
            GooglePhotosCompanionTool(
                photosApiTools = GooglePhotosApiTools(googleAccessTokenProvider),
                pickerStore = GooglePhotosPickerStore(context),
                watchMediaSpec = watchMediaSpec,
            ),
            GoogleWorkspaceStatusCompanionTool(
                tokenProvider = googleAccessTokenProvider,
                mapsApiKeyProvider = { settingsStore.load().googleMapsApiKey },
            ),
            photoTool,
            AppIntentCompanionTool(context),
        ),
    )

    fun answer(prompt: String): CompanionAgentResult {
        activeClarificationOriginalPrompt = null
        activeClarificationAnswers = emptyList()
        return answerInternal(prompt)
    }

    private fun answerInternal(prompt: String): CompanionAgentResult {
        val settings = settingsStore.load()
        if (prompt.isBlank()) {
            return CompanionAgentResult.Failed("Prompt is blank.")
        }
        if (settings.geminiApiKey.isBlank()) {
            return CompanionAgentResult.Failed("Gemini API key is missing in the companion app.")
        }
        activePrompt = prompt
        directClarificationAnswer(prompt)?.let { return rememberTurn(prompt, prepareOutgoingResult(prompt, it)) }
        hydrateGoogleProfileIfAvailable()
        homeLocationClarification(prompt)?.let { return rememberTurn(prompt, prepareOutgoingResult(prompt, it)) }

        val contextualPrompt = buildContextualPrompt(prompt)
        val result = geminiClient.generateWithTools(
            prompt = contextualPrompt,
            apiKey = settings.geminiApiKey,
            toolDeclarations = toolRegistry.declarations(),
            toolExecutor = ::executeToolAndRemember,
        )
        return rememberTurn(prompt, prepareOutgoingResult(prompt, result))
    }

    private fun buildContextualPrompt(prompt: String): String {
        if (prompt.startsWith("BILLY_CLARIFICATION_ANSWER")) {
            return prompt
        }
        val summaries = buildList {
            userProfileStore.promptContext(prompt)?.let { add(it) }
            recentContextStore.conversationContext(threadId)?.let { add(it) }
            recentContextStore.lastPhotoContext(threadId)?.humanSummary()?.let { add(it) }
            recentContextStore.lastGoogleDocContext(threadId)?.humanSummary()?.let { add(it) }
        }
        if (summaries.isEmpty()) {
            return prompt
        }
        return summaries.joinToString(separator = "\n") + "\nCurrent user request: $prompt"
    }

    private fun hydrateGoogleProfileIfAvailable() {
        if (!googleAuthStore.hasScopes(GoogleApiScopes.identity)) {
            return
        }
        if (!userProfileStore.shouldAttemptGoogleProfileHydration()) {
            return
        }
        userProfileStore.markGoogleProfileHydrationAttempt()
        when (val result = googlePeopleApiTools.fetchOwnProfile(includePeopleEnrichment = false)) {
            is GooglePeopleResult.Success -> userProfileStore.mergeGoogleProfile(result.payload)
            is GooglePeopleResult.NeedsScope,
            is GooglePeopleResult.Rejected,
            is GooglePeopleResult.Failed -> Unit
        }
    }

    private fun directClarificationAnswer(prompt: String): CompanionAgentResult? {
        if (!prompt.startsWith("BILLY_CLARIFICATION_ANSWER")) {
            return null
        }
        val fields = prompt.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()
        val context = fields["context"].orEmpty()
        val rawAnswer = fields["answer"].orEmpty().trim()
        val answer = rawAnswer.substringBefore('|').trim()
        val question = fields["question"].orEmpty()
        if (context.startsWith("agent_clarify_token=")) {
            val token = context.substringAfter("agent_clarify_token=").trim()
            val pending = recentContextStore.resolveClarification(token, threadId)
                ?: return CompanionAgentResult.Passed("That question expired. Please ask again.")
            val resolvedAnswer = resolveGenericClarificationAnswer(pending, answer.ifBlank { rawAnswer })
            activeClarificationOriginalPrompt = pending.originalPrompt
            activeClarificationAnswers = pending.withAnswer(resolvedAnswer)
            return answerInternal(buildClarificationContinuation(pending, resolvedAnswer))
        }
        if (context.startsWith("maps_home_location_missing")) {
            if (answer.equals("cancel", ignoreCase = true)) {
                return CompanionAgentResult.Passed("Canceled.")
            }
            val original = context.substringAfter("original=", "").ifBlank { activePrompt }
            val continuation = if (answer.equals("current location", ignoreCase = true)) {
                "Resolved Maps origin: use the Android phone's current location. Original request: $original"
            } else {
                "Resolved Maps origin: use this dictated address or area: $answer. Original request: $original"
            }
            return answerInternal(continuation)
        }
        if (context.startsWith("calendar_create_token=")) {
            val token = context.substringAfter("calendar_create_token=").trim()
            val resolution = PendingCalendarClarifications.resolveCreate(token, answer)
                ?: return CompanionAgentResult.Passed("That calendar choice expired. Try creating the event again.")
            val request = resolution.request
            val result = GoogleCalendarApiTools(googleAccessTokenProvider).createEvent(
                title = request.title,
                startMillis = request.startMillis,
                endMillis = request.endMillis,
                description = request.description,
                timeZoneId = TimeZone.getDefault().id,
                calendarId = resolution.option.calendarId,
                createMeetLink = request.createMeetLink,
            )
            return CompanionAgentResult.Passed(
                when (result) {
                    is GoogleCalendarResult.Success -> result.summary
                    is GoogleCalendarResult.NeedsScope -> result.summary
                    is GoogleCalendarResult.Rejected -> result.reason
                    is GoogleCalendarResult.Failed -> result.reason
                },
            )
        }
        if (context.startsWith("gmail_send_token=")) {
            val token = context.substringAfter("gmail_send_token=").trim()
            val pending = PendingGmailSends.resolve(token)
                ?: return CompanionAgentResult.Passed("That email confirmation expired. Ask again.")
            if (!answer.equals("send", ignoreCase = true)) {
                return CompanionAgentResult.Passed("Email not sent.")
            }
            val result = GoogleGmailApiTools(googleAccessTokenProvider).sendMessage(
                to = pending.to,
                subject = pending.subject,
                body = pending.body,
            )
            return CompanionAgentResult.Passed(
                when (result) {
                    is com.tombo.billyassistant.companion.google.GoogleGmailResult.Success -> result.summary
                    is com.tombo.billyassistant.companion.google.GoogleGmailResult.NeedsScope -> result.summary
                    is com.tombo.billyassistant.companion.google.GoogleGmailResult.Rejected -> result.reason
                    is com.tombo.billyassistant.companion.google.GoogleGmailResult.Failed -> result.reason
                },
            )
        }
        if (context.startsWith("gmail_recipient_token=")) {
            val token = context.substringAfter("gmail_recipient_token=").trim()
            val resolution = PendingGmailRecipientChoices.resolve(token, answer)
                ?: return CompanionAgentResult.Passed("That contact choice expired. Ask again.")
            val result = GoogleGmailApiTools(googleAccessTokenProvider).prepareSend(
                to = resolution.option.email,
                subject = resolution.choice.subject,
                body = resolution.choice.body,
            )
            return gmailSendConfirmationResult(result)
        }
        if (context.startsWith("task_complete_token=")) {
            val token = context.substringAfter("task_complete_token=").trim()
            val pending = PendingTaskCompletions.resolve(token, answer)
                ?: return CompanionAgentResult.Passed("That task choice expired. Ask again.")
            val result = GoogleTasksApiTools(googleAccessTokenProvider).completeExactTask(
                taskListId = pending.taskListId,
                taskId = pending.taskId,
            )
            return CompanionAgentResult.Passed(
                when (result) {
                    is com.tombo.billyassistant.companion.google.GoogleTasksResult.Success -> result.summary
                    is com.tombo.billyassistant.companion.google.GoogleTasksResult.NeedsScope -> result.summary
                    is com.tombo.billyassistant.companion.google.GoogleTasksResult.Rejected -> result.reason
                    is com.tombo.billyassistant.companion.google.GoogleTasksResult.Failed -> result.reason
                },
            )
        }
        if (!context.startsWith("calendar_create|")) {
            val continuation = buildString {
                append("The user answered a Billy clarification.\n")
                append("Original context: ${context.ifBlank { "unknown" }}\n")
                if (question.isNotBlank()) {
                    append("Question: $question\n")
                }
                append("Selected answer: $answer\n")
                append("Continue the original request using that answer.")
            }
            return answerInternal(continuation)
        }
        val values = context.split('|')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) null else part.substring(0, separator) to part.substring(separator + 1)
            }
            .toMap()
        val calendarId = values.entries.firstOrNull { entry ->
            entry.key.endsWith("_label") && entry.value.equals(answer, ignoreCase = true)
        }?.key?.removeSuffix("_label")?.let { prefix -> values["${prefix}_id"] }
        if (calendarId.isNullOrBlank()) {
            return CompanionAgentResult.Passed("I could not match that calendar. Try again.")
        }
        val title = values["title"].orEmpty().ifBlank { "Calendar event" }
        val start = values["start"]?.toLongOrNull() ?: return CompanionAgentResult.Passed("I lost the event start time. Try again.")
        val end = values["end"]?.toLongOrNull() ?: return CompanionAgentResult.Passed("I lost the event end time. Try again.")
        val result = GoogleCalendarApiTools(googleAccessTokenProvider).createEvent(
            title = title,
            startMillis = start,
            endMillis = end,
            description = values["desc"],
            timeZoneId = TimeZone.getDefault().id,
            calendarId = calendarId,
        )
        return CompanionAgentResult.Passed(
            when (result) {
                is GoogleCalendarResult.Success -> result.summary
                is GoogleCalendarResult.NeedsScope -> result.summary
                is GoogleCalendarResult.Rejected -> result.reason
                is GoogleCalendarResult.Failed -> result.reason
            },
        )
    }

    private fun gmailSendConfirmationResult(result: GoogleGmailResult): CompanionAgentResult.Passed {
        if (result !is GoogleGmailResult.Success || result.payload.optString("status") != "ok") {
            return CompanionAgentResult.Passed(
                when (result) {
                    is GoogleGmailResult.Success -> result.payload.optString("summary").ifBlank { result.summary }
                    is GoogleGmailResult.NeedsScope -> result.summary
                    is GoogleGmailResult.Rejected -> result.reason
                    is GoogleGmailResult.Failed -> result.reason
                },
            )
        }
        val response = result.payload
        val pending = PendingGmailSend(
            to = response.optString("to"),
            subject = response.optString("subject"),
            body = response.optString("body"),
        )
        val token = PendingGmailSends.put(pending)
        val question = buildString {
            append("Send email?\n")
            append("To: ${pending.to}\n")
            append("Subject: ${pending.subject.ifBlank { "(no subject)" }}\n")
            append("Body: ${pending.body.ifBlank { "(blank)" }}")
        }.take(220)
        return CompanionAgentResult.Passed(
            text = "",
            clarificationCard = ClarificationCard(
                question = question,
                context = "gmail_send_token=$token",
                options = listOf("Send", "Cancel"),
            ),
        )
    }

    private fun executeToolAndRemember(name: String, args: JSONObject): com.tombo.billyassistant.companion.agent.tools.CompanionToolExecution {
        val result = toolRegistry.execute(name, args)
        if (name.contains("photo", ignoreCase = true)) {
            savePhotoContextFromResult(
                prompt = activePrompt,
                mediaType = if (name.contains("google_photos", ignoreCase = true)) {
                    "google_photos"
                } else {
                    args.optString("media_type", "photo")
                },
                searchText = args.optString("search_text"),
                range = args.toPhotoDateRange(),
                result = result.response,
            )
        }
        if (name == "read_google_doc" || name == "create_google_doc" || name == "update_google_doc") {
            saveGoogleDocContextFromResult(result.response)
        }
        return result
    }

    private fun prepareOutgoingResult(prompt: String, result: CompanionAgentResult): CompanionAgentResult {
        if (result !is CompanionAgentResult.Passed || result.clarificationCard == null) {
            return result
        }
        val card = result.clarificationCard
        if (card.context.isActionClarificationContext()) {
            return result
        }
        val shortCard = card.copy(
            options = card.options.map { it.shortPickerLabel(watchMediaSpec.pickerOptionChars) },
        )
        val pending = recentContextStore.saveClarification(
            originalPrompt = activeClarificationOriginalPrompt ?: prompt,
            card = card,
            priorAnswers = activeClarificationAnswers,
            threadId = threadId,
        )
        return result.copy(
            clarificationCard = shortCard.copy(context = "agent_clarify_token=${pending.token}"),
        )
    }

    private fun resolveGenericClarificationAnswer(pending: PendingClarification, answer: String): String {
        val cleanAnswer = answer.trim()
        if (cleanAnswer.isBlank()) {
            return answer
        }
        return pending.options.firstOrNull { option ->
            option.equals(cleanAnswer, ignoreCase = true)
        } ?: pending.options.firstOrNull { option ->
            option.shortPickerLabel(watchMediaSpec.pickerOptionChars).equals(cleanAnswer, ignoreCase = true)
        } ?: cleanAnswer
    }

    private fun buildClarificationContinuation(pending: PendingClarification, answer: String): String {
        val answers = pending.withAnswer(answer)
        return buildString {
            append("Continue the user's original request using the clarification answers below.\n")
            append("Original user request: ${pending.originalPrompt}\n")
            if (pending.priorContext.isNotBlank() && pending.priorContext != pending.originalPrompt) {
                append("Hidden context from the previous clarification: ${pending.priorContext}\n")
            }
            append("Clarification answers so far:\n")
            answers.forEachIndexed { index, item ->
                append("${index + 1}. ${item.question} -> ${item.answer}\n")
            }
            append("Now complete the original request. ")
            append("Do not ask another clarification unless a private-data action would otherwise be unsafe. ")
            append("For recommendations, advice, summaries, or brainstorming, make a best-effort answer with the available context.")
        }
    }

    private fun rememberTurn(
        prompt: String,
        result: CompanionAgentResult,
        kind: String = "general",
    ): CompanionAgentResult {
        if (result is CompanionAgentResult.Passed && result.text.isNotBlank()) {
            recentContextStore.saveTurn(prompt, result.text, kind, threadId)
        }
        return result
    }

    private fun saveGoogleDocContextFromResult(result: JSONObject) {
        if (result.optString("status") != "ok") {
            return
        }
        val file = result.optJSONObject("file")
        val documentId = result.optString("document_id")
            .ifBlank { file?.optString("id").orEmpty() }
        if (documentId.isBlank()) {
            return
        }
        recentContextStore.saveGoogleDocContext(
            RecentGoogleDocContext(
                documentId = documentId,
                title = result.optString("title")
                    .ifBlank { file?.optString("name").orEmpty() },
                webViewLink = file?.optString("webViewLink")?.takeIf { it.isNotBlank() },
                textExcerpt = result.optString("text_excerpt")
                    .ifBlank { result.optString("text") }
                    .takeIf { it.isNotBlank() }
                    ?.take(900),
            ),
            threadId = threadId,
        )
    }

    private fun savePhotoContextFromResult(
        prompt: String,
        mediaType: String,
        searchText: String,
        range: PhotoDateRange?,
        result: JSONObject,
    ) {
        if (result.optString("status") != "ok") {
            return
        }
        val photo = result.optJSONObject("photo") ?: result.optJSONObject("shown_photo") ?: return
        val dateMillis = photo.optionalPositiveLong("date_taken_millis")
            ?: photo.optionalPositiveLong("date_added_millis")
            ?: photo.optionalPositiveLong("date_modified_millis")
            ?: photo.optString("creation_time").takeIf { it.isNotBlank() }?.let { value ->
                runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            }
        recentContextStore.savePhotoContext(
            RecentPhotoContext(
                prompt = prompt,
                mediaType = mediaType.ifBlank { "photo" },
                searchText = searchText,
                rangeLabel = range?.label,
                rangeStartMillis = range?.startMillis,
                rangeEndMillis = range?.endMillis,
                displayName = photo.optString("display_name").ifBlank { photo.optString("filename") },
                photoDateMillis = dateMillis,
            ),
            threadId = threadId,
        )
    }

    private fun homeLocationClarification(prompt: String): CompanionAgentResult? {
        if (!prompt.needsConcreteHomeForMaps()) {
            return null
        }
        if (userProfileStore.homeLocationHint() != null) {
            return null
        }
        return CompanionAgentResult.Passed(
            text = "",
            clarificationCard = ClarificationCard(
                question = "I don't have your home address. Search where?",
                context = "maps_home_location_missing original=${prompt.take(120)}",
                options = listOf("Current location", "Cancel"),
            ),
        )
    }
}

private fun JSONObject.toPhotoDateRange(): PhotoDateRange? {
    val start = optionalPositiveLong("taken_after_millis") ?: return null
    val end = optionalPositiveLong("taken_before_millis") ?: return null
    return PhotoDateRange(
        label = "requested date range",
        startMillis = start,
        endMillis = end,
        matchedText = "",
    )
}

private fun JSONObject.optionalPositiveLong(name: String): Long? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return when (val value = opt(name)) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }?.takeIf { it > 0L }
}

private fun String.needsConcreteHomeForMaps(): Boolean {
    val lower = lowercase()
    if (lower.startsWith("resolved maps origin:")) {
        return false
    }
    val mentionsHome = Regex("""\b(my\s+)?(home|house|place)\b""").containsMatchIn(lower)
    if (!mentionsHome) {
        return false
    }
    val placeIntent = Regex(
        """\b(coffee|cafe|restaurant|pizza|shop|store|bar|pharmacy|grocery|gas|station|airport|hotel|map|maps|navigate|directions?|route|near|nearby|closest|around|walk|drive|bike|transit)\b""",
    ).containsMatchIn(lower)
    val homeScreen = Regex("""\bhome screen\b""").containsMatchIn(lower)
    return placeIntent && !homeScreen
}

private fun String.isActionClarificationContext(): Boolean {
    return startsWith("calendar_create_token=") ||
        startsWith("calendar_create|") ||
        startsWith("gmail_send_token=") ||
        startsWith("gmail_recipient_token=") ||
        startsWith("task_complete_token=") ||
        startsWith("maps_home_location_missing") ||
        startsWith("agent_clarify_token=")
}

sealed interface CompanionAgentResult {
    data class Passed(
        val text: String,
        val watchImage: WatchImage? = null,
        val watchWeatherCurrent: WatchWeatherCurrent? = null,
        val clarificationCard: ClarificationCard? = null,
    ) : CompanionAgentResult
    data class Failed(val reason: String) : CompanionAgentResult
}
