package com.tombo.billyassistant.companion.agent.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pebble plugins (preview): the phone's calendar, music, notifications,
 * find-my-phone, watch apps and settings, and third-party plugins such as
 * Todoist or Hue. PebbleKit Android can't use plugins yet, so these run in the
 * Pebble phone JS through the watch relay (app/src/pkjs/agent/plugin_tools.js),
 * which keeps the two runtimes identical.
 *
 * Offered only once the phone JS has reported that plugins are on; the list of
 * what's installed is cached and refreshed after turns (see [PluginCatalog]).
 */
class PluginsCompanionTool(
    private val context: Context,
    private val relay: (name: String, args: JSONObject) -> JSONObject,
) : CompanionTool {
    override val declarations: List<JSONObject>
        get() {
            val catalog = PluginCatalog.load(context) ?: return emptyList()
            if (!catalog.available) return emptyList()
            return listOf(
                JSONObject()
                    .put("name", READ)
                    .put(
                        "description",
                        "Read live data from the phone and watch through Pebble plugins: the phone's own calendar (calendar/event: next 10 events " +
                            "within a week), phone battery and connectivity (phone/phone_state), what's playing (music/track), apps and watchfaces on " +
                            "the watch (watch/app), the watch's settings, and third-party plugins. Prefer your own tools when they do the same job " +
                            "(Google Calendar, notifications, phone status). Available now:\n" + catalog.sources.joinToString("\n").ifBlank { "(none)" },
                    )
                    .put(
                        "parameters",
                        objectSchema(
                            required = listOf("category", "item"),
                            properties = mapOf(
                                "category" to stringSchema("e.g. watch"),
                                "item" to stringSchema("e.g. app"),
                                "instance" to stringSchema("Optional instance id from an earlier read."),
                            ),
                        ),
                    ),
                JSONObject()
                    .put("name", ACT)
                    .put(
                        "description",
                        "Do something through a Pebble plugin: open an app or watchface on the watch (Watch State > launch_app with the app's id " +
                            "from watch/app), change a watch setting, ring the phone, play/pause/skip music, or a third-party plugin's actions " +
                            "(add a task, switch a light...). Args marked * are required. For actions marked [confirm first], or anything that sends " +
                            "a message, deletes or spends money, ask the user with ask_clarifying_question first, then call again with confirmed: true. " +
                            "Available now:\n" + catalog.actions.joinToString("\n").ifBlank { "(none)" },
                    )
                    .put(
                        "parameters",
                        objectSchema(
                            required = listOf("plugin", "action"),
                            properties = mapOf(
                                "plugin" to stringSchema("The plugin name as listed (e.g. \"Watch State\") or its id."),
                                "action" to stringSchema("The action name, e.g. launch_app."),
                                "args_json" to stringSchema("The action's arguments as a JSON object, e.g. {\"uuid\": \"...\"}. Omit when it takes none."),
                                "confirmed" to booleanSchema("True only after the user said yes."),
                            ),
                        ),
                    ),
            )
        }

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        if (name != READ && name != ACT) return null
        return CompanionToolExecution(relay(name, args))
    }

    companion object {
        const val READ = "read_phone_data"
        const val ACT = "use_phone_plugin"
        const val LIST = "list_phone_plugins"
    }
}

/** What the phone JS last said about installed plugins. */
object PluginCatalog {
    data class Catalog(val available: Boolean, val sources: List<String>, val actions: List<String>, val at: Long)

    private const val PREFS = "billy_plugins"
    private const val KEY = "catalog"
    private const val STALE_MS = 30 * 60 * 1000L

    fun load(context: Context): Catalog? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            Catalog(json.optBoolean("available"), json.optJSONArray("sources").strings(), json.optJSONArray("actions").strings(), json.optLong("at"))
        }.getOrNull()
    }

    fun isStale(context: Context): Boolean {
        val catalog = load(context) ?: return true
        return System.currentTimeMillis() - catalog.at > STALE_MS
    }

    /** Asks the phone JS (through the watch) what's installed. Call off the main thread. */
    fun refresh(context: Context, relay: (name: String, args: JSONObject) -> JSONObject) {
        val result = runCatching { relay(PluginsCompanionTool.LIST, JSONObject()) }.getOrNull() ?: return
        if (result.optString("status") != "ok") return
        val json = JSONObject()
            .put("available", result.optBoolean("available"))
            .put("sources", result.optJSONArray("sources") ?: JSONArray())
            .put("actions", result.optJSONArray("actions") ?: JSONArray())
            .put("at", System.currentTimeMillis())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}
