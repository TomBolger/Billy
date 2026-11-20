package com.tombo.billyassistant.companion.agent.tools

import org.json.JSONObject

class ClarificationCompanionTool(
    private val optionLabelMaxChars: Int = WatchMediaSpec.Default.pickerOptionChars,
    private val originalPrompt: () -> String,
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        JSONObject()
            .put("name", "ask_clarifying_question")
            .put("description", "Show a picker so the user can choose an answer with the watch buttons (a Dictate option is added automatically). Use ONLY when guessing could do the wrong thing (create, send, or delete the wrong item) and no sensible default exists. Never use it to confirm something the user already asked for. Provide 1-3 likely options, each $optionLabelMaxChars characters or fewer. Ends your turn; the answer arrives as the next message.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("question", "options"),
                    properties = mapOf(
                        "question" to stringSchema("Short watch-sized question. Do not include option text here."),
                        "context" to stringSchema("Original user request or enough hidden context to continue after the user chooses."),
                        "options" to JSONObject()
                            .put("type", "array")
                            .put("description", "One to three short likely answer option labels, each $optionLabelMaxChars characters or fewer. Do not include Dictate; Billy adds it automatically.")
                            .put("items", JSONObject().put("type", "string")),
                    ),
                ),
            ),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        if (name != "ask_clarifying_question") {
            return null
        }
        val options = buildList {
            val array = args.optJSONArray("options")
            if (array != null) {
                for (i in 0 until minOf(array.length(), 3)) {
                    array.optString(i).trim().takeIf { it.isNotBlank() }?.let {
                        add(it.take(120))
                    }
                }
            }
        }
        return CompanionToolExecution(
            response = JSONObject()
                .put("status", "ok")
                .put("summary", "Asked the user a clarifying question."),
            clarificationCard = ClarificationCard(
                question = args.optString("question").ifBlank { "Which option?" }.take(120),
                context = args.optString("context").ifBlank { originalPrompt() }.take(180),
                options = options,
            ),
        )
    }
}

internal fun String.shortPickerLabel(maxChars: Int): String {
    val safeMax = maxChars.coerceIn(8, 64)
    val visible = substringBefore("|").trim()
    if (visible.length <= safeMax) {
        return visible
    }
    if (safeMax <= 3) {
        return visible.take(safeMax)
    }
    return visible.take(safeMax - 3).trimEnd() + "..."
}

internal fun String.matchesPickerAnswer(answer: String): Boolean {
    val cleanAnswer = answer.substringBefore("|").trim()
    if (equals(cleanAnswer, ignoreCase = true)) {
        return true
    }
    return listOf(18, 20, 28, 34, 64).any { maxChars ->
        shortPickerLabel(maxChars).equals(cleanAnswer, ignoreCase = true)
    }
}
