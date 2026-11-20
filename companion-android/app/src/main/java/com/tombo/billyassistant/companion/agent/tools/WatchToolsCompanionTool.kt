package com.tombo.billyassistant.companion.agent.tools

import org.json.JSONObject

/**
 * Alarms, timers, reminders, Billy settings, and the big-number card.
 *
 * These live on the watch / Pebble phone JS, so the companion runs them through
 * the watch relay. The descriptions mirror app/src/pkjs/agent/watch_tools.js and
 * ui_tools.js; keep the two in sync so both runtimes behave the same.
 */
class WatchToolsCompanionTool(
    private val relay: (name: String, args: JSONObject) -> JSONObject,
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        declaration(
            "set_alarm",
            "Set a watch alarm that rings at a clock time (e.g. \"wake me at 7\", \"alarm for 6:30 tomorrow\"). Use set_timer instead for durations (\"in 10 minutes\", \"for 20 minutes\").",
            listOf("time"),
            mapOf(
                "time" to stringSchema("When the alarm rings. Must be in the future. $ISO_TIME"),
                "name" to stringSchema("Optional label, only if the user gave one. Title Case."),
            ),
        ),
        declaration("get_alarms", "List the alarms currently set on the watch."),
        declaration(
            "delete_alarm",
            "Delete one alarm by its exact time. If you do not already know the exact time from this conversation, call get_alarms first.",
            listOf("time"),
            mapOf("time" to stringSchema("Exact alarm time as returned by get_alarms. $ISO_TIME")),
        ),
        declaration(
            "set_timer",
            "Start a countdown timer on the watch for a duration (\"10 minute timer\", \"time the eggs for 7 minutes\"). Shows a live countdown card. Give the total duration using any combination of hours, minutes, and seconds.",
            emptyList(),
            mapOf(
                "hours" to intSchema("Hours part of the duration."),
                "minutes" to intSchema("Minutes part of the duration."),
                "seconds" to intSchema("Seconds part of the duration."),
                "name" to stringSchema("Optional label, only if the user gave one (e.g. \"Pasta\"). Title Case."),
            ),
        ),
        declaration("get_timers", "List running timers with time remaining."),
        declaration(
            "delete_timer",
            "Cancel one running timer. Pass its expiration time from get_timers. Call get_timers first unless you already know it.",
            listOf("time"),
            mapOf("time" to stringSchema("Timer expiration time exactly as returned by get_timers (expirationTimeForDeletingAndWidgets).")),
        ),
        declaration(
            "set_reminder",
            "Create a reminder that notifies the user on the watch at a time and appears on the Pebble timeline. Provide exactly one of time or delay_minutes. If the user gives a clock time without a day, use the next occurrence of that time. Use this for \"remind me\" requests; use Google Tasks or Calendar only when the user names them.",
            listOf("what"),
            mapOf(
                "what" to stringSchema("What to remind the user about, phrased as the task (e.g. \"Call mom\")."),
                "time" to stringSchema("When to remind. $ISO_TIME"),
                "delay_minutes" to intSchema("Minutes from now, for relative requests like \"in 20 minutes\"."),
            ),
        ),
        declaration("get_reminders", "List the active watch reminders with their ids and times."),
        declaration(
            "delete_reminder",
            "Delete a watch reminder by id. Call get_reminders first to find the id.",
            listOf("id"),
            mapOf("id" to stringSchema("Reminder id from get_reminders.")),
        ),
        declaration(
            "update_settings",
            "Change Billy's own settings: units, response language, alarm/timer vibration, quick-launch behaviour, or whether dictated prompts need confirmation. Only include the settings being changed.",
            emptyList(),
            mapOf(
                "unitSystem" to enumStringSchema("Measurement units.", listOf("imperial", "metric", "uk hybrid", "both", "auto")),
                "responseLanguage" to stringSchema("Language code such as 'en_US', 'de_DE', 'fr_FR', or 'auto'."),
                "alarmVibrationPattern" to enumStringSchema("Alarm vibration pattern.", VIBES),
                "timerVibrationPattern" to enumStringSchema("Timer vibration pattern.", VIBES),
                "quickLaunchBehaviour" to enumStringSchema(
                    "What quick launch does.",
                    listOf("start conversation and time out", "start conversation and stay open", "open home screen"),
                ),
                "confirmPrompts" to booleanSchema("True to confirm dictated prompts before sending."),
            ),
        ),
        declaration(
            "show_number",
            "Show a large number card on the watch. Use for any answer that is essentially one number or amount: calculations, unit or currency conversions, \"how many days until...\", scores, prices, distances, percentages. Call it before your short text reply.",
            listOf("value"),
            mapOf(
                "value" to stringSchema("The number exactly as it should be displayed, with separators or currency symbol (e.g. \"\$1,234.50\", \"42\", \"3.7 km\")."),
                "label" to stringSchema("Short caption under the number (e.g. \"EUR to USD\", \"days until Christmas\")."),
            ),
        ),
    )

    private val names = declarations.map { it.getString("name") }.toSet()

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        if (name !in names) {
            return null
        }
        return CompanionToolExecution(relay(name, args))
    }

    private fun declaration(
        name: String,
        description: String,
        required: List<String> = emptyList(),
        properties: Map<String, JSONObject> = emptyMap(),
    ): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", objectSchema(required, properties))
    }

    private fun intSchema(description: String): JSONObject {
        return JSONObject().put("type", "integer").put("description", description)
    }

    companion object {
        private const val ISO_TIME = "Local ISO 8601 time with the user's UTC offset, e.g. 2026-07-12T07:00:00-07:00."
        private val VIBES = listOf("Reveille", "Mario", "Nudge Nudge", "Jackhammer", "Standard")

        /** Tools that change state; identical repeats within one turn are not re-run. */
        val MUTATING = setOf(
            "set_alarm", "delete_alarm", "set_timer", "delete_timer", "set_reminder",
            "delete_reminder", "update_settings",
        )
    }
}
