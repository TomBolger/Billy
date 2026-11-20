package com.tombo.billyassistant.companion.agent.tools

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Times that Gemini passes to tools. Gemini is far more reliable writing
 * ISO strings ("2026-10-02T15:00:00-07:00", "2026-10-02") than epoch millis.
 */
internal object TimeArgs {
    fun zone(): ZoneId = ZoneId.systemDefault()

    /** Parses ISO date-time (with or without offset) or a plain date (start of day). */
    fun parse(value: String?): ParsedTime? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { number ->
            val millis = if (number < 100_000_000_000L) number * 1000 else number
            return ParsedTime(Instant.ofEpochMilli(millis).atZone(zone()), dateOnly = false)
        }
        runCatching { return ParsedTime(ZonedDateTime.parse(text).withZoneSameInstant(zone()), false) }
        runCatching { return ParsedTime(OffsetDateTime.parse(text).atZoneSameInstant(zone()), false) }
        runCatching { return ParsedTime(LocalDateTime.parse(text).atZone(zone()), false) }
        runCatching { return ParsedTime(LocalDate.parse(text).atStartOfDay(zone()), true) }
        return null
    }

    fun spoken(time: ZonedDateTime, allDay: Boolean = false): String {
        val today = LocalDate.now(zone())
        val date = time.toLocalDate()
        val day = when (date) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            today.minusDays(1) -> "yesterday"
            else -> if (date.year == today.year) {
                time.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.getDefault()))
            } else {
                time.format(DateTimeFormatter.ofPattern("EEE MMM d yyyy", Locale.getDefault()))
            }
        }
        if (allDay) return day
        return "$day " + time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
            .replace(":00 ", " ")
    }

    const val ISO_HELP = "ISO 8601 local time with offset, e.g. 2026-07-12T15:00:00-07:00, or a date like 2026-07-12 for all-day."
}

internal data class ParsedTime(val time: ZonedDateTime, val dateOnly: Boolean) {
    val millis: Long get() = time.toInstant().toEpochMilli()
}
