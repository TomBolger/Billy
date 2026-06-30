package com.tombo.billyassistant.companion.agent.tools

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PendingGmailRecipientChoices {
    private val pending = ConcurrentHashMap<String, PendingGmailRecipientChoice>()

    fun put(choice: PendingGmailRecipientChoice): String {
        val token = UUID.randomUUID().toString()
        pending[token] = choice
        return token
    }

    fun resolve(token: String, answer: String): PendingGmailRecipientResolution? {
        val choice = pending.remove(token) ?: return null
        val normalized = answer.trim()
        val option = choice.options.firstOrNull { option ->
            option.label.equals(normalized, ignoreCase = true) ||
                option.email.equals(normalized, ignoreCase = true) ||
                normalized.contains(option.email, ignoreCase = true)
        } ?: return null
        return PendingGmailRecipientResolution(choice, option)
    }
}

data class PendingGmailRecipientChoice(
    val originalQuery: String,
    val subject: String,
    val body: String,
    val options: List<PendingGmailRecipientOption>,
)

data class PendingGmailRecipientOption(
    val label: String,
    val displayName: String,
    val email: String,
)

data class PendingGmailRecipientResolution(
    val choice: PendingGmailRecipientChoice,
    val option: PendingGmailRecipientOption,
)
