package com.tombo.billyassistant.companion.google

import com.tombo.billyassistant.companion.auth.GoogleAccessTokenProvider
import com.tombo.billyassistant.companion.auth.GoogleAccessTokenResult
import com.tombo.billyassistant.companion.auth.GoogleApiScopes
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class GoogleCalendarApiTools(
    private val tokenProvider: GoogleAccessTokenProvider,
    private val http: GoogleApiHttp = GoogleApiHttp(),
) {
    fun listEvents(startMillis: Long, endMillis: Long, maxResults: Int = 10): GoogleCalendarResult {
        if (endMillis <= startMillis) {
            return GoogleCalendarResult.Rejected("End time must be after start time.")
        }
        return withToken { token ->
            val calendars = when (val result = fetchCalendars(token)) {
                is CalendarListLookup.Success -> result.calendars
                is CalendarListLookup.Failed -> return@withToken GoogleCalendarResult.Failed(result.reason)
            }
            if (calendars.isEmpty()) {
                return@withToken GoogleCalendarResult.Success(
                    summary = "No Google calendars are visible to Billy.",
                    payload = JSONObject()
                        .put("status", "ok")
                        .put("summary", "No Google calendars are visible to Billy.")
                        .put("calendars", JSONArray())
                        .put("events", JSONArray()),
                )
            }

            val events = mutableListOf<JSONObject>()
            val failures = mutableListOf<String>()
            calendars.forEach { calendar ->
                when (val result = listEventsForCalendar(token, calendar, startMillis, endMillis, maxResults)) {
                    is CalendarEventsLookup.Success -> events += result.events
                    is CalendarEventsLookup.Failed -> failures += result.reason
                }
            }
            val sortedEvents = events
                .sortedBy { it.optLong("start_millis", Long.MAX_VALUE) }
                .take(maxResults.coerceIn(1, 50))
            val eventsJson = JSONArray().also { array -> sortedEvents.forEach { array.put(it) } }
            val checkedNames = calendars.take(4).joinToString { it.summary }.let { names ->
                if (calendars.size > 4) "$names, +${calendars.size - 4} more" else names
            }
            val summary = when (sortedEvents.size) {
                0 -> "No Google Calendar events found. Checked: $checkedNames."
                1 -> "Found 1 Google Calendar event across ${calendars.size} visible calendars."
                else -> "Found ${sortedEvents.size} Google Calendar events across ${calendars.size} visible calendars."
            }
            GoogleCalendarResult.Success(
                summary = summary,
                payload = JSONObject()
                    .put("status", "ok")
                    .put("summary", summary)
                    .put("calendars_checked", calendars.size)
                    .put("calendars", JSONArray().also { array -> calendars.forEach { array.put(it.toJson()) } })
                    .put("events", eventsJson)
                    .put("partial_failures", JSONArray(failures)),
            )
        }
    }

    fun listCalendars(): GoogleCalendarResult {
        return withToken { token ->
            val calendars = when (val result = fetchCalendars(token)) {
                is CalendarListLookup.Success -> result.calendars
                is CalendarListLookup.Failed -> return@withToken GoogleCalendarResult.Failed(result.reason)
            }
            val writableCalendars = calendars.filter { it.canWriteEvents() }
            val summary = when {
                calendars.isEmpty() -> "No Google calendars are visible to Billy."
                writableCalendars.isEmpty() -> "Billy can read ${calendars.size} Google calendars, but none are writable."
                writableCalendars.size == 1 -> "Billy can write to 1 Google calendar: ${writableCalendars.first().summary}."
                else -> "Billy can write to ${writableCalendars.size} Google calendars."
            }
            GoogleCalendarResult.Success(
                summary = summary,
                payload = JSONObject()
                    .put("status", "ok")
                    .put("summary", summary)
                    .put("calendars", JSONArray().also { array -> calendars.forEach { array.put(it.toJson()) } })
                    .put("writable_calendars", JSONArray().also { array -> writableCalendars.forEach { array.put(it.toJson()) } }),
            )
        }
    }

    fun deleteBillyGhostEvents(): GoogleCalendarGhostDeleteResult {
        return when (val token = tokenProvider.getAccessToken(GoogleApiScopes.calendar)) {
            is GoogleAccessTokenResult.NeedsUserGrant -> GoogleCalendarGhostDeleteResult.NeedsScope(token.scopes)
            is GoogleAccessTokenResult.Failed -> GoogleCalendarGhostDeleteResult.Failed(token.reason)
            is GoogleAccessTokenResult.Authorized -> {
                val calendars = when (val result = fetchCalendars(token.accessToken)) {
                    is CalendarListLookup.Success -> result.calendars
                    is CalendarListLookup.Failed -> return GoogleCalendarGhostDeleteResult.Failed(result.reason)
                }
                val now = System.currentTimeMillis()
                val startMillis = now - GHOST_CLEANUP_WINDOW_MS
                val endMillis = now + GHOST_CLEANUP_WINDOW_MS
                val matched = mutableListOf<String>()
                var deleted = 0
                val failures = mutableListOf<String>()
                calendars.forEach { calendar ->
                    when (val eventsResult = listEventsForCalendar(token.accessToken, calendar, startMillis, endMillis, 250)) {
                        is CalendarEventsLookup.Success -> {
                            eventsResult.events
                                .filter { it.isBillyGhostEvent() }
                                .forEach { event ->
                                    val eventId = event.optString("id")
                                    if (eventId.isBlank()) {
                                        return@forEach
                                    }
                                    matched += "${event.optString("summary", "(untitled)")} on ${calendar.summary}"
                                    when (val delete = http.delete("$API_BASE/calendars/${encode(calendar.id)}/events/${encode(eventId)}", token.accessToken)) {
                                        is GoogleHttpResult.Success -> deleted += 1
                                        is GoogleHttpResult.HttpError -> failures += "${calendar.summary}: HTTP ${delete.responseCode}: ${delete.reason}"
                                        is GoogleHttpResult.Failed -> failures += "${calendar.summary}: ${delete.reason}"
                                    }
                                }
                        }
                        is CalendarEventsLookup.Failed -> failures += eventsResult.reason
                    }
                }
                GoogleCalendarGhostDeleteResult.Deleted(
                    deletedCount = deleted,
                    matchedCount = matched.size,
                    checkedCalendarCount = calendars.size,
                    failures = failures,
                )
            }
        }
    }

    fun queryFreeBusy(
        startMillis: Long,
        endMillis: Long,
        calendarIds: List<String> = emptyList(),
    ): GoogleCalendarResult {
        if (endMillis <= startMillis) {
            return GoogleCalendarResult.Rejected("End time must be after start time.")
        }
        return withToken { token ->
            val calendars = when (val result = fetchCalendars(token)) {
                is CalendarListLookup.Success -> result.calendars
                is CalendarListLookup.Failed -> return@withToken GoogleCalendarResult.Failed(result.reason)
            }
            val requestedIds = calendarIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()
            val targets = if (requestedIds.isNotEmpty()) {
                calendars.filter { it.id in requestedIds }
            } else {
                calendars.filter { it.selected && !it.hidden }.ifEmpty { calendars }
            }
            if (targets.isEmpty()) {
                return@withToken GoogleCalendarResult.Rejected("No matching Google calendars are visible to Billy.")
            }
            val body = JSONObject()
                .put("timeMin", Instant.ofEpochMilli(startMillis).toString())
                .put("timeMax", Instant.ofEpochMilli(endMillis).toString())
                .put("items", JSONArray().also { array ->
                    targets.take(MAX_FREEBUSY_CALENDARS).forEach { calendar ->
                        array.put(JSONObject().put("id", calendar.id))
                    }
                })
            when (val result = http.post("$API_BASE/freeBusy", token, body)) {
                is GoogleHttpResult.Success -> {
                    val response = JSONObject(result.body)
                    val busySlots = JSONArray()
                    val calendarsJson = response.optJSONObject("calendars") ?: JSONObject()
                    targets.forEach { calendar ->
                        val busy = calendarsJson.optJSONObject(calendar.id)?.optJSONArray("busy") ?: JSONArray()
                        for (i in 0 until busy.length()) {
                            val slot = busy.optJSONObject(i) ?: continue
                            val start = slot.optString("start")
                            val end = slot.optString("end")
                            busySlots.put(
                                JSONObject()
                                    .put("calendar_id", calendar.id)
                                    .put("calendar_name", calendar.summary)
                                    .put("start", start)
                                    .put("end", end)
                                    .put("start_millis", parseCalendarMillis(start))
                                    .put("end_millis", parseCalendarMillis(end)),
                            )
                        }
                    }
                    val summary = when (busySlots.length()) {
                        0 -> "No busy time found across ${targets.size} Google calendars."
                        1 -> "Found 1 busy block across ${targets.size} Google calendars."
                        else -> "Found ${busySlots.length()} busy blocks across ${targets.size} Google calendars."
                    }
                    GoogleCalendarResult.Success(
                        summary = summary,
                        payload = JSONObject()
                            .put("status", "ok")
                            .put("summary", summary)
                            .put("start_millis", startMillis)
                            .put("end_millis", endMillis)
                            .put("calendars_checked", targets.size)
                            .put("calendars", JSONArray().also { array -> targets.forEach { array.put(it.toJson()) } })
                            .put("busy_slots", busySlots),
                    )
                }
                is GoogleHttpResult.HttpError -> GoogleCalendarResult.Failed(result.toHumanCalendarError())
                is GoogleHttpResult.Failed -> GoogleCalendarResult.Failed(result.toHumanCalendarError())
            }
        }
    }

    fun findAvailability(
        startMillis: Long,
        endMillis: Long,
        durationMinutes: Int,
        maxResults: Int = 5,
        calendarIds: List<String> = emptyList(),
    ): GoogleCalendarResult {
        val durationMillis = durationMinutes.coerceIn(5, 24 * 60) * 60_000L
        val freeBusy = queryFreeBusy(startMillis, endMillis, calendarIds)
        if (freeBusy !is GoogleCalendarResult.Success) {
            return freeBusy
        }
        val busySlots = freeBusy.payload.optJSONArray("busy_slots") ?: JSONArray()
        val merged = mergeBusyIntervals(busySlots)
        val available = JSONArray()
        var cursor = startMillis
        merged.forEach { busy ->
            if (busy.startMillis - cursor >= durationMillis) {
                available.put(slotJson(cursor, busy.startMillis))
            }
            cursor = maxOf(cursor, busy.endMillis)
        }
        if (endMillis - cursor >= durationMillis) {
            available.put(slotJson(cursor, endMillis))
        }
        val limited = JSONArray()
        for (i in 0 until minOf(available.length(), maxResults.coerceIn(1, 12))) {
            limited.put(available.optJSONObject(i))
        }
        val summary = when (limited.length()) {
            0 -> "No open slot found for ${durationMinutes.coerceAtLeast(5)} minutes."
            1 -> "Found 1 open slot."
            else -> "Found ${limited.length()} open slots."
        }
        return GoogleCalendarResult.Success(
            summary = summary,
            payload = JSONObject()
                .put("status", "ok")
                .put("summary", summary)
                .put("start_millis", startMillis)
                .put("end_millis", endMillis)
                .put("duration_minutes", durationMinutes.coerceAtLeast(5))
                .put("available_slots", limited)
                .put("busy_slots", busySlots)
                .put("calendars", freeBusy.payload.optJSONArray("calendars") ?: JSONArray()),
        )
    }

    fun createEvent(
        title: String,
        startMillis: Long,
        endMillis: Long,
        description: String?,
        timeZoneId: String,
        calendarId: String? = null,
        createMeetLink: Boolean = false,
    ): GoogleCalendarResult {
        if (title.isBlank()) {
            return GoogleCalendarResult.Rejected("Event title is blank.")
        }
        if (endMillis <= startMillis) {
            return GoogleCalendarResult.Rejected("Event end time must be after start time.")
        }
        return withToken { token ->
            val calendar = when (val result = fetchCalendars(token)) {
                is CalendarListLookup.Success -> {
                    val calendars = result.calendars
                    val requestedId = calendarId?.trim().orEmpty()
                    if (requestedId.isNotBlank()) {
                        calendars.firstOrNull { it.id == requestedId }
                            ?: return@withToken GoogleCalendarResult.Failed("Billy cannot see Google calendar \"$requestedId\".")
                    } else {
                        chooseDefaultWritableCalendar(calendars)
                            ?: return@withToken GoogleCalendarResult.Failed("No writable Google Calendar was visible to Billy.")
                    }
                }
                is CalendarListLookup.Failed -> return@withToken GoogleCalendarResult.Failed(result.reason)
            }
            if (!calendar.canWriteEvents()) {
                return@withToken GoogleCalendarResult.Rejected("Billy can read \"${calendar.summary}\" but cannot write events there.")
            }
            val zoneId = runCatching { ZoneId.of(timeZoneId) }.getOrElse { ZoneId.systemDefault() }
            val body = JSONObject()
                .put("summary", title.trim())
                .put("start", eventTime(startMillis, zoneId))
                .put("end", eventTime(endMillis, zoneId))
            description?.trim()?.takeIf { it.isNotBlank() }?.let { body.put("description", it) }
            if (createMeetLink) {
                body.put(
                    "conferenceData",
                    JSONObject().put(
                        "createRequest",
                        JSONObject()
                            .put("requestId", "billy-${UUID.randomUUID()}")
                            .put("conferenceSolutionKey", JSONObject().put("type", "hangoutsMeet")),
                    ),
                )
            }

            val createUrl = buildString {
                append("$API_BASE/calendars/${encode(calendar.id)}/events")
                if (createMeetLink) {
                    append("?conferenceDataVersion=1")
                }
            }
            when (val createResult = http.post(createUrl, token, body)) {
                is GoogleHttpResult.Success -> {
                    val created = JSONObject(createResult.body)
                    val eventId = created.optString("id")
                    val readback = if (eventId.isNotBlank()) {
                        http.get("$API_BASE/calendars/${encode(calendar.id)}/events/${encode(eventId)}", token)
                    } else {
                        GoogleHttpResult.Failed("Google Calendar did not return an event id.")
                    }
                    when (readback) {
                        is GoogleHttpResult.Success -> {
                            val verified = JSONObject(readback.body)
                            val calendarName = calendar.summary
                            val calendarId = calendar.id
                            val startText = verified.optJSONObject("start")?.toWatchTimeText().orEmpty()
                            val meetLink = verified.meetLink()
                            val summary = buildString {
                                append("Created \"")
                                append(verified.optString("summary", title.trim()))
                                append("\" on ")
                                append(calendarName)
                                if (startText.isNotBlank()) {
                                    append(" at ")
                                    append(startText)
                                }
                                append(".")
                                if (!calendar.selected || calendar.hidden) {
                                    append(" Enable that calendar in Google Calendar if it is not visible.")
                                }
                                if (meetLink.isNotBlank()) {
                                    append("\nMeet link added.")
                                }
                            }
                            GoogleCalendarResult.Success(
                                summary = summary,
                                payload = JSONObject()
                                    .put("status", "ok")
                                    .put("summary", summary)
                                    .put("event_id", verified.optString("id", eventId))
                                    .put("calendar_id", calendarId)
                                    .put("calendar_name", calendarName)
                                    .put("html_link", verified.optString("htmlLink"))
                                    .put("meet_link", meetLink)
                                    .put("verified", true)
                                    .put("event", verified),
                            )
                        }
                        is GoogleHttpResult.HttpError -> GoogleCalendarResult.Failed("Created event, but Billy could not verify it in Google Calendar.")
                        is GoogleHttpResult.Failed -> GoogleCalendarResult.Failed("Created event, but Billy could not verify it in Google Calendar.")
                    }
                }
                is GoogleHttpResult.HttpError -> GoogleCalendarResult.Failed(createResult.toHumanCalendarError())
                is GoogleHttpResult.Failed -> GoogleCalendarResult.Failed(createResult.toHumanCalendarError())
            }
        }
    }

    private fun fetchCalendars(token: String): CalendarListLookup {
        val calendars = mutableListOf<CalendarListEntry>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("$API_BASE/users/me/calendarList?showHidden=true&maxResults=250")
                pageToken?.takeIf { it.isNotBlank() }?.let { append("&pageToken=${encode(it)}") }
            }
            when (val result = http.get(url, token)) {
                is GoogleHttpResult.Success -> {
                    val json = JSONObject(result.body)
                    val items = json.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                        if (item.optBoolean("deleted")) {
                            continue
                        }
                        calendars += CalendarListEntry(
                            id = id,
                            summary = item.optString("summary").ifBlank { id },
                            accessRole = item.optString("accessRole"),
                            primary = item.optBoolean("primary"),
                            selected = item.optBoolean("selected", true),
                            hidden = item.optBoolean("hidden"),
                        )
                    }
                    pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
                }
                is GoogleHttpResult.HttpError -> return CalendarListLookup.Failed(result.toHumanCalendarError())
                is GoogleHttpResult.Failed -> return CalendarListLookup.Failed(result.toHumanCalendarError())
            }
        } while (pageToken != null)

        return CalendarListLookup.Success(
            calendars.sortedWith(
                compareByDescending<CalendarListEntry> { it.selected && !it.hidden }
                    .thenByDescending { it.primary }
                    .thenByDescending { !it.hidden }
                    .thenBy { it.summary.lowercase() },
            ),
        )
    }

    private fun chooseDefaultWritableCalendar(calendars: List<CalendarListEntry>): CalendarListEntry? {
        val visibleWritable = calendars
            .filter { it.canWriteEvents() }
            .sortedWith(
                compareByDescending<CalendarListEntry> { it.selected && !it.hidden }
                    .thenByDescending { it.primary }
                    .thenByDescending { !it.hidden }
                    .thenBy { it.summary.lowercase() },
            )
        return visibleWritable.firstOrNull { it.selected && !it.hidden }
            ?: visibleWritable.firstOrNull()
    }

    private fun listEventsForCalendar(
        token: String,
        calendar: CalendarListEntry,
        startMillis: Long,
        endMillis: Long,
        maxResults: Int,
    ): CalendarEventsLookup {
        val url = API_BASE + "/calendars/${encode(calendar.id)}/events" +
            "?singleEvents=true&orderBy=startTime&maxResults=${maxResults.coerceIn(1, 250)}" +
            "&timeMin=${encode(Instant.ofEpochMilli(startMillis).toString())}" +
            "&timeMax=${encode(Instant.ofEpochMilli(endMillis).toString())}"
        return when (val result = http.get(url, token)) {
            is GoogleHttpResult.Success -> {
                val rawEvents = JSONObject(result.body).optJSONArray("items") ?: JSONArray()
                val events = mutableListOf<JSONObject>()
                for (i in 0 until rawEvents.length()) {
                    val event = rawEvents.optJSONObject(i) ?: continue
                    if (event.optString("status").equals("cancelled", ignoreCase = true)) {
                        continue
                    }
                    events += event.normalizedForWatch(calendar)
                }
                CalendarEventsLookup.Success(events)
            }
            is GoogleHttpResult.HttpError -> CalendarEventsLookup.Failed("${calendar.summary}: ${result.toHumanCalendarError()}")
            is GoogleHttpResult.Failed -> CalendarEventsLookup.Failed("${calendar.summary}: ${result.toHumanCalendarError()}")
        }
    }

    private fun withToken(block: (String) -> GoogleCalendarResult): GoogleCalendarResult {
        return when (val token = tokenProvider.getAccessToken(GoogleApiScopes.calendar)) {
            is GoogleAccessTokenResult.Authorized -> block(token.accessToken)
            is GoogleAccessTokenResult.NeedsUserGrant -> GoogleCalendarResult.NeedsScope(token.scopes)
            is GoogleAccessTokenResult.Failed -> GoogleCalendarResult.Failed(token.reason)
        }
    }

    private fun eventTime(millis: Long, zoneId: ZoneId): JSONObject {
        return JSONObject()
            .put("dateTime", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(zoneId)))
            .put("timeZone", zoneId.id)
    }

    private fun JSONObject.normalizedForWatch(calendar: CalendarListEntry): JSONObject {
        val normalized = JSONObject(toString())
            .put("calendar_id", calendar.id)
            .put("calendar_name", calendar.summary)
        val start = normalized.optJSONObject("start")
        val end = normalized.optJSONObject("end")
        val startMillis = start?.toEventMillis() ?: 0L
        val endMillis = end?.toEventMillis() ?: startMillis
        normalized
            .put("start_millis", startMillis)
            .put("end_millis", endMillis)
            .put("all_day", start?.optString("date").orEmpty().isNotBlank() && start?.optString("dateTime").orEmpty().isBlank())
        if (normalized.optString("summary").isBlank()) {
            normalized.put("summary", "(busy)")
        }
        return normalized
    }

    private fun JSONObject.toEventMillis(): Long? {
        optString("dateTime").takeIf { it.isNotBlank() }?.let { dateTime ->
            return runCatching { ZonedDateTime.parse(dateTime).toInstant().toEpochMilli() }
                .getOrNull()
        }
        optString("date").takeIf { it.isNotBlank() }?.let { date ->
            return runCatching {
                LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
        }
        return null
    }

    private fun JSONObject.toWatchTimeText(): String {
        optString("dateTime").takeIf { it.isNotBlank() }?.let { dateTime ->
            return runCatching {
                DateTimeFormatter.ofPattern("EEE MMM d, h:mm a").format(ZonedDateTime.parse(dateTime))
            }.getOrDefault("")
        }
        optString("date").takeIf { it.isNotBlank() }?.let {
            return "all day"
        }
        return ""
    }

    private fun JSONObject.meetLink(): String {
        optString("hangoutLink").takeIf { it.isNotBlank() }?.let { return it }
        val entries = optJSONObject("conferenceData")?.optJSONArray("entryPoints") ?: JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (entry.optString("entryPointType").equals("video", ignoreCase = true)) {
                return entry.optString("uri")
            }
        }
        return ""
    }

    private fun parseCalendarMillis(value: String): Long {
        if (value.isBlank()) {
            return 0L
        }
        return runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { ZonedDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun mergeBusyIntervals(busySlots: JSONArray): List<BusyInterval> {
        val intervals = mutableListOf<BusyInterval>()
        for (i in 0 until busySlots.length()) {
            val slot = busySlots.optJSONObject(i) ?: continue
            val start = slot.optLong("start_millis")
            val end = slot.optLong("end_millis")
            if (end > start) {
                intervals += BusyInterval(start, end)
            }
        }
        if (intervals.isEmpty()) {
            return emptyList()
        }
        val sorted = intervals.sortedBy { it.startMillis }
        val merged = mutableListOf<BusyInterval>()
        sorted.forEach { interval ->
            val last = merged.lastOrNull()
            if (last == null || interval.startMillis > last.endMillis) {
                merged += interval
            } else {
                merged[merged.lastIndex] = last.copy(endMillis = maxOf(last.endMillis, interval.endMillis))
            }
        }
        return merged
    }

    private fun slotJson(startMillis: Long, endMillis: Long): JSONObject {
        val zone = ZoneId.systemDefault()
        return JSONObject()
            .put("start_millis", startMillis)
            .put("end_millis", endMillis)
            .put("start", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(startMillis).atZone(zone)))
            .put("end", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(endMillis).atZone(zone)))
    }

    private fun JSONObject.isBillyGhostEvent(): Boolean {
        val text = listOf(
            optString("summary"),
            optString("description"),
            optString("location"),
        ).joinToString(" ").lowercase()
        return GHOST_EVENT_MARKERS.any { marker -> text.contains(marker) }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun GoogleHttpResult.HttpError.toHumanCalendarError(): String {
        val reasonText = reason.lowercase()
        return when {
            responseCode == 403 && reasonText.contains("disabled") -> "Google Calendar API is disabled for Billy's OAuth project."
            responseCode == 403 && reasonText.contains("insufficient") -> "Billy needs Google Calendar permission again."
            responseCode == 401 -> "Google Calendar sign-in expired. Re-grant Google access in the companion."
            responseCode == 404 -> "Google Calendar or event was not found."
            responseCode in 500..599 -> "Google Calendar is temporarily unavailable."
            else -> "Google Calendar returned HTTP $responseCode: ${reason.take(120)}"
        }
    }

    private fun GoogleHttpResult.Failed.toHumanCalendarError(): String {
        val reasonText = reason.lowercase()
        return when {
            reasonText.contains("unable to resolve host") || reasonText.contains("googleapis") -> "Billy could not reach Google Calendar. Check phone internet and try again."
            reasonText.contains("timeout") || reasonText.contains("timed out") -> "Google Calendar timed out. Try again."
            else -> "Google Calendar request failed: ${reason.take(120)}"
        }
    }

    private companion object {
        private const val API_BASE = "https://www.googleapis.com/calendar/v3"
        private const val GHOST_CLEANUP_WINDOW_MS = 366L * 24L * 60L * 60L * 1000L
        private const val MAX_FREEBUSY_CALENDARS = 50
        private val GHOST_EVENT_MARKERS = listOf(
            "billy companion android calendar fallback",
        )
    }
}

private data class BusyInterval(
    val startMillis: Long,
    val endMillis: Long,
)

private data class CalendarListEntry(
    val id: String,
    val summary: String,
    val accessRole: String,
    val primary: Boolean,
    val selected: Boolean,
    val hidden: Boolean,
) {
    fun canWriteEvents(): Boolean {
        return accessRole.equals("owner", ignoreCase = true) ||
            accessRole.equals("writer", ignoreCase = true)
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("summary", summary)
            .put("access_role", accessRole)
            .put("primary", primary)
            .put("selected", selected)
            .put("hidden", hidden)
    }
}

private sealed interface CalendarListLookup {
    data class Success(val calendars: List<CalendarListEntry>) : CalendarListLookup
    data class Failed(val reason: String) : CalendarListLookup
}

private sealed interface CalendarEventsLookup {
    data class Success(val events: List<JSONObject>) : CalendarEventsLookup
    data class Failed(val reason: String) : CalendarEventsLookup
}

sealed interface GoogleCalendarResult {
    val summary: String

    data class Success(
        override val summary: String,
        val payload: JSONObject,
    ) : GoogleCalendarResult

    data class NeedsScope(val scopes: List<String>) : GoogleCalendarResult {
        override val summary: String = "Grant Google Calendar access in the companion app."
    }

    data class Rejected(val reason: String) : GoogleCalendarResult {
        override val summary: String = reason
    }

    data class Failed(val reason: String) : GoogleCalendarResult {
        override val summary: String = reason
    }
}

sealed interface GoogleCalendarGhostDeleteResult {
    val summary: String

    data class Deleted(
        val deletedCount: Int,
        val matchedCount: Int,
        val checkedCalendarCount: Int,
        val failures: List<String>,
    ) : GoogleCalendarGhostDeleteResult {
        override val summary: String = buildString {
            append(
                when (deletedCount) {
                    0 -> "No Google Calendar Billy/Bobby ghosts removed"
                    1 -> "Removed 1 Google Calendar Billy/Bobby ghost"
                    else -> "Removed $deletedCount Google Calendar Billy/Bobby ghosts"
                },
            )
            append(" across $checkedCalendarCount calendars.")
            if (matchedCount > deletedCount) {
                append(" Matched $matchedCount.")
            }
            if (failures.isNotEmpty()) {
                append(" ${failures.size} cleanup errors.")
            }
        }
    }

    data class NeedsScope(val scopes: List<String>) : GoogleCalendarGhostDeleteResult {
        override val summary: String = "Grant Google Calendar access to remove Google ghosts."
    }

    data class Failed(val reason: String) : GoogleCalendarGhostDeleteResult {
        override val summary: String = "Google Calendar ghost cleanup failed: $reason"
    }
}
