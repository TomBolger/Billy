package com.tombo.billyassistant.companion.agent.tools

import com.tombo.billyassistant.companion.profile.BillyUserProfileStore
import org.json.JSONArray
import org.json.JSONObject

class UserProfileCompanionTool(
    private val profileStore: BillyUserProfileStore,
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        JSONObject()
            .put("name", "get_billy_user_profile")
            .put("description", "Read Billy's locally stored user profile and memories. Use when the user asks what Billy knows/remembers about them or when profile context is directly needed.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = emptyMap(),
                ),
            ),
        JSONObject()
            .put("name", "remember_billy_user_fact")
            .put("description", "Store a durable Billy memory only when the user explicitly asks Billy to remember/save a personal fact or clearly corrects Billy with durable profile information.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("fact"),
                    properties = mapOf(
                        "fact" to stringSchema("Short durable personal fact or preference to remember. Do not include transient conversation details."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "forget_billy_user_fact")
            .put("description", "Remove Billy memories matching the user's forget/delete request.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("query"),
                    properties = mapOf(
                        "query" to stringSchema("Memory text, person, preference, or topic to forget."),
                    ),
                ),
            ),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        return when (name) {
            "get_billy_user_profile" -> getProfile()
            "remember_billy_user_fact" -> rememberFact(args)
            "forget_billy_user_fact" -> forgetFact(args)
            else -> null
        }
    }

    private fun getProfile(): CompanionToolExecution {
        val profile = profileStore.load()
        return CompanionToolExecution(
            JSONObject()
                .put("status", "ok")
                .put("summary", profile.statusSummary())
                .put("profile", profile.toJson())
                .put("prompt_context", profileStore.promptContext().orEmpty()),
        )
    }

    private fun rememberFact(args: JSONObject): CompanionToolExecution {
        val memory = profileStore.addMemory(args.optString("fact"), source = "watch")
            ?: return CompanionToolExecution(
                JSONObject()
                    .put("status", "rejected")
                    .put("summary", "No durable fact was provided."),
                finalText = "I need a specific fact to remember.",
            )
        val text = "Remembered: ${memory.fact}"
        return CompanionToolExecution(
            JSONObject()
                .put("status", "ok")
                .put("summary", text)
                .put("memory", memory.toJson()),
            finalText = text,
        )
    }

    private fun forgetFact(args: JSONObject): CompanionToolExecution {
        val result = profileStore.forgetMemory(args.optString("query"))
        val summary = when (result.removed.size) {
            0 -> "I did not find a matching Billy memory."
            1 -> "Forgot 1 Billy memory."
            else -> "Forgot ${result.removed.size} Billy memories."
        }
        return CompanionToolExecution(
            JSONObject()
                .put("status", if (result.removed.isEmpty()) "not_found" else "ok")
                .put("summary", summary)
                .put("removed", JSONArray().also { array -> result.removed.forEach { array.put(it.toJson()) } })
                .put("remaining_count", result.remaining.size),
            finalText = summary,
        )
    }
}
