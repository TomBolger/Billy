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
            options = kept.mapIndexed { index, choice -> "${choice.label.substringBefore("|")}|choice=$index" } +
                listOfNotNull(cancelLabel?.let { "$it|choice=cancel" }),
        )
    }

    /** One-button confirmation: [actionLabel] runs [action], Cancel does nothing. */
    fun confirm(question: String, actionLabel: String, action: () -> PendingOutcome): ClarificationCard {
        // Show all content on successive watch pages; Send exists only on the last page.
        val pages = reviewPages(question)
        fun page(index: Int): ClarificationCard {
            val text = if (pages.size == 1) pages[index] else "${index + 1}/${pages.size}\n${pages[index]}"
            return if (index == pages.lastIndex) {
                offer(text, listOf(Choice(actionLabel, action)))
            } else {
                offer(text, listOf(Choice("Next") { PendingOutcome("", page(index + 1)) }))
            }
        }
        return page(0)
    }

    internal fun reviewPages(text: String): List<String> {
        val pages = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            var end = minOf(offset + 190, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            pages += text.substring(offset, end)
            offset = end
        }
        return pages.ifEmpty { listOf("") }
    }

    fun resolve(token: String, answer: String): Resolution {
        val entry = entries.remove(token) ?: return Resolution.Expired
        if (System.currentTimeMillis() - entry.createdAt > TTL_MS) return Resolution.Expired
        val clean = answer.substringBefore('|').trim()
        val id = answer.substringAfter("|choice=", "")
        if (id == "cancel" || (id.isEmpty() && entry.cancelLabel != null && clean.equals(entry.cancelLabel, ignoreCase = true))) {
            return Resolution.Cancelled
        }
        val choice = if (id.isNotEmpty()) {
            id.toIntOrNull()?.let { entry.choices.getOrNull(it) }
        } else {
            // Dictation may select a unique full label. Never guess from a shortened prefix.
            entry.choices.filter { it.label.substringBefore('|').trim().equals(clean, ignoreCase = true) }.singleOrNull()
        }
        return if (choice != null) Resolution.Run(choice.run)
        else Resolution.Unmatched(entry.question, entry.choices.map { it.label })
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
