package com.tombo.billyassistant.companion.phone

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps a short in-memory list of recent notifications so Billy can read them
 * ("what did Sam text me?") and reply through the app's own reply button
 * (WhatsApp, Messages, Signal, ...). Nothing is written to disk.
 *
 * Also lets Billy see and control whatever media is playing.
 */
class BillyNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = true
        runCatching { activeNotifications?.forEach { remember(it) } }
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        remember(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        recent.removeAll { it.key == sbn.key && !it.isMessage }
    }

    private fun remember(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = messageText(extras)
        if (title.isBlank() && text.isBlank()) return
        val reply = notification.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val item = RecentNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            app = appName,
            title = title,
            text = text.take(500),
            postedAt = sbn.postTime,
            isMessage = notification.category == Notification.CATEGORY_MESSAGE || reply != null,
            replyAction = reply,
        )
        recent.removeAll { it.key == item.key }
        recent.add(0, item)
        while (recent.size > MAX_ITEMS) recent.removeAt(recent.size - 1)
    }

    private fun messageText(extras: Bundle): String {
        // Messaging-style notifications keep each message separately.
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!messages.isNullOrEmpty()) {
            return messages.takeLast(4).mapNotNull { (it as? Bundle)?.getCharSequence("text")?.toString() }.joinToString(" / ")
        }
        return (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
    }

    companion object {
        private const val MAX_ITEMS = 60
        val recent = CopyOnWriteArrayList<RecentNotification>()

        @Volatile
        var connected = false
            private set

        fun component(context: Context) = ComponentName(context, BillyNotificationListener::class.java)

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            return flat.contains(component(context).flattenToString()) || flat.contains(component(context).flattenToShortString())
        }

        /** Sends [message] through the notification's own reply button. */
        fun reply(context: Context, item: RecentNotification, message: String): Boolean {
            val action = item.replyAction ?: return false
            val inputs = action.remoteInputs ?: return false
            val intent = Intent()
            val results = Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, message) }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            return runCatching {
                action.actionIntent.send(context, 0, intent)
                true
            }.getOrDefault(false)
        }
    }
}

data class RecentNotification(
    val key: String,
    val packageName: String,
    val app: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val isMessage: Boolean,
    val replyAction: Notification.Action?,
)
