package com.tombo.billyassistant.companion.agent.tools

import android.content.Context
import com.tombo.billyassistant.companion.gemini.GeminiAccountBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * ask_my_gemini: passes a question to the user's own Gemini account. Only
 * offered while the optional account link is on and healthy; any failure tells
 * Billy to carry on with its normal tools.
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
                "Don't use it for things your own tools already do (timers, alarms, weather, calendar, texts, calls, searches). " +
                "It's slower than your tools. If it returns an error, answer with your normal tools instead and don't dwell on the failure.",
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
        get() = if (GeminiAccountBridge.isUsable(context)) listOf(declaration) else emptyList()

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        if (name != "ask_my_gemini") return null
        val question = args.optString("question").trim()
        if (question.isBlank()) return error("No question given.")
        val prompt = "$question\n\n(Reply for a small smartwatch screen: 1-3 short plain sentences, no markdown, lists, or tables. " +
            "If I asked to see a photo or picture, show it, not just a description.)"
        return when (val reply = GeminiAccountBridge.ask(context, prompt)) {
            is GeminiAccountBridge.Reply.Failed -> error(
                if (reply.reason == "signed_out") "The user's Gemini account is signed out." else reply.reason,
            )
            is GeminiAccountBridge.Reply.Answer -> {
                val image = reply.imageUrls.firstNotNullOfOrNull { url ->
                    GeminiAccountBridge.downloadImage(context, url)?.let { bitmap ->
                        try {
                            bitmap.toWatchImage(watchMediaSpec)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                var answer = plain(reply.text).ifBlank { if (image != null) "" else "Done." }
                if (image == null && reply.imageUrls.isNotEmpty()) answer += " (Couldn't load the picture on the watch.)"
                CompanionToolExecution(
                    response = JSONObject()
                        .put("status", "ok")
                        .put("summary", "Answer from the user's Gemini account. Relay it in your own short words." + if (image != null) " The first picture it showed is now on the watch." else "")
                        .put("answer", answer.take(4000)),
                    finalText = answer,
                    watchImage = image,
                    endTurn = true,
                )
            }
        }
    }

    /** The watch shows plain text: drop markdown Gemini may still add. */
    private fun plain(text: String): String = text
        .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""(?m)^#{1,6}\s*"""), "")
        .replace(Regex("""(?m)^\s*[*•]\s+"""), "- ")
        .replace("**", "").replace("__", "")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    private fun error(reason: String) = CompanionToolExecution(
        JSONObject()
            .put("status", "error")
            .put("summary", "The Gemini account link isn't available right now ($reason). Use your normal tools to answer instead."),
    )
}
