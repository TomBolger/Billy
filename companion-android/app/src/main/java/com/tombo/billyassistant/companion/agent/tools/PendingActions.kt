package com.tombo.billyassistant.companion.agent.tools

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Watch picker choices that run real code when selected: "Send" for an email
 * or text, "Delete" for an event, or picking one of several matching items.
 *
 * A tool calls [offer]; the watch shows the card; when the user picks an
 * option, CompanionAgent calls [resolve] with the answer and runs the action.
 * Free-text (dictated) answers that match no option return [Resolution.Unmatched]
 * so the agent can hand them back to Gemini instead.
 */
object PendingActions {
    private const val TTL_MS = 15 * 60 * 1000L
    const val CONTEXT_PREFIX = "pending="

    data class Choice(val label: String, val run: () -> PendingOutcome)

    private data class Entry(
        val question: String,
        val choices: List<Choice>,
        val cancelLabel: String?,
        val createdAt: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun offer(question: String, choices: List<Choice>, cancelLabel: String? = "Cancel"): ClarificationCard {
        prune()
        val token = UUID.randomUUID().toString().substring(0, 12)
        // The watch shows at most three options plus "Dictate...".
        val kept = choices.take(if (cancelLabel != null) 2 else 3)
        entries[token] = Entry(question, kept, cancelLabel, System.currentTimeMillis())
        return ClarificationCard(
            question = question.take(220),
            context = CONTEXT_PREFIX + token,
            options = kept.map { it.label } + listOfNotNull(cancelLabel),
        )
    }

    /** One-button confirmation: [actionLabel] runs [action], Cancel does nothing. */
    fun confirm(question: String, actionLabel: String, action: () -> PendingOutcome): ClarificationCard {
        return offer(question, listOf(Choice(actionLabel, action)))
    }

    fun resolve(token: String, answer: String): Resolution {
        val entry = entries.remove(token) ?: return Resolution.Expired
        val clean = answer.substringBefore('|').trim()
        if (entry.cancelLabel != null && clean.equals(entry.cancelLabel, ignoreCase = true)) {
            return Resolution.Cancelled
        }
        val choice = entry.choices.firstOrNull { it.label.matchesPickerAnswer(clean) }
            ?: entry.choices.firstOrNull { clean.isNotBlank() && it.label.contains(clean, ignoreCase = true) }
        return if (choice != null) {
            Resolution.Run(choice.run)
        } else {
            Resolution.Unmatched(entry.question, entry.choices.map { it.label })
        }
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        entries.entries.removeIf { it.value.createdAt < cutoff }
    }

    sealed interface Resolution {
        data class Run(val action: () -> PendingOutcome) : Resolution
        data class Unmatched(val question: String, val options: List<String>) : Resolution
        data object Cancelled : Resolution
        data object Expired : Resolution
    }
}

/** What happens after a picker choice: reply text, or another picker. */
data class PendingOutcome(
    val text: String,
    val card: ClarificationCard? = null,
)
