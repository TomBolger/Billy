package com.tombo.billyassistant.companion.agent

import android.content.Context
import com.tombo.billyassistant.companion.agent.tools.CalendarCompanionTool
import com.tombo.billyassistant.companion.agent.tools.ClarificationCard
import com.tombo.billyassistant.companion.agent.tools.ClarificationCompanionTool
import com.tombo.billyassistant.companion.agent.tools.CompanionToolExecution
import com.tombo.billyassistant.companion.agent.tools.CompanionToolRegistry
import com.tombo.billyassistant.companion.agent.tools.GoogleTasksCompanionTool
import com.tombo.billyassistant.companion.agent.tools.GoogleWorkspaceCompanionTool
import com.tombo.billyassistant.companion.agent.tools.MapCompanionTool
import com.tombo.billyassistant.companion.agent.tools.PendingActions
import com.tombo.billyassistant.companion.agent.tools.PhoneCompanionTool
import com.tombo.billyassistant.companion.agent.tools.PhotoCompanionTool
import com.tombo.billyassistant.companion.agent.tools.UserProfileCompanionTool
import com.tombo.billyassistant.companion.agent.tools.WatchImage
import com.tombo.billyassistant.companion.agent.tools.WatchMediaSpec
import com.tombo.billyassistant.companion.agent.tools.WatchToolsCompanionTool
import com.tombo.billyassistant.companion.agent.tools.WatchWeatherCurrent
import com.tombo.billyassistant.companion.agent.tools.WeatherCompanionTool
import com.tombo.billyassistant.companion.agent.tools.WebImageCompanionTool
import com.tombo.billyassistant.companion.agent.tools.MyGeminiCompanionTool
import com.tombo.billyassistant.companion.gemini.GeminiAccountBridge
import com.tombo.billyassistant.companion.agent.tools.currentAndroidLocation
import com.tombo.billyassistant.companion.auth.GoogleAccessTokenProvider
import com.tombo.billyassistant.companion.auth.GoogleApiScopes
import com.tombo.billyassistant.companion.auth.GoogleAuthStore
import com.tombo.billyassistant.companion.google.GoogleDriveApiTools
import com.tombo.billyassistant.companion.google.GoogleGmailApiTools
import com.tombo.billyassistant.companion.google.GooglePeopleApiTools
import com.tombo.billyassistant.companion.google.GooglePeopleResult
import com.tombo.billyassistant.companion.google.GoogleTasksApiTools
import com.tombo.billyassistant.companion.profile.BillyUserProfileStore
import com.tombo.billyassistant.companion.settings.SettingsStore
import org.json.JSONObject

/**
 * Answers one watch prompt: picker answers are handled here directly,
 * everything else goes to Gemini with every tool available.
 */
class CompanionAgent(
    private val context: Context,
    private val settingsStore: SettingsStore = SettingsStore(context),
    private val geminiClient: GeminiClient = GeminiClient(),
    watchMediaSpec: WatchMediaSpec = WatchMediaSpec.Default,
    private val threadId: String? = null,
    watchToolRelay: ((String, JSONObject) -> JSONObject)? = null,
) {
    private val tokenProvider = GoogleAccessTokenProvider(context)
    private val people = GooglePeopleApiTools(tokenProvider)
    private val profileStore = BillyUserProfileStore(context)
    private val conversations = ConversationStore(context)
    private var actions = mutableListOf<String>()
    private var currentUserText = ""

    private val myGemini = MyGeminiCompanionTool(context, watchMediaSpec)

    private val tools = CompanionToolRegistry(
        listOf(
            ClarificationCompanionTool(optionLabelMaxChars = watchMediaSpec.pickerOptionChars) { "" },
            WatchToolsCompanionTool(
                watchToolRelay ?: { name: String, _: JSONObject ->
                    JSONObject().put("status", "error").put("summary", "$name needs the watch connected with Billy open.")
                },
            ),
            WeatherCompanionTool(context),
            PhoneCompanionTool(context),
            CalendarCompanionTool(tokenProvider),
            GoogleTasksCompanionTool(GoogleTasksApiTools(tokenProvider)),
            GoogleWorkspaceCompanionTool(
                driveApiTools = GoogleDriveApiTools(tokenProvider),
                gmailApiTools = GoogleGmailApiTools(tokenProvider),
                peopleApiTools = people,
            ),
            PhotoCompanionTool(context, watchMediaSpec, geminiClient) { settingsStore.load().geminiApiKey },
            MapCompanionTool(context, watchMediaSpec) { settingsStore.load().googleMapsApiKey },
            WebImageCompanionTool(watchMediaSpec),
            myGemini,
            UserProfileCompanionTool(profileStore),
        ),
    )

    fun answer(prompt: String): CompanionAgentResult {
        val settings = settingsStore.load()
        if (prompt.isBlank()) return CompanionAgentResult.Failed("Prompt is blank.")
        if (settings.geminiApiKey.isBlank()) {
            return CompanionAgentResult.Failed("Add your Gemini API key in Billy Companion.")
        }

        var userText = prompt.trim()
        if (userText.startsWith(PICKER_ANSWER)) {
            val picked = parsePickerAnswer(userText)
            if (picked.context.startsWith(PendingActions.CONTEXT_PREFIX)) {
                val token = picked.context.removePrefix(PendingActions.CONTEXT_PREFIX).trim()
                when (val resolution = PendingActions.resolve(token, picked.answer)) {
                    is PendingActions.Resolution.Run -> {
                        val outcome = runCatching { resolution.action() }
                            .getOrElse { com.tombo.billyassistant.companion.agent.tools.PendingOutcome("That didn't work: ${it.message}") }
                        conversations.record(threadId, picked.answer, outcome.text, listOf("${picked.question} -> ${picked.answer}: ${outcome.text}"))
                        return CompanionAgentResult.Passed(text = outcome.text, clarificationCard = outcome.card)
                    }
                    PendingActions.Resolution.Cancelled -> {
                        conversations.record(threadId, picked.answer, "Cancelled.", listOf("user cancelled: ${picked.question}"))
                        return CompanionAgentResult.Passed("Cancelled.")
                    }
                    PendingActions.Resolution.Expired -> return CompanionAgentResult.Passed("That choice expired. Please ask again.")
                    is PendingActions.Resolution.Unmatched -> Unit // dictated answer: let Gemini handle it
                }
            }
            userText = "My answer to your question \"${picked.question}\": ${picked.answer}"
        }

        GeminiAccountBridge.warmUp(context)
        currentUserText = userText
        hydrateGoogleProfile()
        actions = mutableListOf()
        val location = currentAndroidLocation(context)
        val result = geminiClient.generateWithTools(
            prompt = userText,
            apiKey = settings.geminiApiKey,
            toolDeclarations = tools.declarations(),
            toolExecutor = ::runTool,
            history = conversations.history(threadId),
            extraContext = buildContext(userText),
            latitude = location?.latitude,
            longitude = location?.longitude,
        )
        if (result is CompanionAgentResult.Passed) {
            val recorded = result.text.ifBlank { result.clarificationCard?.question?.let { "(asked: $it)" }.orEmpty() }
            conversations.record(threadId, userText, recorded, actions)
        }
        return result
    }

    private fun runTool(name: String, args: JSONObject): CompanionToolExecution {
        val execution = routed(name, args)
        val status = execution.response.optString("status", "ok")
        // Keep ids in the action note so follow-ups can reuse them.
        val ids = listOf("event_id", "calendar_id").mapNotNull { key ->
            execution.response.optJSONObject("event")?.optString(key)?.takeIf { it.isNotBlank() }?.let { "$key=$it" }
        }
        actions += buildString {
            append(name).append(' ').append(args.toString().take(160)).append(" -> ").append(status)
            if (ids.isNotEmpty()) append(" (").append(ids.joinToString(", ")).append(')')
        }
        return execution
    }

    /**
     * With the Gemini account linked, photo lookups go to the user's whole
     * Google Photos library through Gemini (which can show the photo on the
     * watch) before falling back to the camera roll or opening the Photos app.
     */
    private fun routed(name: String, args: JSONObject): CompanionToolExecution {
        val ownPhotos = OWN_PHOTO_REQUEST.containsMatchIn(currentUserText)
        if (name == "find_photo" && !ownPhotos) {
            return CompanionToolExecution(
                JSONObject()
                    .put("status", "rejected")
                    .put("summary", "The user didn't ask for their own photos. For a picture of the subject, use show_image instead."),
            )
        }
        // Only escalate to the user's Google Photos when they actually asked for
        // their own photos, not when Billy reached for a picture as a visual aid.
        val linked = GeminiAccountBridge.isUsable(context) && ownPhotos
        if (linked && name == "open_google_photos_search") {
            askGeminiForPhoto(args.optString("query"))?.let { return it }
        }
        val execution = tools.execute(name, args)
        if (linked && name == "find_photo" && !execution.response.optString("status", "ok").equals("ok", ignoreCase = true)) {
            val when_ = listOf(args.optString("taken_after"), args.optString("taken_before")).filter { it.isNotBlank() }
            val described = args.optString("description").ifBlank { "my most recent photo" } +
                if (when_.isNotEmpty()) " (taken between ${when_.joinToString(" and ")})" else ""
            askGeminiForPhoto(described)?.let { return it }
        }
        return execution
    }

    private fun askGeminiForPhoto(description: String): CompanionToolExecution? {
        val result = myGemini.execute("ask_my_gemini", JSONObject().put("question", "Find and show me this photo from my Google Photos: $description")) ?: return null
        actions += "ask_my_gemini (photo: ${description.take(80)}) -> ${result.response.optString("status")}"
        return result.takeIf { it.response.optString("status") == "ok" }
    }

    private fun buildContext(prompt: String): String {
        val parts = mutableListOf<String>()
        val linked = GeminiAccountBridge.isUsable(context)
        if (linked) {
            parts += "The user's own Gemini account is linked (ask_my_gemini). Use it FIRST for: the user's photos, unless taken in the last day or two " +
                "(it searches the whole Google Photos library and shows the photo on the watch); facts about the user and their life " +
                "(home or work address, family, birthdays, preferences, anything Gemini has saved); their past Gemini chats, Gems, Keep, YouTube, and Google Home. " +
                "Billy's own notes below are partial: if they don't clearly answer a question about the user, use ask_my_gemini instead of saying you don't know. " +
                "Never use it for general knowledge, science, news, or how-to questions: answer those yourself (with show_image for a picture of the subject)."
        }
        profileStore.promptContext(prompt)?.let {
            parts += (if (linked) "Billy's own notes about the user (partial): " else "What Billy knows about the user (use when relevant, don't recite): ") + it
        }
        val granted = GoogleAuthStore(context).grantedScopes()
        if (!granted.contains(GoogleApiScopes.CALENDAR)) {
            parts += "Google account is not connected yet; Google Calendar/Tasks/Gmail/Drive tools will ask the user to connect it."
        }
        return parts.joinToString("\n")
    }

    private fun hydrateGoogleProfile() {
        if (!GoogleAuthStore(context).hasScopes(GoogleApiScopes.identity)) return
        if (!profileStore.shouldAttemptGoogleProfileHydration()) return
        profileStore.markGoogleProfileHydrationAttempt()
        val result = runCatching { people.fetchOwnProfile(includePeopleEnrichment = false) }.getOrNull()
        if (result is GooglePeopleResult.Success) profileStore.mergeGoogleProfile(result.payload)
    }

    private data class PickerAnswer(val context: String, val question: String, val answer: String)

    private fun parsePickerAnswer(text: String): PickerAnswer {
        val fields = text.lineSequence().drop(1).mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
        }.toMap()
        return PickerAnswer(
            context = fields["context"].orEmpty().trim(),
            question = fields["question"].orEmpty().trim(),
            answer = fields["answer"].orEmpty().substringBefore('|').trim(),
        )
    }

    private companion object {
        const val PICKER_ANSWER = "BILLY_CLARIFICATION_ANSWER"

        /** The user asked for their own photos ("my photo of...", "pictures I took", "a selfie"). */
        val OWN_PHOTO_REQUEST = Regex(
            """\b(my|our)\b[^.?!]{0,40}\b(photos?|pictures?|pics?|selfies?|snaps?)\b|\b(photos?|pictures?|pics?)\b[^.?!]{0,30}\b(of me|of us|i took|we took|i've taken|i have taken)\b|\b(do|did|have) (i|we) (have|take|taken)\b[^.?!]{0,40}\b(photos?|pictures?|pics?)\b|\b(last|latest|recent|newest|previous)\b[^.?!]{0,15}\b(photos?|pictures?|pics?|screenshots?)\b|\b(selfies?|screenshots?|camera roll|google photos)\b""",
            RegexOption.IGNORE_CASE,
        )
    }
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
