package com.tombo.billyassistant.companion.agent

import com.tombo.billyassistant.companion.settings.SettingsStore

class AgentRuntime(
    private val settingsStore: SettingsStore,
    private val geminiClient: GeminiClient,
) {
    fun describeStatus(): String {
        val settings = settingsStore.load()
        val geminiStatus = geminiClient.describeConfiguration(settings.geminiApiKey)
        val bridgeStatus = if (settings.pebbleBridgeEnabled) {
            "Watch bridge is enabled."
        } else {
            "Watch bridge is disabled."
        }

        return listOf(geminiStatus, bridgeStatus).joinToString(separator = "\n")
    }

    fun prepareAssistantRequest(prompt: String): AgentRuntimeResult {
        val settings = settingsStore.load()
        if (prompt.isBlank()) {
            return AgentRuntimeResult.Rejected("Prompt is blank.")
        }
        if (settings.geminiApiKey.isBlank()) {
            return AgentRuntimeResult.NotReady("Configure a Gemini API key first.")
        }

        return AgentRuntimeResult.Prepared(
            prompt = prompt.trim(),
            target = AgentTarget.Gemini,
        )
    }
}

enum class AgentTarget {
    Gemini,
}

sealed interface AgentRuntimeResult {
    data class Prepared(val prompt: String, val target: AgentTarget) : AgentRuntimeResult
    data class NotReady(val reason: String) : AgentRuntimeResult
    data class Rejected(val reason: String) : AgentRuntimeResult
}
