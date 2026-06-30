package com.tombo.billyassistant.companion.agent.tools

import com.tombo.billyassistant.companion.calendar.AndroidCalendarTools
import com.tombo.billyassistant.companion.calendar.CalendarEventsResult
import com.tombo.billyassistant.companion.calendar.CreateCalendarEventRequest
import com.tombo.billyassistant.companion.calendar.CreateCalendarEventResult
import com.tombo.billyassistant.companion.calendar.DeleteCalendarEventsResult
import com.tombo.billyassistant.companion.calendar.WritableCalendarsResult
import com.tombo.billyassistant.companion.google.GoogleCalendarApiTools
import com.tombo.billyassistant.companion.google.GoogleCalendarGhostDeleteResult
import com.tombo.billyassistant.companion.google.GoogleCalendarResult
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

class CalendarCompanionTool(
    private val calendarTools: AndroidCalendarTools,
    private val googleCalendarApiTools: GoogleCalendarApiTools? = null,
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        JSONObject()
            .put("name", "list_calendar_events")
            .put("description", "List calendar events. If no valid time window is provided, defaults to today's events. Use max_results=1 for the next event.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "start_millis" to integerSchema("Optional start of the query window as Unix epoch milliseconds. Defaults to now."),
                        "end_millis" to integerSchema("Optional end of the query window as Unix epoch milliseconds. Defaults to 90 days from now."),
                        "max_results" to integerSchema("Maximum number of events to return. Use 1 for the next event. Defaults to 10."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "create_calendar_event")
            .put("description", "Create a Google Calendar event through the Google Calendar API. Do not create local Android provider fallback events.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("title", "start_millis", "end_millis"),
                    properties = mapOf(
                        "title" to stringSchema("Event title."),
                        "start_millis" to integerSchema("Event start time as Unix epoch milliseconds."),
                        "end_millis" to integerSchema("Event end time as Unix epoch milliseconds."),
                        "description" to stringSchema("Optional event description."),
                        "calendar_id" to stringSchema("Optional Google Calendar ID, exactly as returned by list_writable_calendars. If omitted and multiple writable calendars are available, Billy should ask the user."),
                        "calendar_hint" to stringSchema("Optional natural-language calendar name or intent from the user, such as primary, personal, work, family, or an account email. Use this instead of listing calendars when creating an event."),
                        "create_meet_link" to booleanSchema("Whether to add a Google Meet link. Use true when the user asks for a video meeting, Google Meet, call, or conference link."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "query_calendar_freebusy")
            .put("description", "Query Google Calendar free/busy blocks for scheduling. Use when the user asks whether they are free or busy in a time window.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("start_millis", "end_millis"),
                    properties = mapOf(
                        "start_millis" to integerSchema("Start of the query window as Unix epoch milliseconds."),
                        "end_millis" to integerSchema("End of the query window as Unix epoch milliseconds."),
                        "calendar_ids" to stringSchema("Optional comma-separated Google Calendar IDs. Leave blank to check visible calendars."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "find_calendar_availability")
            .put("description", "Find open calendar slots of a requested duration. Use before proposing meeting times or scheduling when the user asks for availability.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("start_millis", "end_millis", "duration_minutes"),
                    properties = mapOf(
                        "start_millis" to integerSchema("Start of the search window as Unix epoch milliseconds."),
                        "end_millis" to integerSchema("End of the search window as Unix epoch milliseconds."),
                        "duration_minutes" to integerSchema("Required open duration in minutes."),
                        "max_results" to integerSchema("Maximum number of slots to return. Defaults to 5."),
                        "calendar_ids" to stringSchema("Optional comma-separated Google Calendar IDs. Leave blank to check visible calendars."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "list_writable_calendars")
            .put("description", "List writable calendars available on this Android phone.")
            .put("parameters", objectSchema(required = emptyList(), properties = emptyMap())),
        JSONObject()
            .put("name", "delete_billy_local_calendar_ghosts")
            .put("description", "Delete local Android Calendar Provider events that Billy/Bobby created as fallback ghost events. Prefer delete_billy_calendar_ghosts for normal cleanup requests.")
            .put("parameters", objectSchema(required = emptyList(), properties = emptyMap())),
        JSONObject()
            .put("name", "delete_billy_calendar_ghosts")
            .put("description", "Delete Billy/Bobby ghost calendar events from both local Android Calendar Provider and Google Calendar API.")
            .put("parameters", objectSchema(required = emptyList(), properties = emptyMap())),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        return when (name) {
            "list_calendar_events" -> {
                val maxResults = args.optionalInt("max_results") ?: DEFAULT_CALENDAR_RESULTS
                val window = calendarQueryWindow(args, maxResults)
                val googleResult = googleCalendarApiTools?.listEvents(
                    startMillis = window.startMillis,
                    endMillis = window.endMillis,
                    maxResults = maxResults,
                )
                if (googleResult is GoogleCalendarResult.Success) {
                    val response = googleResult.toJson()
                    return CompanionToolExecution(
                        response = response.put("source", "google_calendar_api"),
                        finalText = calendarEventsWatchSummary(response, window),
                    )
                }
                if (googleResult != null) {
                    val response = googleResult.toJson().put("source", "google_calendar_api")
                    return CompanionToolExecution(
                        response = response,
                        finalText = response.optString("summary").ifBlank {
                            "Google Calendar lookup failed. I did not use Android local calendar ghosts."
                        },
                    )
                }
                val localResponse = calendarTools.listEvents(
                    startMillis = window.startMillis,
                    endMillis = window.endMillis,
                ).toJson()
                if (localResponse.optString("status") == "ok") {
                    return CompanionToolExecution(
                        response = localResponse.put("source", "android_calendar_provider_visible_fallback"),
                        finalText = calendarEventsWatchSummary(localResponse, window),
                    )
                }
                CompanionToolExecution(
                    response = googleResult?.toJson() ?: localResponse,
                    finalText = (googleResult?.toJson() ?: localResponse).optString("summary").ifBlank { "Calendar lookup failed." },
                )
            }
            "create_calendar_event" -> {
                validateCalendarCreateTiming(args)?.let { message ->
                    return CompanionToolExecution(
                        response = JSONObject()
                            .put("status", "needs_clarification")
                            .put("summary", message),
                        finalText = message,
                    )
                }
                val googleTools = googleCalendarApiTools
                    ?: return CompanionToolExecution(
                        response = JSONObject()
                            .put("status", "error")
                            .put("summary", "Google Calendar API tool is unavailable; not creating a local Android calendar fallback."),
                        finalText = "Google Calendar API is unavailable. I did not create a local ghost event.",
                    )
                val calendarId = args.optionalString("calendar_id")
                if (calendarId.isNullOrBlank()) {
                    val calendarsResult = googleTools.listCalendars()
                    val calendarsResponse = calendarsResult.toJson()
                    if (calendarsResponse.optString("status") != "ok") {
                        return CompanionToolExecution(
                            response = calendarsResponse,
                            finalText = calendarsResponse.optString("summary").ifBlank { "Google Calendar lookup failed." },
                        )
                    }
                    val writableCalendars = preferredWritableCalendars(calendarsResponse.optJSONArray("writable_calendars") ?: JSONArray())
                    if (writableCalendars.length() == 0) {
                        return CompanionToolExecution(
                            response = calendarsResponse,
                            finalText = "Billy can read Google Calendar, but no writable calendars are available.",
                        )
                    }
                    val calendarHint = args.optionalString("calendar_hint")
                    val hintedCalendar = resolveCalendarHint(calendarHint, writableCalendars)
                    if (hintedCalendar != null) {
                        args.put("calendar_id", hintedCalendar.optString("id"))
                    }
                    if (args.optionalString("calendar_id").isNullOrBlank() && writableCalendars.length() > 1) {
                        return CompanionToolExecution(
                            response = JSONObject()
                                .put("status", "needs_clarification")
                                .put("summary", "Multiple writable Google calendars are available."),
                            clarificationCard = calendarChoiceCard(args, writableCalendars),
                        )
                    }
                    if (args.optionalString("calendar_id").isNullOrBlank()) {
                        args.put("calendar_id", writableCalendars.optJSONObject(0)?.optString("id").orEmpty())
                    }
                }
                googleTools.createEvent(
                    title = args.optString("title"),
                    startMillis = args.optLong("start_millis"),
                    endMillis = args.optLong("end_millis"),
                    description = args.optString("description").ifBlank { null },
                    timeZoneId = TimeZone.getDefault().id,
                    calendarId = args.optionalString("calendar_id"),
                    createMeetLink = args.optBoolean("create_meet_link", false),
                ).toExecution(finalOnSuccess = true)
            }
            "query_calendar_freebusy" -> {
                val googleTools = googleCalendarApiTools
                    ?: return CompanionToolExecution(
                        response = JSONObject()
                            .put("status", "error")
                            .put("summary", "Google Calendar API tool is unavailable."),
                        finalText = "Google Calendar API is unavailable.",
                    )
                googleTools.queryFreeBusy(
                    startMillis = args.optLong("start_millis"),
                    endMillis = args.optLong("end_millis"),
                    calendarIds = args.optionalStringList("calendar_ids"),
                ).toExecution(finalOnSuccess = true)
            }
            "find_calendar_availability" -> {
                val googleTools = googleCalendarApiTools
                    ?: return CompanionToolExecution(
                        response = JSONObject()
                            .put("status", "error")
                            .put("summary", "Google Calendar API tool is unavailable."),
                        finalText = "Google Calendar API is unavailable.",
                    )
                googleTools.findAvailability(
                    startMillis = args.optLong("start_millis"),
                    endMillis = args.optLong("end_millis"),
                    durationMinutes = args.optionalInt("duration_minutes") ?: 30,
                    maxResults = args.optionalInt("max_results") ?: 5,
                    calendarIds = args.optionalStringList("calendar_ids"),
                ).toExecution(finalOnSuccess = true)
            }
            "list_writable_calendars" -> {
                val googleResult = googleCalendarApiTools?.listCalendars()
                if (googleResult != null) {
                    val response = googleResult.toJson().put("source", "google_calendar_api")
                    return CompanionToolExecution(
                        response = response,
                    )
                }
                val response = calendarTools.listWritableCalendars().toJson()
                CompanionToolExecution(
                    response = response,
                )
            }
            "delete_billy_local_calendar_ghosts" -> {
                val response = calendarTools.deleteBillyLocalGhostEvents().toJson()
                CompanionToolExecution(
                    response = response,
                    finalText = response.optString("summary").takeIf { it.isNotBlank() },
                )
            }
            "delete_billy_calendar_ghosts" -> {
                val localResponse = calendarTools.deleteBillyLocalGhostEvents().toJson()
                val googleResult = googleCalendarApiTools?.deleteBillyGhostEvents()
                val response = JSONObject()
                    .put("status", if (localResponse.optString("status") == "ok" && googleResult !is GoogleCalendarGhostDeleteResult.Failed) "ok" else "partial")
                    .put("summary", ghostCleanupWatchSummary(localResponse, googleResult))
                    .put("local", localResponse)
                    .put("google", googleResult?.toJson() ?: JSONObject().put("status", "unavailable"))
                CompanionToolExecution(
                    response = response,
                    finalText = response.optString("summary").takeIf { it.isNotBlank() },
                )
            }
            else -> return null
        }
    }
}

private fun validateCalendarCreateTiming(args: JSONObject): String? {
    val start = args.optionalLong("start_millis")?.takeIf { it > 0L }
        ?: return "I need the event date and start time."
    val end = args.optionalLong("end_millis")?.takeIf { it > 0L }
        ?: return "I need the event end time or duration."
    if (end <= start) {
        return "I need an end time after the start time."
    }
    return null
}

private fun calendarChoiceCard(args: JSONObject, writableCalendars: JSONArray): ClarificationCard {
    val title = args.optString("title").ifBlank { "event" }
    val start = args.optLong("start_millis")
    val end = args.optLong("end_millis")
    val pendingOptions = mutableListOf<PendingCalendarOption>()
    for (i in 0 until minOf(writableCalendars.length(), 4)) {
        val calendar = writableCalendars.optJSONObject(i) ?: continue
        val name = calendar.optString("summary").ifBlank { calendar.optString("id") }
        val display = "${i + 1}. ${name.take(44)}"
        pendingOptions += PendingCalendarOption(
            index = i + 1,
            display = display,
            label = name.take(48),
            calendarId = calendar.optString("id"),
            calendarName = name,
        )
    }
    val token = PendingCalendarClarifications.putCreate(
        PendingCalendarCreate(
            title = title,
            startMillis = start,
            endMillis = end,
            description = args.optString("description").takeIf { it.isNotBlank() },
            createMeetLink = args.optBoolean("create_meet_link", false),
            options = pendingOptions,
        ),
    )
    return ClarificationCard(
        question = "Which calendar?",
        context = "calendar_create_token=$token",
        options = pendingOptions.map { it.display }.ifEmpty { listOf("Primary calendar", "Cancel") },
    )
}

private fun preferredWritableCalendars(writableCalendars: JSONArray): JSONArray {
    val visible = JSONArray()
    val fallback = JSONArray()
    for (i in 0 until writableCalendars.length()) {
        val calendar = writableCalendars.optJSONObject(i) ?: continue
        if (calendar.optBoolean("selected", true) && !calendar.optBoolean("hidden")) {
            visible.put(calendar)
        }
        fallback.put(calendar)
    }
    return if (visible.length() > 0) visible else fallback
}

private fun resolveCalendarHint(hint: String?, writableCalendars: JSONArray): JSONObject? {
    val normalizedHint = hint.normalizedCalendarHint()
    if (normalizedHint.isBlank()) {
        return null
    }
    val calendars = buildList {
        for (i in 0 until writableCalendars.length()) {
            writableCalendars.optJSONObject(i)?.let { add(it) }
        }
    }
    if (calendars.isEmpty()) {
        return null
    }
    val primaryWords = setOf("primary", "personal", "main", "default", "my calendar", "own calendar")
    if (normalizedHint in primaryWords || primaryWords.any { normalizedHint.contains(it) }) {
        calendars.firstOrNull { it.optBoolean("primary") }?.let { return it }
    }
    val exact = calendars.filter { calendar ->
        val summary = calendar.optString("summary").normalizedCalendarHint()
        val id = calendar.optString("id").normalizedCalendarHint()
        normalizedHint == summary || normalizedHint == id
    }
    if (exact.size == 1) {
        return exact.first()
    }
    val contains = calendars.filter { calendar ->
        val summary = calendar.optString("summary").normalizedCalendarHint()
        val id = calendar.optString("id").normalizedCalendarHint()
        summary.contains(normalizedHint) || id.contains(normalizedHint)
    }
    return contains.singleOrNull()
}

private fun String?.normalizedCalendarHint(): String {
    return this
        ?.trim()
        ?.lowercase(Locale.US)
        ?.replace(Regex("\\s+"), " ")
        ?.removePrefix("my ")
        ?.removeSuffix(" calendar")
        .orEmpty()
}

private data class CalendarQueryWindow(
    val startMillis: Long,
    val endMillis: Long,
)

private fun calendarQueryWindow(args: JSONObject, maxResults: Int): CalendarQueryWindow {
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val requestedStart = args.optionalLong("start_millis")
    val requestedEnd = args.optionalLong("end_millis")
    val noExplicitWindow = requestedStart == null && requestedEnd == null
    val start = requestedStart?.takeIf { it > 0L }
        ?: if (noExplicitWindow && maxResults != 1) startOfTodayMillis(zone) else now
    val defaultEnd = if (noExplicitWindow && maxResults == 1) {
        start + DEFAULT_CALENDAR_LOOKAHEAD_MS
    } else {
        endOfDayMillis(start, zone).takeIf { it > start } ?: (start + ONE_DAY_MS)
    }
    val end = requestedEnd?.takeIf { it > start } ?: defaultEnd
    return CalendarQueryWindow(start, end)
}

private fun startOfTodayMillis(zone: ZoneId): Long {
    return LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
}

private fun endOfDayMillis(millis: Long, zone: ZoneId): Long {
    return Instant.ofEpochMilli(millis)
        .atZone(zone)
        .toLocalDate()
        .atTime(LocalTime.MAX)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
}

private fun calendarEventsWatchSummary(response: JSONObject, window: CalendarQueryWindow): String {
    if (response.optString("status") != "ok") {
        return response.optString("summary").ifBlank { "Calendar lookup failed." }
    }
    val events = response.optJSONArray("events") ?: JSONArray()
    val label = if (isSingleDayWindow(window)) "Today" else "Calendar"
    if (events.length() == 0) {
        return response.optString("summary").ifBlank {
            "No calendar events ${if (label == "Today") "today" else "found"}."
        }
    }
    val limit = minOf(events.length(), 5)
    val lines = mutableListOf("$label:")
    for (i in 0 until limit) {
        val event = events.optJSONObject(i) ?: continue
        lines += "- ${calendarEventTime(event)} ${calendarEventTitle(event)}".trimEnd()
    }
    if (events.length() > limit) {
        lines += "- +${events.length() - limit} more"
    }
    return lines.joinToString("\n")
}

private fun calendarEventTitle(event: JSONObject): String {
    return event.optString("summary")
        .ifBlank { event.optString("title") }
        .ifBlank { "(untitled)" }
}

private fun calendarEventTime(event: JSONObject): String {
    if (event.optBoolean("all_day")) {
        return "All day"
    }
    val localMillis = event.optionalLong("start_millis")
    if (localMillis != null && localMillis > 0L) {
        return TIME_FORMAT.format(Instant.ofEpochMilli(localMillis).atZone(ZoneId.systemDefault()))
    }
    val start = event.optJSONObject("start") ?: return ""
    if (start.optString("date").isNotBlank() && start.optString("dateTime").isBlank()) {
        return "All day"
    }
    val dateTime = start.optString("dateTime")
    return runCatching { TIME_FORMAT.format(ZonedDateTime.parse(dateTime)) }.getOrDefault("")
}

private fun isSingleDayWindow(window: CalendarQueryWindow): Boolean {
    return window.endMillis - window.startMillis <= ONE_DAY_MS + 60_000L
}

private fun googleCalendarsWatchSummary(response: JSONObject): String {
    if (response.optString("status") != "ok") {
        return response.optString("summary").ifBlank { "Google Calendar lookup failed." }
    }
    val writable = response.optJSONArray("writable_calendars") ?: JSONArray()
    if (writable.length() == 0) {
        return response.optString("summary").ifBlank { "No writable Google calendars." }
    }
    val lines = mutableListOf("Writable calendars:")
    for (i in 0 until minOf(writable.length(), 5)) {
        val calendar = writable.optJSONObject(i) ?: continue
        val marker = when {
            calendar.optBoolean("primary") -> " primary"
            calendar.optBoolean("hidden") -> " hidden"
            !calendar.optBoolean("selected", true) -> " unselected"
            else -> ""
        }
        lines += "- ${calendar.optString("summary").ifBlank { calendar.optString("id") }}$marker"
    }
    if (writable.length() > 5) {
        lines += "- +${writable.length() - 5} more"
    }
    return lines.joinToString("\n")
}

private fun ghostCleanupWatchSummary(localResponse: JSONObject, googleResult: GoogleCalendarGhostDeleteResult?): String {
    val localText = localResponse.optString("summary").ifBlank { "Local ghost cleanup finished." }
    val googleText = googleResult?.summary ?: "Google Calendar cleanup unavailable."
    return "$localText\n$googleText"
}

private fun GoogleCalendarResult.toExecution(finalOnSuccess: Boolean = false): CompanionToolExecution {
    val response = toJson()
    return CompanionToolExecution(
        response = response,
        finalText = if (finalOnSuccess || response.optString("status") != "ok") calendarResultWatchSummary(response, summary) else null,
    )
}

private fun calendarResultWatchSummary(response: JSONObject, fallback: String): String {
    if (response.optString("status") != "ok") {
        return response.optString("summary").ifBlank { fallback }
    }
    response.optJSONArray("available_slots")?.let { slots ->
        if (slots.length() == 0) {
            return response.optString("summary").ifBlank { fallback }
        }
        val lines = mutableListOf("Open slots:")
        for (i in 0 until minOf(slots.length(), 5)) {
            val slot = slots.optJSONObject(i) ?: continue
            lines += "- ${slotTime(slot.optLong("start_millis"))}-${slotTime(slot.optLong("end_millis"))}"
        }
        return lines.joinToString("\n")
    }
    response.optJSONArray("busy_slots")?.let { slots ->
        if (slots.length() == 0) {
            return "Free in that window."
        }
        val lines = mutableListOf("Busy:")
        for (i in 0 until minOf(slots.length(), 5)) {
            val slot = slots.optJSONObject(i) ?: continue
            lines += "- ${slotTime(slot.optLong("start_millis"))}-${slotTime(slot.optLong("end_millis"))} ${slot.optString("calendar_name").take(28)}".trimEnd()
        }
        if (slots.length() > 5) {
            lines += "- +${slots.length() - 5} more"
        }
        return lines.joinToString("\n")
    }
    return response.optString("summary").ifBlank { fallback }
}

private fun slotTime(millis: Long): String {
    return if (millis > 0L) {
        TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    } else {
        "?"
    }
}

private fun GoogleCalendarResult.toJson(): JSONObject {
    return when (this) {
        is GoogleCalendarResult.Success -> payload
        is GoogleCalendarResult.NeedsScope -> JSONObject()
            .put("status", "needs_scope")
            .put("summary", summary)
            .put("missing_scopes", JSONArray(scopes))
        is GoogleCalendarResult.Rejected -> JSONObject()
            .put("status", "rejected")
            .put("summary", reason)
            .put("reason", reason)
        is GoogleCalendarResult.Failed -> JSONObject()
            .put("status", "error")
            .put("summary", reason)
            .put("reason", reason)
    }
}

private fun GoogleCalendarGhostDeleteResult.toJson(): JSONObject {
    return when (this) {
        is GoogleCalendarGhostDeleteResult.Deleted -> JSONObject()
            .put("status", if (failures.isEmpty()) "ok" else "partial")
            .put("summary", summary)
            .put("deleted_count", deletedCount)
            .put("matched_count", matchedCount)
            .put("checked_calendar_count", checkedCalendarCount)
            .put("failures", JSONArray(failures))
        is GoogleCalendarGhostDeleteResult.NeedsScope -> JSONObject()
            .put("status", "needs_scope")
            .put("summary", summary)
            .put("missing_scopes", JSONArray(scopes))
        is GoogleCalendarGhostDeleteResult.Failed -> JSONObject()
            .put("status", "error")
            .put("summary", summary)
            .put("reason", reason)
    }
}

private fun CalendarEventsResult.toJson(): JSONObject {
    val base = JSONObject().put("summary", summary)
    return when (this) {
        is CalendarEventsResult.Success -> base
            .put("status", "ok")
            .put(
                "events",
                JSONArray().also { array ->
                    events.forEach { event ->
                        array.put(
                            JSONObject()
                                .put("event_id", event.eventId)
                                .put("title", event.title)
                                .put("start_millis", event.startMillis)
                                .put("end_millis", event.endMillis)
                                .put("calendar_id", event.calendarId)
                                .put("calendar_name", event.calendarName)
                                .put("description", event.description)
                                .put("location", event.location)
                                .put("all_day", event.allDay),
                        )
                    }
                },
            )
        is CalendarEventsResult.NotAuthorized -> base
            .put("status", "not_authorized")
            .put("reason", reason)
            .put("missing_permissions", JSONArray(missingPermissions))
        is CalendarEventsResult.Rejected -> base
            .put("status", "rejected")
            .put("reason", reason)
        is CalendarEventsResult.Failed -> base
            .put("status", "error")
            .put("reason", reason)
    }
}

private fun CreateCalendarEventResult.toJson(): JSONObject {
    val base = JSONObject().put("summary", summary)
    return when (this) {
        is CreateCalendarEventResult.Created -> base
            .put("status", "ok")
            .put("event_id", eventId)
            .put("calendar_id", calendar.id)
            .put("calendar_name", calendar.name)
            .put("account_name", calendar.accountName)
            .put("account_type", calendar.accountType)
            .put("visible", calendar.visible)
            .put("sync_events", calendar.syncEvents)
            .put("verified", verified)
        is CreateCalendarEventResult.NotAuthorized -> base
            .put("status", "not_authorized")
            .put("reason", reason)
            .put("missing_permissions", JSONArray(missingPermissions))
        is CreateCalendarEventResult.Rejected -> base
            .put("status", "rejected")
            .put("reason", reason)
        is CreateCalendarEventResult.Failed -> base
            .put("status", "error")
            .put("reason", reason)
    }
}

private fun WritableCalendarsResult.toJson(): JSONObject {
    val base = JSONObject().put("summary", summary)
    return when (this) {
        is WritableCalendarsResult.Success -> base
            .put("status", "ok")
            .put(
                "calendars",
                JSONArray().also { array ->
                    calendars.forEach { calendar ->
                        array.put(
                            JSONObject()
                                .put("id", calendar.id)
                                .put("name", calendar.name)
                                .put("account_name", calendar.accountName)
                                .put("account_type", calendar.accountType)
                                .put("owner_account", calendar.ownerAccount)
                                .put("access_level", calendar.accessLevel)
                                .put("visible", calendar.visible)
                                .put("sync_events", calendar.syncEvents)
                                .put("is_primary", calendar.isPrimary),
                        )
                    }
                },
            )
        is WritableCalendarsResult.NotAuthorized -> base
            .put("status", "not_authorized")
            .put("reason", reason)
            .put("missing_permissions", JSONArray(missingPermissions))
        is WritableCalendarsResult.Failed -> base
            .put("status", "error")
            .put("reason", reason)
    }
}

private fun DeleteCalendarEventsResult.toJson(): JSONObject {
    val base = JSONObject().put("summary", summary)
    return when (this) {
        is DeleteCalendarEventsResult.Deleted -> base
            .put("status", "ok")
            .put("deleted_count", deletedCount)
            .put("matched_count", matchedCount)
            .put(
                "events",
                JSONArray().also { array ->
                    events.forEach { event ->
                        array.put(
                            JSONObject()
                                .put("event_id", event.eventId)
                                .put("title", event.title)
                                .put("calendar_id", event.calendarId)
                                .put("calendar_name", event.calendarName)
                                .put("start_millis", event.startMillis),
                        )
                    }
                },
            )
        is DeleteCalendarEventsResult.NotAuthorized -> base
            .put("status", "not_authorized")
            .put("reason", reason)
            .put("missing_permissions", JSONArray(missingPermissions))
        is DeleteCalendarEventsResult.Failed -> base
            .put("status", "error")
            .put("reason", reason)
    }
}

private fun JSONObject.optionalLong(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}

private fun JSONObject.optionalInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

private fun JSONObject.optionalString(name: String): String? {
    return if (has(name) && !isNull(name)) optString(name).trim().takeIf { it.isNotBlank() } else null
}

private fun JSONObject.optionalStringList(name: String): List<String> {
    return optionalString(name)
        ?.split(',', ';', '|')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()
}

private const val DEFAULT_CALENDAR_RESULTS = 10
private const val DEFAULT_CALENDAR_LOOKAHEAD_MS = 90L * 24L * 60L * 60L * 1000L
private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
