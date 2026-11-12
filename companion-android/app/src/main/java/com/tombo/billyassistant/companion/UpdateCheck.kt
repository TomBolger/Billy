package com.tombo.billyassistant.companion

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
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks at Billy's latest GitHub release at most twice a day and, when it's
 * newer than this app, shows an "Update Billy Companion" notification (once
 * per release) and a card in the app with the download link.
 */
object UpdateCheck {
    data class Release(val version: String, val apkUrl: String, val pageUrl: String)

    private const val LATEST = "https://api.github.com/repos/TomBolger/Billy/releases/latest"
    private const val PREFS = "billy_updates"
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 4711

    @Volatile private var running = false

    fun installedVersionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(1L)

    private fun installedVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** A newer release than this app, from the last check, or null. */
    fun available(context: Context): Release? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString("version", null) ?: return null
        if (!isNewer(version, installedVersionName(context))) return null
        return Release(version, prefs.getString("apk", null).orEmpty(), prefs.getString("page", null).orEmpty())
    }

    /** Checks GitHub if it's been a while; never blocks the caller. */
    fun checkInBackground(context: Context, force: Boolean = false, then: (() -> Unit)? = null) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - prefs.getLong("checked", 0) < CHECK_EVERY_MS) return
        if (running) return
        running = true
        Thread {
            try {
                val release = fetchLatest() ?: return@Thread
                prefs.edit()
                    .putLong("checked", System.currentTimeMillis())
                    .putString("version", release.version)
                    .putString("apk", release.apkUrl)
                    .putString("page", release.pageUrl)
                    .apply()
                if (isNewer(release.version, installedVersionName(app)) && prefs.getString("notified", null) != release.version) {
                    notify(app, release)
                    prefs.edit().putString("notified", release.version).apply()
                }
                then?.invoke()
            } catch (_: Exception) {
                // No network or GitHub hiccup: try again next time.
            } finally {
                running = false
            }
        }.start()
    }

    private fun fetchLatest(): Release? {
        val connection = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "BillyCompanion")
        }
        if (connection.responseCode != 200) return null
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val version = json.optString("tag_name").removePrefix("v").takeIf { it.isNotBlank() } ?: return null
        val assets = json.optJSONArray("assets")
        var apk = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").startsWith("BillyCompanion") && asset.optString("name").endsWith(".apk")) {
                    apk = asset.optString("browser_download_url")
                }
            }
        }
        return Release(version, apk, json.optString("html_url"))
    }

    /** "0.8" > "0.7.1" > "0.7" */
    fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(v: String) = v.split('.', '-').mapNotNull { it.toIntOrNull() }
        val a = parts(candidate)
        val b = parts(installed)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun notify(context: Context, release: Release) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a new Billy Companion is out."
            },
        )
        val target = release.apkUrl.ifBlank { release.pageUrl }
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = "Version ${release.version} is out. Tap to download it, then open the file to install. Your setup is kept."
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Update Billy Companion")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                // Phone-only: watch notification apps (Pebble, Notification Sync) skip these,
                // so they never pop up over Billy on the watch.
                .setLocalOnly(true)
                .build(),
        )
    }
}
