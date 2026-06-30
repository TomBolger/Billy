package com.tombo.billyassistant.companion.agent.tools

import org.json.JSONObject

class ClarificationCompanionTool(
    private val originalPrompt: () -> String,
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        JSONObject()
            .put("name", "ask_clarifying_question")
            .put("description", "Ask the user one short clarifying question as a watch picker. Use this for every user-facing follow-up question; never ask open-ended questions in final text. Provide 1-3 likely selectable options; the watch adds a Dictate option for anything else.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("question", "options"),
                    properties = mapOf(
                        "question" to stringSchema("Short watch-sized question. Do not include option text here."),
                        "context" to stringSchema("Original user request or enough hidden context to continue after the user chooses."),
                        "options" to JSONObject()
                            .put("type", "array")
                            .put("description", "One to three short likely answer options. Do not include Dictate; Billy adds it automatically.")
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
                    array.optString(i).trim().takeIf { it.isNotBlank() }?.let { add(it.take(64)) }
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
