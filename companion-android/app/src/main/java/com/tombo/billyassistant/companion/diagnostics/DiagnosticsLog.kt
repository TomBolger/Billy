package com.tombo.billyassistant.companion.diagnostics

import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Privacy-safe "turn report" store for finding out why answers are slow or fail.
 *
 * One JSON line per watch request (the last [MAX_TURNS] are kept in filesDir):
 * timings of every Gemini HTTP call, tool names and durations, device state
 * (network, Doze, battery saver, screen, battery exemption, standby bucket),
 * error messages, and the app/Android/phone versions.
 *
 * It NEVER stores the user's prompt, Billy's reply, tool arguments or tool
 * results. Callers must only pass error text that cannot contain personal content.
 */
object DiagnosticsLog {
    private const val MAX_TURNS = 30
    private const val MAX_CALLS = 20
    private const val MAX_TOOLS = 40
    private const val MAX_EVENTS = 30
    private const val MAX_TEXT = 200

    @Volatile private var appContext: Context? = null
    private val lock = Any()
    private var turn: Turn? = null
    private val nextId = java.util.concurrent.atomic.AtomicLong(0)

    private class Turn(val id: Long, val startedWall: Long, val startedElapsed: Long, val start: JSONObject) {
        val calls = JSONArray()
        val tools = JSONArray()
        val events = JSONArray()
        var problem: String? = null
    }

    /** The finished turn: its JSON line and how long it took. */
    class Report(val json: JSONObject, val totalMs: Long, val outcome: String, val error: String?)

    fun file(context: Context) = File(context.applicationContext.filesDir, "billy-turn-reports.jsonl")

    // ---- recording ------------------------------------------------------------

    /** Starts a turn and returns its id; pass the id to [endTurn]. A newer turn replaces an older unfinished one. */
    fun beginTurn(context: Context): Long {
        val app = context.applicationContext
        appContext = app
        val start = deviceState()
        val id = nextId.incrementAndGet()
        synchronized(lock) {
            turn = Turn(id, System.currentTimeMillis(), SystemClock.elapsedRealtime(), start)
        }
        return id
    }

    /** One Gemini HTTP call. Ignored outside a turn (for example the key test). */
    fun addCall(call: JSONObject) {
        synchronized(lock) {
            val t = turn ?: return
            if (t.calls.length() < MAX_CALLS) t.calls.put(call)
        }
    }

    fun addTool(name: String, ms: Long, status: String) {
        synchronized(lock) {
            val t = turn ?: return
            if (t.tools.length() < MAX_TOOLS) {
                t.tools.put(JSONObject().put("name", name.take(60)).put("ms", ms).put("status", status.take(30)))
            }
        }
    }

    /** A retry, a recovered hiccup, or any other noteworthy thing. Keep it free of personal content. */
    fun event(text: String) {
        synchronized(lock) {
            val t = turn ?: return
            if (t.events.length() < MAX_EVENTS) t.events.put(text.take(MAX_TEXT))
        }
    }

    /**
     * Like [event], but also marks the turn as having had a problem even if Billy
     * still produced an answer, so the user gets a "Billy had a problem" notification.
     */
    fun problem(text: String) {
        synchronized(lock) {
            val t = turn ?: return
            if (t.events.length() < MAX_EVENTS) t.events.put(text.take(MAX_TEXT))
            if (t.problem == null) t.problem = text.take(MAX_TEXT)
        }
    }

    /**
     * Finishes turn [id], appends it to the file and returns it. Null if that turn is
     * no longer the open one (a newer request replaced it), so it can't close the newer turn.
     */
    fun endTurn(id: Long, outcome: String, error: String?): Report? {
        val end = deviceState()
        val finished: Turn = synchronized(lock) {
            val open = turn
            if (open == null || open.id != id) {
                null
            } else {
                turn = null
                open
            }
        } ?: return null
        val totalMs = SystemClock.elapsedRealtime() - finished.startedElapsed
        val finalError = error?.take(MAX_TEXT) ?: finished.problem
        val finalOutcome = if (outcome == "ok" && finished.problem != null) "partial" else outcome
        val json = JSONObject()
            .put("at", isoTime(finished.startedWall))
            .put("app", versionName())
            .put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("phone", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("outcome", finalOutcome)
            .put("totalMs", totalMs)
            .put("start", finished.start)
            .put("end", end)
            .put("calls", finished.calls)
            .put("tools", finished.tools)
            .put("events", finished.events)
        if (finalError != null) json.put("error", finalError)
        append(json)
        return Report(json, totalMs, finalOutcome, finalError)
    }

    // ---- device state -----------------------------------------------------------

    /** Network, Doze, battery saver, screen, battery exemption and standby bucket right now. */
    fun deviceState(): JSONObject {
        val state = JSONObject()
        val ctx = appContext ?: return state
        runCatching { state.put("net", networkType(ctx)) }
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            state.put("doze", pm.isDeviceIdleMode)
            state.put("saver", pm.isPowerSaveMode)
            state.put("screenOn", pm.isInteractive)
            state.put("batteryExempt", pm.isIgnoringBatteryOptimizations(ctx.packageName))
        }
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching {
                val usage = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                state.put("standby", standbyName(usage.appStandbyBucket))
            }
        }
        return state
    }

    /** "wifi", "cellular", "ethernet", "other", "none" (no internet at all) or "unknown"; "-unvalidated" means Android doubts it works. */
    fun networkType(ctx: Context): String = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return@runCatching "none"
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching "none"
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@runCatching "none"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) kind else "$kind-unvalidated"
    }.getOrDefault("unknown")

    /** True when Android says the phone has no usable network right now. */
    fun isOffline(): Boolean {
        val ctx = appContext ?: return false
        return networkType(ctx) == "none"
    }

    private fun standbyName(bucket: Int): String = when (bucket) {
        10 -> "active"
        20 -> "working_set"
        30 -> "frequent"
        40 -> "rare"
        45 -> "restricted"
        50 -> "never"
        else -> "bucket_$bucket"
    }

    // ---- reading ----------------------------------------------------------------

    /** The last report line, or null. */
    fun lastReportText(context: Context): String? = runCatching {
        file(context).readLines().lastOrNull { it.isNotBlank() }
    }.getOrNull()

    /** A smaller copy of a report (no per-call device state) for pasting into a GitHub issue URL. */
    fun compact(report: JSONObject): JSONObject {
        val copy = JSONObject(report.toString())
        val calls = copy.optJSONArray("calls")
        if (calls != null) {
            for (i in 0 until calls.length()) {
                calls.optJSONObject(i)?.apply {
                    remove("start")
                    remove("end")
                }
            }
        }
        return copy
    }

    fun versionName(): String {
        val ctx = appContext ?: return "unknown"
        return runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "unknown"
    }

    // ---- storage ----------------------------------------------------------------

    private fun append(json: JSONObject) {
        val ctx = appContext ?: return
        synchronized(lock) {
            runCatching {
                val f = file(ctx)
                val lines = if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
                val kept = (lines + json.toString()).takeLast(MAX_TURNS)
                f.writeText(kept.joinToString("\n") + "\n")
            }
        }
    }

    private fun isoTime(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(millis))
    }
}
