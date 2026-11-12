package com.tombo.billyassistant.companion.diagnostics

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Turns a failed or slow turn into a "Problems" notification. Tapping it opens a
 * prefilled GitHub issue holding the privacy-safe turn report; the user reads and
 * edits it on GitHub before submitting anything.
 */
object ProblemReporter {
    private const val CHANNEL_ID = "problems"
    private const val NOTIFICATION_ID = 7001
    private const val SLOW_MS = 20_000L
    private const val MAX_URL_CHARS = 6000
    private const val ISSUE_URL = "https://github.com/TomBolger/Billy/issues/new"

    /** True when a problem notification would actually be shown. */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
        }.getOrDefault(false)
    }

    /** Appended to messages shown on the watch when a report notification will follow. */
    fun hint(context: Context): String = if (canPost(context)) REPORT_HINT else ""

    /** Fits [reason] plus the report hint into [maxChars] without cutting the hint. */
    fun withHint(context: Context, reason: String, maxChars: Int): String {
        val hint = hint(context)
        val room = maxChars - hint.length
        val body = if (reason.length <= room) reason else reason.take((room - 1).coerceAtLeast(0)).trimEnd() + "…"
        return body + hint
    }

    /** Posts the notification when the turn failed or was slow. Silently does nothing without permission. */
    fun maybeReport(context: Context, report: DiagnosticsLog.Report) {
        val slow = report.outcome == "ok" && report.totalMs > SLOW_MS
        val failed = report.outcome != "ok" && report.outcome != "superseded"
        if (!slow && !failed) return
        if (!canPost(context)) return
        runCatching {
            val app = context.applicationContext
            val seconds = (report.totalMs + 500) / 1000
            val title = if (failed) "Billy had a problem" else "Billy was slow ($seconds s)"
            val text = if (failed) {
                report.error ?: "An answer failed."
            } else {
                "That answer took $seconds seconds. Tap to report it; the timings are filled in for you."
            }
            val issueTitle = if (failed) "Problem: ${(report.error ?: "answer failed").take(80)}" else "Slow answer ($seconds s)"
            val url = buildIssueUrl(issueTitle, report.json)

            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Tells you when Billy fails or is slow, with a one-tap bug report."
                },
            )
            val open = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                app,
                NOTIFICATION_ID,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildIssueUrl(title: String, report: JSONObject): String {
        val header = "What did you ask Billy and what went wrong? (Please don't paste private details.)\n\n\n" +
            "---\n" +
            "Billy Companion ${report.optString("app")} | Android ${report.optString("android")} | ${report.optString("phone")}\n\n" +
            "Turn report (contains no prompts, answers, names or emails):\n"
        val variants = listOf(report.toString(), DiagnosticsLog.compact(report).toString())
        for (variant in variants) {
            val url = makeUrl(title, header, variant)
            if (url.length <= MAX_URL_CHARS) return url
        }
        var text = variants.last()
        while (true) {
            text = text.take(text.length * 8 / 10)
            val url = makeUrl(title, header, "$text … (truncated)")
            if (url.length <= MAX_URL_CHARS || text.length < 200) return url
        }
    }

    private fun makeUrl(title: String, header: String, reportText: String): String {
        val body = header + "```json\n" + reportText + "\n```\n"
        return "$ISSUE_URL?title=${encode(title)}&body=${encode(body)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private const val REPORT_HINT = " Report it from the Billy Companion notification."
}
