package com.tombo.billyassistant.companion.agent.tools

import android.content.Context
import com.tombo.billyassistant.companion.diagnostics.DiagnosticsLog
import com.tombo.billyassistant.companion.gemini.GeminiAccountBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * ask_my_gemini: passes a question to the user's own Gemini account. Offered
 * whenever the user has turned the optional account link on, even if it has
 * since broken. There is no silent fallback: a failure comes back as an error
 * that tells Billy to say plainly that the user's Gemini account didn't answer
 * (and why), and it is added to the turn report so it can be reported.
 */
class MyGeminiCompanionTool(
    private val context: Context,
    private val watchMediaSpec: WatchMediaSpec = WatchMediaSpec.Default,
) : CompanionTool {
    private val declaration = JSONObject()
        .put("name", "ask_my_gemini")
        .put(
            "description",
            "Ask the user's own Gemini app account (gemini.google.com, signed in as them). It knows things your other tools can't reach: " +
                "the user's Google Photos library (\"show me my photos from Paris\"), Gemini's saved info and past Gemini chats, their Gems, " +
                "and Gemini's connections to Gmail, Drive, YouTube, YouTube Music, Google Home, and Keep. Use it for those, and when the user says \"ask Gemini\". " +
                "Don't use it for things your own tools already do (timers, alarms, weather, calendar, texts, calls, searches), " +
                "or for general knowledge, science, news, or how-to questions. " +
                "It's slower than your tools. If it returns an error, tell the user plainly that their Gemini account didn't answer and why; don't answer from other sources unless they ask.",
        )
        .put(
            "parameters",
            objectSchema(
                required = listOf("question"),
                properties = mapOf(
                    "question" to stringSchema("The user's request, written as they would type it into the Gemini app. Include needed context from the conversation."),
                ),
            ),
        )

    override val declarations: List<JSONObject>
        get() = if (GeminiAccountBridge.isLinkEnabled(context)) listOf(declaration) else emptyList()

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        if (name != "ask_my_gemini") return null
        val question = args.optString("question").trim()
        if (question.isBlank()) {
            return CompanionToolExecution(JSONObject().put("status", "error").put("summary", "No question given. Call ask_my_gemini again with the user's request."))
        }
        val prompt = "$question\n\n(Brief answer, it's for my smartwatch.)"
        return when (val reply = GeminiAccountBridge.ask(context, prompt)) {
            is GeminiAccountBridge.Reply.Failed -> error(
                if (reply.reason == "signed_out") "The user's Gemini account is signed out." else reply.reason,
            )
            is GeminiAccountBridge.Reply.Answer -> {
                // The user's own photos come first; if Gemini found one, never fall
                // back to an unrelated web picture it attached alongside.
                // Only attach a picture when the request was about seeing something;
                // Gemini often attaches stock images to plain factual answers.
                val imageUrls = if (PICTURE_REQUEST.containsMatchIn(question)) reply.imageUrls else emptyList()
                val personal = imageUrls.filter(::isPersonalPhoto)
                // Each failed picture can cost up to ~40 s, so only try two.
                val image = personal.ifEmpty { imageUrls }.take(2).firstNotNullOfOrNull { url ->
                    GeminiAccountBridge.downloadImage(context, url)?.let { bitmap ->
                        try {
                            bitmap.toWatchImage(watchMediaSpec)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                // Gemini's text is written for its own app (it may say it can't show a
                // picture that Billy did show, or describe UI the watch doesn't have).
                // Billy writes the watch reply itself from these facts.
                val pictureFound = imageUrls.isNotEmpty()
                CompanionToolExecution(
                    response = JSONObject()
                        .put("status", "ok")
                        .put("gemini_answer", plain(reply.text).take(4000))
                        .put("picture_on_watch", image != null)
                        .put("picture_found_but_not_shown", pictureFound && image == null)
                        // Shown as-is only if the model returns no text of its own.
                        .put(
                            "summary",
                            when {
                                image != null -> "Here it is, from your Gemini account."
                                pictureFound -> "Your Gemini found it, but the picture couldn't be loaded onto the watch."
                                else -> "Answered by your Gemini account."
                            },
                        )
                        .put(
                            "how_to_reply",
                            "gemini_answer was written for the Gemini app: ignore anything it says about what it can or can't " +
                                "display. Write the watch reply yourself in 1-2 short sentences from its facts. " +
                                when {
                                    image != null -> "The picture IS showing on the watch right now; never say it can't be shown."
                                    pictureFound -> "Gemini found a picture but it couldn't be loaded onto the watch; say so briefly."
                                    else -> "No picture is involved."
                                },
                        ),
                    watchImage = image,
                )
            }
        }
    }

    private companion object {
        /** The request is about a photo or seeing something (not a plain factual question). */
        val PICTURE_REQUEST = Regex(
            """\b(photos?|pictures?|pics?|images?|selfies?|screenshots?|snaps?|show me|look(s|ed)? like|see)\b""",
            RegexOption.IGNORE_CASE,
        )
    }

    private fun isPersonalPhoto(url: String) =
        url.contains("photos-askphotos") || url.contains(".usercontent.google.com/") || url.contains("googleusercontent.com/pw/")

    /** The watch shows plain text: drop markdown Gemini may still add. */
    private fun plain(text: String): String = text
        .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""(?m)^#{1,6}\s*"""), "")
        .replace(Regex("""(?m)^\s*[*•]\s+"""), "- ")
        .replace("**", "").replace("__", "")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    private fun error(reason: String): CompanionToolExecution {
        DiagnosticsLog.problem("ask_my_gemini failed: ${reason.take(120)}")
        val told = when {
            reason.startsWith(GeminiAccountBridge.NEEDS_SIGN_IN) ->
                "Tell the user: $reason. Don't answer from other sources unless they ask."
            else ->
                "Tell the user: your Gemini account didn't answer ($reason). Don't answer from other sources unless they ask."
        }
        return CompanionToolExecution(
            JSONObject()
                .put("status", "error")
                .put("summary", told),
        )
    }
}
