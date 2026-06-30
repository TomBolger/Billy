package com.tombo.billyassistant.companion.agent.tools

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PendingCalendarClarifications {
    private val pendingCreates = ConcurrentHashMap<String, PendingCalendarCreate>()

    fun putCreate(request: PendingCalendarCreate): String {
        val token = UUID.randomUUID().toString()
        pendingCreates[token] = request
        return token
    }

    fun resolveCreate(token: String, answer: String): PendingCalendarCreateResolution? {
        val request = pendingCreates.remove(token) ?: return null
        val normalizedAnswer = answer.trim()
        val option = request.options.firstOrNull { option ->
            option.label.equals(normalizedAnswer, ignoreCase = true) ||
                option.display.equals(normalizedAnswer, ignoreCase = true) ||
                normalizedAnswer.startsWith("${option.index}.", ignoreCase = true) ||
                normalizedAnswer.equals(option.index.toString(), ignoreCase = true)
        } ?: return null
        return PendingCalendarCreateResolution(request, option)
    }
}

data class PendingCalendarCreate(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val description: String?,
    val createMeetLink: Boolean,
    val options: List<PendingCalendarOption>,
)

data class PendingCalendarOption(
    val index: Int,
    val display: String,
    val label: String,
    val calendarId: String,
    val calendarName: String,
)

data class PendingCalendarCreateResolution(
    val request: PendingCalendarCreate,
    val option: PendingCalendarOption,
)
