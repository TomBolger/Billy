package com.tombo.billyassistant.companion.agent.tools

import org.junit.Assert.*
import org.junit.Test

class PendingActionsTest {
    private fun token(card: ClarificationCard) = card.context.removePrefix(PendingActions.CONTEXT_PREFIX)

    @Test fun identicalVisibleLabelsSelectTheChosenId() {
        val card = PendingActions.offer("Which?", listOf(
            PendingActions.Choice("Alex Very Long Shared Name Work") { PendingOutcome("first") },
            PendingActions.Choice("Alex Very Long Shared Name Home") { PendingOutcome("second") },
        ))
        val answer = card.options[1].shortPickerLabel(24) + "|" + card.options[1].substringAfter('|')
        val run = PendingActions.resolve(token(card), answer) as PendingActions.Resolution.Run
        assertEquals("second", run.action().text)
        assertTrue(PendingActions.resolve(token(card), answer) is PendingActions.Resolution.Expired)
    }

    @Test fun ambiguousDictationDoesNotPickTheFirstChoice() {
        val card = PendingActions.offer("Which?", listOf(
            PendingActions.Choice("Alex") { PendingOutcome("first") },
            PendingActions.Choice("Alex") { PendingOutcome("second") },
        ))
        assertTrue(PendingActions.resolve(token(card), "Alex") is PendingActions.Resolution.Unmatched)
    }

    @Test fun fullMessageIsPreservedAndSendOnlyExistsOnLastPage() {
        val message = "Email somebody@example.com\nSubject: Hello\n" + "A message including 😀 and private details. ".repeat(25)
        val pages = PendingActions.reviewPages(message)
        assertEquals(message, pages.joinToString(""))
        // Pages break between words, not inside them.
        pages.dropLast(1).forEach { assertTrue(it.endsWith(" ") || it.endsWith("\n")) }
        assertTrue(pages.size in 2..5)
        var sent = 0
        var card = PendingActions.confirm(message, "Send") { sent++; PendingOutcome("sent") }
        var pages = 0
        while (card.options[0].startsWith("Next|")) {
            assertEquals(0, sent)
            card = (PendingActions.resolve(token(card), card.options[0]) as PendingActions.Resolution.Run).action().card!!
            pages++
        }
        assertTrue(pages > 1)
        assertTrue(card.options[0].startsWith("Send|"))
        (PendingActions.resolve(token(card), card.options[0]) as PendingActions.Resolution.Run).action()
        assertEquals(1, sent)
    }

    @Test fun cancelNeverSendsTheMessage() {
        var sent = false
        val card = PendingActions.confirm("Long message ".repeat(50), "Send") { sent = true; PendingOutcome("sent") }
        assertTrue(PendingActions.resolve(token(card), card.options.last()) is PendingActions.Resolution.Cancelled)
        assertFalse(sent)
    }

    @Test fun shortMessagesFitOnOnePage() {
        val card = PendingActions.confirm("Text Sam (555-0100):\n\"Running ten minutes late!\"", "Send") { PendingOutcome("sent") }
        assertTrue(card.options[0].startsWith("Send|"))
        assertFalse(card.question.startsWith("("))
    }
}
