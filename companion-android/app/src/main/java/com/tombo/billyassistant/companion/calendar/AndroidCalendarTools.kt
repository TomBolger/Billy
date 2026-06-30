package com.tombo.billyassistant.companion.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.TimeZone

class AndroidCalendarTools(private val context: Context) {
    fun listEvents(startMillis: Long, endMillis: Long): CalendarEventsResult {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            return CalendarEventsResult.NotAuthorized(
                missingPermissions = listOf(Manifest.permission.READ_CALENDAR),
            )
        }
        if (endMillis <= startMillis) {
            return CalendarEventsResult.Rejected("End time must be after start time.")
        }

        return try {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { builder ->
                ContentUris.appendId(builder, startMillis)
                ContentUris.appendId(builder, endMillis)
            }.build()
            val events = mutableListOf<CalendarEventSummary>()
            context.contentResolver.query(
                uri,
                EVENT_PROJECTION,
                "${CalendarContract.Instances.VISIBLE} = ?",
                arrayOf("1"),
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    events += CalendarEventSummary(
                        eventId = cursor.getLong(EVENT_EVENT_ID),
                        title = cursor.getStringOrBlank(EVENT_TITLE),
                        startMillis = cursor.getLong(EVENT_BEGIN),
                        endMillis = cursor.getLong(EVENT_END),
                        calendarId = cursor.getLong(EVENT_CALENDAR_ID),
                        calendarName = cursor.getStringOrNull(EVENT_CALENDAR_NAME),
                        description = cursor.getStringOrNull(EVENT_DESCRIPTION),
                        location = cursor.getStringOrNull(EVENT_LOCATION),
                        allDay = cursor.getInt(EVENT_ALL_DAY) == 1,
                    )
                }
            }

            CalendarEventsResult.Success(
                events = events,
                summary = when (events.size) {
                    0 -> "No calendar events found."
                    1 -> "Found 1 calendar event: ${events.first().toAgentSummary()}"
                    else -> "Found ${events.size} calendar events."
                },
            )
        } catch (e: SecurityException) {
            CalendarEventsResult.NotAuthorized(
                missingPermissions = listOf(Manifest.permission.READ_CALENDAR),
                reason = "Calendar read permission was denied by Android.",
            )
        } catch (e: Exception) {
            CalendarEventsResult.Failed("Calendar event lookup failed: ${e.shortMessage()}")
        }
    }

    fun createEvent(request: CreateCalendarEventRequest): CreateCalendarEventResult {
        return CreateCalendarEventResult.Rejected(
            "Android Calendar Provider creation is disabled. Billy must create events through Google Calendar API.",
        )
    }

    fun deleteBillyLocalGhostEvents(): DeleteCalendarEventsResult {
        val missingPermissions = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            missingPermissions += Manifest.permission.READ_CALENDAR
        }
        if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            missingPermissions += Manifest.permission.WRITE_CALENDAR
        }
        if (missingPermissions.isNotEmpty()) {
            return DeleteCalendarEventsResult.NotAuthorized(missingPermissions = missingPermissions)
        }

        return try {
            val matches = mutableListOf<DeletedCalendarEventSummary>()
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                DELETE_PROJECTION,
                GHOST_EVENT_SELECTION,
                GHOST_EVENT_SELECTION_ARGS,
                "${CalendarContract.Events.DTSTART} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    matches += DeletedCalendarEventSummary(
                        eventId = cursor.getLong(DELETE_EVENT_ID),
                        title = cursor.getStringOrBlank(DELETE_TITLE),
                        calendarId = cursor.getLong(DELETE_CALENDAR_ID),
                        calendarName = cursor.getStringOrNull(DELETE_CALENDAR_NAME),
                        startMillis = cursor.getLong(DELETE_DTSTART),
                    )
                }
            }

            var deleted = 0
            matches.forEach { event ->
                deleted += context.contentResolver.delete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId),
                    null,
                    null,
                )
            }

            DeleteCalendarEventsResult.Deleted(
                deletedCount = deleted,
                matchedCount = matches.size,
                events = matches,
                summary = when (deleted) {
                    0 -> "No Billy/Bobby local calendar ghosts were removed."
                    1 -> "Removed 1 Billy/Bobby local calendar ghost."
                    else -> "Removed $deleted Billy/Bobby local calendar ghosts."
                },
            )
        } catch (e: SecurityException) {
            DeleteCalendarEventsResult.NotAuthorized(
                missingPermissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                reason = "Calendar write permission was denied by Android.",
            )
        } catch (e: Exception) {
            DeleteCalendarEventsResult.Failed("Calendar ghost cleanup failed: ${e.shortMessage()}")
        }
    }

    fun listWritableCalendars(): WritableCalendarsResult {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            return WritableCalendarsResult.NotAuthorized(
                missingPermissions = listOf(Manifest.permission.READ_CALENDAR),
            )
        }

        return try {
            val calendars = queryWritableCalendars()
            WritableCalendarsResult.Success(
                calendars = calendars,
                summary = when (calendars.size) {
                    0 -> "No writable calendars found."
                    1 -> "Found 1 writable calendar: ${calendars.first().toAgentSummary()}"
                    else -> "Found ${calendars.size} writable calendars."
                },
            )
        } catch (e: SecurityException) {
            WritableCalendarsResult.NotAuthorized(
                missingPermissions = listOf(Manifest.permission.READ_CALENDAR),
                reason = "Calendar read permission was denied by Android.",
            )
        } catch (e: Exception) {
            WritableCalendarsResult.Failed("Writable calendar lookup failed: ${e.shortMessage()}")
        }
    }

    private fun chooseDefaultWritableCalendar(calendars: List<WritableCalendarSummary>): WritableCalendarSummary? {
        return calendars.sortedWith(
            compareByDescending<WritableCalendarSummary> { it.visible && it.syncEvents && it.isGoogleCalendar && it.isPrimary }
                .thenByDescending { it.visible && it.syncEvents && it.isGoogleCalendar }
                .thenByDescending { it.visible && it.syncEvents && it.isPrimary }
                .thenByDescending { it.visible && it.syncEvents }
                .thenByDescending { it.visible && it.isPrimary }
                .thenByDescending { it.visible }
                .thenByDescending { it.isPrimary }
                .thenBy { it.name.lowercase() },
        ).firstOrNull()
    }

    private fun queryWritableCalendars(): List<WritableCalendarSummary> {
        val calendars = mutableListOf<WritableCalendarSummary>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            CALENDAR_PROJECTION,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${CalendarContract.Calendars.VISIBLE} DESC, ${CalendarContract.Calendars.SYNC_EVENTS} DESC, ${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                calendars += WritableCalendarSummary(
                    id = cursor.getLong(CALENDAR_ID),
                    name = cursor.getStringOrBlank(CALENDAR_DISPLAY_NAME),
                    accountName = cursor.getStringOrNull(CALENDAR_ACCOUNT_NAME),
                    accountType = cursor.getStringOrNull(CALENDAR_ACCOUNT_TYPE),
                    ownerAccount = cursor.getStringOrNull(CALENDAR_OWNER_ACCOUNT),
                    accessLevel = cursor.getInt(CALENDAR_ACCESS_LEVEL),
                    visible = cursor.getInt(CALENDAR_VISIBLE) == 1,
                    syncEvents = cursor.getInt(CALENDAR_SYNC_EVENTS) == 1,
                    isPrimary = cursor.getInt(CALENDAR_IS_PRIMARY) == 1,
                )
            }
        }
        return calendars
    }

    private fun readEventExists(eventId: Long): Boolean {
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            EVENT_READBACK_PROJECTION,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { cursor ->
            return cursor.moveToFirst()
        }
        return false
    }

    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        val EVENT_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
        )
        const val EVENT_EVENT_ID = 0
        const val EVENT_TITLE = 1
        const val EVENT_BEGIN = 2
        const val EVENT_END = 3
        const val EVENT_CALENDAR_ID = 4
        const val EVENT_CALENDAR_NAME = 5
        const val EVENT_DESCRIPTION = 6
        const val EVENT_LOCATION = 7
        const val EVENT_ALL_DAY = 8

        val EVENT_READBACK_PROJECTION = arrayOf(
            CalendarContract.Events._ID,
        )

        val DELETE_PROJECTION = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
            CalendarContract.Events.DTSTART,
        )
        const val DELETE_EVENT_ID = 0
        const val DELETE_TITLE = 1
        const val DELETE_CALENDAR_ID = 2
        const val DELETE_CALENDAR_NAME = 3
        const val DELETE_DTSTART = 4

        private const val LOCAL_FALLBACK_MARKER = "Created by Billy Companion Android calendar fallback."
        private const val GHOST_EVENT_SELECTION = "${CalendarContract.Events.DESCRIPTION} LIKE ?"
        private val GHOST_EVENT_SELECTION_ARGS = arrayOf(
            "%$LOCAL_FALLBACK_MARKER%",
        )

        val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        const val CALENDAR_ID = 0
        const val CALENDAR_DISPLAY_NAME = 1
        const val CALENDAR_ACCOUNT_NAME = 2
        const val CALENDAR_ACCOUNT_TYPE = 3
        const val CALENDAR_OWNER_ACCOUNT = 4
        const val CALENDAR_ACCESS_LEVEL = 5
        const val CALENDAR_VISIBLE = 6
        const val CALENDAR_SYNC_EVENTS = 7
        const val CALENDAR_IS_PRIMARY = 8
    }
}

data class CreateCalendarEventRequest(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val description: String? = null,
    val calendarId: Long? = null,
    val timeZoneId: String = TimeZone.getDefault().id,
)

data class CalendarEventSummary(
    val eventId: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val calendarId: Long,
    val calendarName: String?,
    val description: String?,
    val location: String?,
    val allDay: Boolean,
) {
    fun toAgentSummary(): String {
        val calendar = calendarName?.takeIf { it.isNotBlank() }?.let { " on $it" }.orEmpty()
        val locationText = location?.takeIf { it.isNotBlank() }?.let { " at $it" }.orEmpty()
        return "$title from $startMillis to $endMillis$calendar$locationText"
    }
}

data class WritableCalendarSummary(
    val id: Long,
    val name: String,
    val accountName: String?,
    val accountType: String?,
    val ownerAccount: String?,
    val accessLevel: Int,
    val visible: Boolean,
    val syncEvents: Boolean,
    val isPrimary: Boolean,
) {
    val isGoogleCalendar: Boolean
        get() = accountType.equals("com.google", ignoreCase = true)

    fun toAgentSummary(): String {
        val account = accountName?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        val primary = if (isPrimary) ", primary" else ""
        val sync = if (visible && syncEvents) ", synced" else ", not visibly synced"
        return "$name$account, id=$id$primary$sync"
    }

    fun accountLabel(): String {
        return accountName?.takeIf { it.isNotBlank() } ?: ownerAccount?.takeIf { it.isNotBlank() } ?: "unknown account"
    }
}

data class DeletedCalendarEventSummary(
    val eventId: Long,
    val title: String,
    val calendarId: Long,
    val calendarName: String?,
    val startMillis: Long,
)

sealed interface CalendarEventsResult {
    val summary: String

    data class Success(
        val events: List<CalendarEventSummary>,
        override val summary: String,
    ) : CalendarEventsResult

    data class NotAuthorized(
        val missingPermissions: List<String>,
        val reason: String = "Calendar read permission is required.",
    ) : CalendarEventsResult {
        override val summary: String = "Not authorized: ${missingPermissions.joinToString()}."
    }

    data class Rejected(val reason: String) : CalendarEventsResult {
        override val summary: String = reason
    }

    data class Failed(val reason: String) : CalendarEventsResult {
        override val summary: String = reason
    }
}

sealed interface CreateCalendarEventResult {
    val summary: String

    data class Created(
        val eventId: Long,
        val calendar: WritableCalendarSummary,
        val verified: Boolean,
        override val summary: String,
    ) : CreateCalendarEventResult

    data class NotAuthorized(
        val missingPermissions: List<String>,
        val reason: String = "Calendar permissions are required.",
    ) : CreateCalendarEventResult {
        override val summary: String = "Not authorized: ${missingPermissions.joinToString()}."
    }

    data class Rejected(val reason: String) : CreateCalendarEventResult {
        override val summary: String = reason
    }

    data class Failed(val reason: String) : CreateCalendarEventResult {
        override val summary: String = reason
    }
}

sealed interface WritableCalendarsResult {
    val summary: String

    data class Success(
        val calendars: List<WritableCalendarSummary>,
        override val summary: String,
    ) : WritableCalendarsResult

    data class NotAuthorized(
        val missingPermissions: List<String>,
        val reason: String = "Calendar read permission is required.",
    ) : WritableCalendarsResult {
        override val summary: String = "Not authorized: ${missingPermissions.joinToString()}."
    }

    data class Failed(val reason: String) : WritableCalendarsResult {
        override val summary: String = reason
    }
}

sealed interface DeleteCalendarEventsResult {
    val summary: String

    data class Deleted(
        val deletedCount: Int,
        val matchedCount: Int,
        val events: List<DeletedCalendarEventSummary>,
        override val summary: String,
    ) : DeleteCalendarEventsResult

    data class NotAuthorized(
        val missingPermissions: List<String>,
        val reason: String = "Calendar read/write permissions are required.",
    ) : DeleteCalendarEventsResult {
        override val summary: String = "Not authorized: ${missingPermissions.joinToString()}."
    }

    data class Failed(val reason: String) : DeleteCalendarEventsResult {
        override val summary: String = reason
    }
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? {
    return if (isNull(index)) null else getString(index)
}

private fun android.database.Cursor.getStringOrBlank(index: Int): String {
    return getStringOrNull(index).orEmpty()
}

private fun Exception.shortMessage(): String {
    return message ?: javaClass.simpleName
}
