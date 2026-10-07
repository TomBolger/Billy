package com.tombo.billyassistant.companion.agent.tools

import com.tombo.billyassistant.companion.auth.GoogleAccessTokenProvider
import com.tombo.billyassistant.companion.auth.GoogleApiScopes
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Google Calendar: read, search, create, change, and delete events.
 *
 * Every event the model sees carries event_id + calendar_id, so follow-ups
 * ("move it to 4", "delete that") act on the exact event. Deletes always ask
 * on the watch first.
 */
class CalendarCompanionTool(tokenProvider: GoogleAccessTokenProvider) : CompanionTool {
    private val google = GoogleAccess(tokenProvider, "Google Calendar", GoogleApiScopes.calendar)

    override val declarations: List<JSONObject> = listOf(
        decl(
            "get_calendar_events",
            "Read the user's Google Calendar across all their calendars. Use for \"what's on my calendar\", \"when is my next meeting\", \"am I free Friday\", or to find an event by name before changing or deleting it. Returns event_id and calendar_id for each event.",
            emptyList(),
            mapOf(
                "start" to stringSchema("Window start. ${TimeArgs.ISO_HELP} Defaults to now."),
                "end" to stringSchema("Window end. Defaults to end of the start day, or 60 days ahead when searching by text."),
                "search" to stringSchema("Optional text to match in the title, description, location, or attendees (e.g. \"dentist\")."),
                "max_results" to integerSchema("Maximum events, default 10."),
            ),
        ),
        decl(
            "create_calendar_event",
            "Add an event to Google Calendar. If the user gives a start but no end, use a 1 hour duration (all-day events: pass dates). Pass calendar only if the user names one; otherwise the primary calendar is used.",
            listOf("title", "start"),
            mapOf(
                "title" to stringSchema("Event title."),
                "start" to stringSchema("Start. ${TimeArgs.ISO_HELP}"),
                "end" to stringSchema("End, same format as start."),
                "location" to stringSchema("Optional location."),
                "description" to stringSchema("Optional notes."),
                "calendar" to stringSchema("Optional calendar name the user mentioned (e.g. \"Work\", \"Family\")."),
                "attendee_emails" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                    .put("description", "Optional guest email addresses."),
                "add_meet_link" to booleanSchema("True when the user wants a Google Meet / video call link."),
                "reminder_minutes" to integerSchema("Optional popup reminder this many minutes before."),
            ),
        ),
        decl(
            "update_calendar_event",
            "Change an existing event: move it, rename it, change length, location, or notes. Get event_id and calendar_id from get_calendar_events first. Only pass fields that change.",
            listOf("event_id", "calendar_id"),
            mapOf(
                "event_id" to stringSchema("event_id from get_calendar_events."),
                "calendar_id" to stringSchema("calendar_id from get_calendar_events."),
                "title" to stringSchema("New title."),
                "start" to stringSchema("New start. ${TimeArgs.ISO_HELP}"),
                "end" to stringSchema("New end. If only start moves, omit this and the duration is kept."),
                "location" to stringSchema("New location."),
                "description" to stringSchema("New notes."),
            ),
        ),
        decl(
            "delete_calendar_event",
            "Delete (cancel/remove) a calendar event. Pass event_id + calendar_id from get_calendar_events; or pass search + date and Billy finds it. The watch asks the user to confirm before anything is deleted.",
            emptyList(),
            mapOf(
                "event_id" to stringSchema("event_id from get_calendar_events."),
                "calendar_id" to stringSchema("calendar_id from get_calendar_events."),
                "search" to stringSchema("Event title words, when no event_id is known."),
                "date" to stringSchema("Day to search, e.g. 2026-10-03. Optional."),
                "all_occurrences" to booleanSchema("For a repeating event: true deletes the whole series, false (default) only this occurrence."),
            ),
        ),
        decl(
            "find_free_time",
            "Find open time slots across the user's calendars, e.g. \"when am I free tomorrow afternoon for an hour\".",
            listOf("start", "end"),
            mapOf(
                "start" to stringSchema("Search window start. ${TimeArgs.ISO_HELP}"),
                "end" to stringSchema("Search window end."),
                "duration_minutes" to integerSchema("Length of slot needed, default 30."),
            ),
        ),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        val result = when (name) {
            "get_calendar_events" -> getEvents(args)
            "create_calendar_event" -> create(args)
            "update_calendar_event" -> update(args)
            "delete_calendar_event" -> return delete(args)
            "find_free_time" -> freeTime(args)
            else -> return null
        }
        return CompanionToolExecution(result)
    }

    // ---- read -------------------------------------------------------------

    private fun getEvents(args: JSONObject): JSONObject {
        val search = args.optString("search").trim()
        val start = TimeArgs.parse(args.optString("start"))?.time ?: ZonedDateTime.now(TimeArgs.zone())
        val end = TimeArgs.parse(args.optString("end"))?.time
            ?: if (search.isNotEmpty()) start.plusDays(60) else start.toLocalDate().plusDays(1).atStartOfDay(TimeArgs.zone())
        if (!end.isAfter(start)) return GoogleAccess.error("The end must be after the start.")
        val max = args.optInt("max_results", 10).coerceIn(1, 30)
        return google.run { token ->
            val events = fetchEvents(token, start, end, search, max)
            val list = JSONArray().also { array -> events.forEach { array.put(it.toJson()) } }
            val summary = when (events.size) {
                0 -> if (search.isEmpty()) "Nothing on the calendar in that window." else "No events matching \"$search\"."
                else -> events.take(6).joinToString("\n") { "- ${it.spokenLine()}" }
            }
            GoogleAccess.ok(summary).put("events", list).put("count", events.size)
        }
    }

    private fun fetchEvents(token: String, start: ZonedDateTime, end: ZonedDateTime, search: String, max: Int): List<Event> {
        val calendars = calendars(token).filter { it.visible }
        val events = mutableListOf<Event>()
        calendars.forEach { calendar ->
            val url = buildString {
                append("$API/calendars/${GoogleAccess.encode(calendar.id)}/events?singleEvents=true&orderBy=startTime")
                append("&maxResults=$max")
                append("&timeMin=${GoogleAccess.encode(start.toInstant().toString())}")
                append("&timeMax=${GoogleAccess.encode(end.toInstant().toString())}")
                if (search.isNotEmpty()) append("&q=${GoogleAccess.encode(search)}")
            }
            val items = runCatching { google.get(url, token).optJSONArray("items") }.getOrNull() ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("status") == "cancelled") continue
                Event.from(item, calendar)?.let { events += it }
            }
        }
        return events.sortedBy { it.start }.take(max)
    }

    // ---- create / update --------------------------------------------------

    private fun create(args: JSONObject): JSONObject {
        val title = args.optString("title").trim()
        val start = TimeArgs.parse(args.optString("start")) ?: return GoogleAccess.error("I need a start time, e.g. ${TimeArgs.ISO_HELP}")
        val end = TimeArgs.parse(args.optString("end"))
            ?: if (start.dateOnly) ParsedTime(start.time.plusDays(1), true) else ParsedTime(start.time.plusHours(1), false)
        if (title.isEmpty()) return GoogleAccess.error("I need an event title.")
        if (!end.time.isAfter(start.time)) return GoogleAccess.error("The end must be after the start.")
        return google.run { token ->
            val calendar = pickCalendar(token, args.optString("calendar"))
                ?: return@run GoogleAccess.error("I couldn't find a calendar I can add events to.")
            val body = JSONObject()
                .put("summary", title)
                .put("start", timeJson(start))
                .put("end", timeJson(end))
            args.optString("location").trim().takeIf { it.isNotEmpty() }?.let { body.put("location", it) }
            args.optString("description").trim().takeIf { it.isNotEmpty() }?.let { body.put("description", it) }
            args.optJSONArray("attendee_emails")?.let { emails ->
                val attendees = JSONArray()
                for (i in 0 until emails.length()) {
                    emails.optString(i).trim().takeIf { "@" in it }?.let { attendees.put(JSONObject().put("email", it)) }
                }
                if (attendees.length() > 0) body.put("attendees", attendees)
            }
            if (args.has("reminder_minutes")) {
                body.put(
                    "reminders",
                    JSONObject().put("useDefault", false).put(
                        "overrides",
                        JSONArray().put(JSONObject().put("method", "popup").put("minutes", args.optInt("reminder_minutes").coerceIn(0, 40320))),
                    ),
                )
            }
            val meet = args.optBoolean("add_meet_link", false)
            if (meet) {
                body.put(
                    "conferenceData",
                    JSONObject().put(
                        "createRequest",
                        JSONObject().put("requestId", UUID.randomUUID().toString())
                            .put("conferenceSolutionKey", JSONObject().put("type", "hangoutsMeet")),
                    ),
                )
            }
            val url = "$API/calendars/${GoogleAccess.encode(calendar.id)}/events" +
                (if (meet) "?conferenceDataVersion=1" else "") +
                (if (body.has("attendees")) (if (meet) "&" else "?") + "sendUpdates=all" else "")
            val created = Event.from(google.post(url, token, body), calendar)
                ?: return@run GoogleAccess.error("Google Calendar did not confirm the new event.")
            GoogleAccess.ok("Added \"${created.title}\" ${created.spokenWhen()} to ${calendar.name}.")
                .put("event", created.toJson())
        }
    }

    private fun update(args: JSONObject): JSONObject {
        val eventId = args.optString("event_id").trim()
        val calendarId = args.optString("calendar_id").trim()
        if (eventId.isEmpty() || calendarId.isEmpty()) {
            return GoogleAccess.error("I need event_id and calendar_id. Call get_calendar_events first.")
        }
        return google.run { token ->
            val url = "$API/calendars/${GoogleAccess.encode(calendarId)}/events/${GoogleAccess.encode(eventId)}"
            val current = google.get(url, token)
            val calendar = Calendar(calendarId, calendarId, "owner", primary = false, visible = true)
            val existing = Event.from(current, calendar) ?: return@run GoogleAccess.error("I couldn't read that event.")
            val patch = JSONObject()
            args.optString("title").trim().takeIf { it.isNotEmpty() }?.let { patch.put("summary", it) }
            args.optString("location").trim().takeIf { it.isNotEmpty() }?.let { patch.put("location", it) }
            args.optString("description").trim().takeIf { it.isNotEmpty() }?.let { patch.put("description", it) }
            val newStart = TimeArgs.parse(args.optString("start"))
            val newEnd = TimeArgs.parse(args.optString("end"))
            if (newStart != null || newEnd != null) {
                val duration = existing.end - existing.start
                val startTime = newStart ?: ParsedTime(Instant.ofEpochMilli(existing.start).atZone(TimeArgs.zone()), existing.allDay)
                val endTime = newEnd ?: ParsedTime(startTime.time.plusNanos(duration * 1_000_000), startTime.dateOnly)
                if (!endTime.time.isAfter(startTime.time)) return@run GoogleAccess.error("The end must be after the start.")
                patch.put("start", timeJson(startTime)).put("end", timeJson(endTime))
            }
            if (patch.length() == 0) return@run GoogleAccess.error("Tell me what to change about the event.")
            val updated = Event.from(google.patch(url, token, patch), calendar) ?: existing
            GoogleAccess.ok("Updated \"${updated.title}\": now ${updated.spokenWhen()}.").put("event", updated.toJson())
        }
    }

    // ---- delete -----------------------------------------------------------

    private fun delete(args: JSONObject): CompanionToolExecution {
        val allOccurrences = args.optBoolean("all_occurrences", false)
        var picker: ClarificationCard? = null
        val result = google.run { token ->
            val eventId = args.optString("event_id").trim()
            val calendarId = args.optString("calendar_id").trim()
            val search = args.optString("search").trim()
            val candidates: List<Event> = if (eventId.isNotEmpty() && calendarId.isNotEmpty()) {
                val raw = google.get("$API/calendars/${GoogleAccess.encode(calendarId)}/events/${GoogleAccess.encode(eventId)}", token)
                listOfNotNull(Event.from(raw, Calendar(calendarId, calendarId, "owner", primary = false, visible = true)))
            } else if (search.isNotEmpty()) {
                val day = TimeArgs.parse(args.optString("date"))?.time
                val from = day?.toLocalDate()?.atStartOfDay(TimeArgs.zone()) ?: ZonedDateTime.now(TimeArgs.zone()).minusDays(1)
                val to = day?.toLocalDate()?.plusDays(1)?.atStartOfDay(TimeArgs.zone()) ?: from.plusDays(90)
                fetchEvents(token, from, to, search, 6).filter { it.writable }
            } else {
                return@run GoogleAccess.error("Which event? Give event_id + calendar_id, or a search.")
            }
            when {
                candidates.isEmpty() -> GoogleAccess.error("I couldn't find a matching event I can delete.")
                candidates.size == 1 -> {
                    val event = candidates.first()
                    val series = allOccurrences && event.recurringId != null
                    picker = PendingActions.confirm(
                        "Delete ${if (series) "every \"${event.title}\"" else "\"${event.title}\" ${event.spokenWhen()}"}?",
                        "Delete",
                    ) { PendingOutcome(performDelete(event, series)) }
                    GoogleAccess.ok("Asking the user to confirm on the watch.")
                }
                else -> {
                    picker = PendingActions.offer(
                        "Delete which event?",
                        candidates.take(3).map { event ->
                            PendingActions.Choice("${event.title} ${event.spokenWhenShort()}") {
                                PendingOutcome(performDelete(event, allOccurrences && event.recurringId != null))
                            }
                        },
                        cancelLabel = null,
                    )
                    GoogleAccess.ok("Several events match; asking the user to pick one.")
                }
            }
        }
        return CompanionToolExecution(result, clarificationCard = picker)
    }

    private fun performDelete(event: Event, series: Boolean): String {
        val result = google.run { token ->
            val id = if (series) event.recurringId ?: event.id else event.id
            try {
                google.delete("$API/calendars/${GoogleAccess.encode(event.calendarId)}/events/${GoogleAccess.encode(id)}?sendUpdates=all", token)
            } catch (e: GoogleCallException) {
                if (e.message?.contains("already") != true) throw e
            }
            GoogleAccess.ok(if (series) "Deleted every \"${event.title}\"." else "Deleted \"${event.title}\" ${event.spokenWhen()}.")
        }
        return result.optString("summary")
    }

    // ---- free time ----------------------------------------------------------

    private fun freeTime(args: JSONObject): JSONObject {
        val start = TimeArgs.parse(args.optString("start"))?.time ?: return GoogleAccess.error("I need a start.")
        val end = TimeArgs.parse(args.optString("end"))?.time ?: return GoogleAccess.error("I need an end.")
        val minutes = args.optInt("duration_minutes", 30).coerceIn(5, 600)
        return google.run { token ->
            val calendars = calendars(token).filter { it.visible }
            val body = JSONObject()
                .put("timeMin", start.toInstant().toString())
                .put("timeMax", end.toInstant().toString())
                .put("items", JSONArray().also { a -> calendars.take(50).forEach { a.put(JSONObject().put("id", it.id)) } })
            val response = google.post("$API/freeBusy", token, body).optJSONObject("calendars") ?: JSONObject()
            val busy = mutableListOf<Pair<Long, Long>>()
            response.keys().forEach { key ->
                val slots = response.optJSONObject(key)?.optJSONArray("busy") ?: return@forEach
                for (i in 0 until slots.length()) {
                    val slot = slots.optJSONObject(i) ?: continue
                    val s = TimeArgs.parse(slot.optString("start"))?.millis ?: continue
                    val e = TimeArgs.parse(slot.optString("end"))?.millis ?: continue
                    busy += s to e
                }
            }
            busy.sortBy { it.first }
            val free = mutableListOf<Pair<Long, Long>>()
            var cursor = start.toInstant().toEpochMilli()
            val stop = end.toInstant().toEpochMilli()
            busy.forEach { (s, e) ->
                if (s - cursor >= minutes * 60_000L) free += cursor to s
                cursor = maxOf(cursor, e)
            }
            if (stop - cursor >= minutes * 60_000L) free += cursor to stop
            val lines = free.take(5).map { (s, e) ->
                val from = Instant.ofEpochMilli(s).atZone(TimeArgs.zone())
                val to = Instant.ofEpochMilli(e).atZone(TimeArgs.zone())
                "${TimeArgs.spoken(from)} to ${to.format(DateTimeFormatter.ofPattern("h:mm a"))}"
            }
            GoogleAccess.ok(if (lines.isEmpty()) "No free ${minutes}-minute slot in that window." else lines.joinToString("\n") { "- $it" })
                .put("free_slots", JSONArray(lines))
        }
    }

    // ---- helpers ----------------------------------------------------------

    private fun calendars(token: String): List<Calendar> {
        val items = google.get("$API/users/me/calendarList?maxResults=250", token).optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            Calendar(
                id = item.optString("id"),
                name = item.optString("summaryOverride").ifBlank { item.optString("summary") },
                accessRole = item.optString("accessRole"),
                primary = item.optBoolean("primary"),
                visible = item.optBoolean("selected", true) && !item.optBoolean("hidden"),
            )
        }.filter { it.id.isNotEmpty() }
    }

    private fun pickCalendar(token: String, hint: String): Calendar? {
        val writable = calendars(token).filter { it.writable }
        val wanted = hint.trim().lowercase()
        if (wanted.isNotEmpty()) {
            writable.firstOrNull { it.name.lowercase() == wanted }?.let { return it }
            writable.firstOrNull { it.name.lowercase().contains(wanted) || wanted.contains(it.name.lowercase()) }?.let { return it }
        }
        return writable.firstOrNull { it.primary } ?: writable.firstOrNull { it.visible } ?: writable.firstOrNull()
    }

    private fun timeJson(time: ParsedTime): JSONObject {
        return if (time.dateOnly) {
            JSONObject().put("date", time.time.toLocalDate().toString())
        } else {
            JSONObject()
                .put("dateTime", time.time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .put("timeZone", TimeArgs.zone().id)
        }
    }

    private fun decl(name: String, description: String, required: List<String>, properties: Map<String, JSONObject> = emptyMap()): JSONObject {
        return JSONObject().put("name", name).put("description", description).put("parameters", objectSchema(required, properties))
    }

    private data class Calendar(val id: String, val name: String, val accessRole: String, val primary: Boolean, val visible: Boolean) {
        val writable: Boolean get() = accessRole == "owner" || accessRole == "writer"
    }

    private data class Event(
        val id: String,
        val calendarId: String,
        val calendarName: String,
        val title: String,
        val start: Long,
        val end: Long,
        val allDay: Boolean,
        val location: String,
        val recurringId: String?,
        val meetLink: String,
        val writable: Boolean,
    ) {
        fun spokenWhen(): String = TimeArgs.spoken(Instant.ofEpochMilli(start).atZone(TimeArgs.zone()), allDay)

        fun spokenWhenShort(): String {
            val time = Instant.ofEpochMilli(start).atZone(TimeArgs.zone())
            return if (allDay) time.format(DateTimeFormatter.ofPattern("M/d")) else time.format(DateTimeFormatter.ofPattern("M/d h:mma"))
        }

        fun spokenLine(): String = buildString {
            append(spokenWhen())
            append(" ")
            append(title)
            if (location.isNotBlank()) append(" @ ").append(location.substringBefore(',').take(30))
        }

        fun toJson(): JSONObject = JSONObject()
            .put("event_id", id)
            .put("calendar_id", calendarId)
            .put("calendar", calendarName)
            .put("title", title)
            .put("start", Instant.ofEpochMilli(start).atZone(TimeArgs.zone()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("end", Instant.ofEpochMilli(end).atZone(TimeArgs.zone()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("all_day", allDay)
            .put("location", location)
            .put("repeating", recurringId != null)
            .put("meet_link", meetLink)

        companion object {
            fun from(json: JSONObject, calendar: Calendar): Event? {
                val id = json.optString("id").ifBlank { return null }
                val startJson = json.optJSONObject("start") ?: return null
                val endJson = json.optJSONObject("end") ?: startJson
                val allDay = startJson.optString("dateTime").isBlank()
                val start = TimeArgs.parse(startJson.optString("dateTime").ifBlank { startJson.optString("date") })?.millis ?: return null
                val end = TimeArgs.parse(endJson.optString("dateTime").ifBlank { endJson.optString("date") })?.millis ?: start
                val organizerSelf = json.optJSONObject("organizer")?.optBoolean("self", false) ?: true
                return Event(
                    id = id,
                    calendarId = calendar.id,
                    calendarName = calendar.name,
                    title = json.optString("summary").ifBlank { "(busy)" },
                    start = start,
                    end = end,
                    allDay = allDay,
                    location = json.optString("location"),
                    recurringId = json.optString("recurringEventId").takeIf { it.isNotBlank() },
                    meetLink = json.optString("hangoutLink"),
                    writable = calendar.writable || organizerSelf,
                )
            }
        }
    }

    private companion object {
        const val API = "https://www.googleapis.com/calendar/v3"
    }
}
