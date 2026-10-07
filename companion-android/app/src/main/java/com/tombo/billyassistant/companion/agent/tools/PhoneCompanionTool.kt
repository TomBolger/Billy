package com.tombo.billyassistant.companion.agent.tools

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.RingtoneManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.KeyEvent
import com.tombo.billyassistant.companion.phone.BillyNotificationListener
import com.tombo.billyassistant.companion.phone.LaunchActivity
import com.tombo.billyassistant.companion.phone.PhoneContacts
import com.tombo.billyassistant.companion.phone.RecentNotification
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Things Gemini can do on the phone itself: text, call, read and reply to
 * notifications, control music, flashlight, volume, battery, find my phone,
 * open apps, and start navigation. Anything that sends a message or places a
 * call is confirmed on the watch first.
 */
class PhoneCompanionTool(private val context: Context) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        decl(
            "send_text_message",
            "Send an SMS text message to a contact or number. The watch shows the message and asks the user to confirm before it is sent. For replying in WhatsApp, Signal, etc. use reply_to_notification instead.",
            listOf("to", "message"),
            mapOf(
                "to" to stringSchema("Contact name as the user said it, or a phone number."),
                "message" to stringSchema("The message text, written as the user wants it sent."),
            ),
        ),
        decl(
            "call_contact",
            "Place a phone call to a contact or number from the phone.",
            listOf("to"),
            mapOf("to" to stringSchema("Contact name or phone number.")),
        ),
        decl(
            "get_notifications",
            "Read the phone's recent notifications and messages from any app (texts, WhatsApp, email alerts, deliveries...). Use for \"what did Sam say\", \"any new messages\", \"read my notifications\".",
            emptyList(),
            mapOf(
                "from" to stringSchema("Optional: only notifications whose sender, title, or app matches this (e.g. \"Sam\", \"WhatsApp\")."),
                "messages_only" to booleanSchema("True to only include conversations (texts, chats)."),
                "max_results" to integerSchema("Default 8."),
            ),
        ),
        decl(
            "reply_to_notification",
            "Reply to a recent message notification using that app's own reply (WhatsApp, Messages, Signal, Telegram, ...). The watch asks the user to confirm first.",
            listOf("to", "message"),
            mapOf(
                "to" to stringSchema("Sender or conversation name from get_notifications."),
                "message" to stringSchema("Reply text."),
            ),
        ),
        decl(
            "control_media",
            "Control or check whatever is playing on the phone (music, podcasts, videos).",
            listOf("action"),
            mapOf(
                "action" to enumStringSchema(
                    "What to do.",
                    listOf("play", "pause", "toggle", "next", "previous", "now_playing"),
                ),
            ),
        ),
        decl(
            "play_music",
            "Start playing a song, artist, album, playlist, or podcast on the phone.",
            listOf("query"),
            mapOf(
                "query" to stringSchema("What to play, e.g. \"Taylor Swift\", \"lofi beats\"."),
                "app" to stringSchema("Optional app the user named, e.g. \"Spotify\", \"YouTube Music\"."),
            ),
        ),
        decl(
            "set_phone_volume",
            "Set the phone's volume.",
            listOf("level"),
            mapOf(
                "level" to integerSchema("0-100 percent."),
                "stream" to enumStringSchema("Which volume. Default media.", listOf("media", "ring", "alarm")),
            ),
        ),
        decl(
            "set_flashlight",
            "Turn the phone's flashlight on or off.",
            listOf("on"),
            mapOf("on" to booleanSchema("True for on.")),
        ),
        decl("get_phone_status", "Phone battery level, charging state, and ringer mode."),
        decl("find_my_phone", "Make the phone ring loudly for about 20 seconds so the user can find it, even if it is on silent."),
        decl(
            "open_app",
            "Open an app on the phone by name.",
            listOf("app"),
            mapOf("app" to stringSchema("App name, e.g. \"Spotify\", \"Camera\".")),
        ),
        decl(
            "open_google_photos_search",
            "Open the Google Photos app on the phone with a search, which covers the user's whole cloud library (people, places, things, dates). Results appear on the phone, not the watch; Google doesn't let other apps read the library. Use when find_photo can't find it or the user says Google Photos.",
            listOf("query"),
            mapOf("query" to stringSchema("Search as you'd type it in Google Photos, e.g. \"dog beach 2024\".")),
        ),
        decl(
            "start_navigation",
            "Start Google Maps turn-by-turn navigation on the phone. Pair with show_map for a map on the watch.",
            listOf("destination"),
            mapOf(
                "destination" to stringSchema("Address or place name."),
                "travel_mode" to enumStringSchema("Default drive.", listOf("drive", "walk", "bicycle", "transit")),
            ),
        ),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        return when (name) {
            "send_text_message" -> sendText(args)
            "call_contact" -> call(args)
            "get_notifications" -> CompanionToolExecution(notifications(args))
            "reply_to_notification" -> reply(args)
            "control_media" -> CompanionToolExecution(media(args.optString("action")))
            "play_music" -> CompanionToolExecution(playMusic(args))
            "set_phone_volume" -> CompanionToolExecution(volume(args))
            "set_flashlight" -> CompanionToolExecution(flashlight(args.optBoolean("on", true)))
            "get_phone_status" -> CompanionToolExecution(status())
            "find_my_phone" -> CompanionToolExecution(findPhone())
            "open_app" -> CompanionToolExecution(openApp(args.optString("app")))
            "start_navigation" -> CompanionToolExecution(navigate(args))
            "open_google_photos_search" -> CompanionToolExecution(googlePhotosSearch(args.optString("query")))
            else -> null
        }
    }

    // ---- texts and calls -------------------------------------------------

    private fun sendText(args: JSONObject): CompanionToolExecution {
        val message = args.optString("message").trim()
        if (message.isEmpty()) return CompanionToolExecution(GoogleAccess.error("What should the message say?"))
        if (!granted(Manifest.permission.SEND_SMS)) return CompanionToolExecution(needs("Text messages"))
        return withRecipient(args.optString("to"), verb = "Text") { match ->
            PendingActions.confirm("Text ${match.name} (${match.number}):\n\"${message}\"", "Send") {
                PendingOutcome(
                    runCatching {
                        @Suppress("DEPRECATION")
                        val sms = if (android.os.Build.VERSION.SDK_INT >= 31) {
                            context.getSystemService(SmsManager::class.java)
                        } else {
                            SmsManager.getDefault()
                        }
                        sms.sendMultipartTextMessage(match.number, null, sms.divideMessage(message), null, null)
                        "Sent to ${match.name}."
                    }.getOrElse { "Couldn't send the text: ${it.message}" },
                )
            }
        }
    }

    private fun call(args: JSONObject): CompanionToolExecution {
        if (!granted(Manifest.permission.CALL_PHONE)) return CompanionToolExecution(needs("Phone calls"))
        return withRecipient(args.optString("to"), verb = "Call") { match ->
            PendingActions.confirm("Call ${match.name} (${match.label})?", "Call") {
                PendingOutcome(
                    runCatching {
                        context.getSystemService(TelecomManager::class.java)
                            .placeCall(Uri.fromParts("tel", match.number, null), null)
                        "Calling ${match.name} on your phone."
                    }.getOrElse { "Couldn't start the call: ${it.message}" },
                )
            }
        }
    }

    /** Resolves a contact; asks which one on the watch when several match. */
    private fun withRecipient(
        to: String,
        verb: String,
        card: (PhoneContacts.Match) -> ClarificationCard,
    ): CompanionToolExecution {
        if (!PhoneContacts.canRead(context) && to.count { it.isDigit() } < 3) {
            return CompanionToolExecution(needs("Contacts"))
        }
        val matches = PhoneContacts.find(context, to)
        return when {
            matches.isEmpty() -> CompanionToolExecution(GoogleAccess.error("I couldn't find \"$to\" in your contacts."))
            matches.size == 1 || matches[0].name.equals(to.trim(), ignoreCase = true) && matches.count { it.name == matches[0].name } == 1 ->
                CompanionToolExecution(GoogleAccess.ok("Asking the user to confirm on the watch."), clarificationCard = card(matches[0]))
            else -> {
                val picker = PendingActions.offer(
                    "$verb which?",
                    matches.take(3).map { match ->
                        PendingActions.Choice("${match.name} ${match.label}") { PendingOutcome("", card(match)) }
                    },
                    cancelLabel = null,
                )
                CompanionToolExecution(GoogleAccess.ok("Several contacts match; asking the user to pick."), clarificationCard = picker)
            }
        }
    }

    // ---- notifications ---------------------------------------------------

    private fun notifications(args: JSONObject): JSONObject {
        if (!BillyNotificationListener.isEnabled(context)) return needs("Notifications")
        val filter = args.optString("from").trim().lowercase()
        val items = BillyNotificationListener.recent
            .filter { !args.optBoolean("messages_only", false) || it.isMessage }
            .filter { filter.isEmpty() || filter in "${it.title} ${it.app}".lowercase() }
            .take(args.optInt("max_results", 8).coerceIn(1, 20))
        if (items.isEmpty()) return GoogleAccess.ok("No recent notifications${if (filter.isNotEmpty()) " from $filter" else ""}.")
        val list = JSONArray()
        items.forEach { item ->
            list.put(
                JSONObject()
                    .put("app", item.app)
                    .put("from", item.title)
                    .put("text", item.text)
                    .put("when", Instant.ofEpochMilli(item.postedAt).atZone(TimeArgs.zone()).let { TimeArgs.spoken(it) })
                    .put("can_reply", item.replyAction != null),
            )
        }
        return GoogleAccess.ok("${items.size} recent notifications.").put("notifications", list)
    }

    private fun reply(args: JSONObject): CompanionToolExecution {
        if (!BillyNotificationListener.isEnabled(context)) return CompanionToolExecution(needs("Notifications"))
        val to = args.optString("to").trim().lowercase()
        val message = args.optString("message").trim()
        if (message.isEmpty()) return CompanionToolExecution(GoogleAccess.error("What should the reply say?"))
        val target: RecentNotification = BillyNotificationListener.recent.firstOrNull {
            it.replyAction != null && (to.isEmpty() || to in it.title.lowercase() || to in it.app.lowercase())
        } ?: return CompanionToolExecution(
            GoogleAccess.error("I don't see a recent message from \"$to\" that I can reply to. Use send_text_message for SMS."),
        )
        val card = PendingActions.confirm("Reply to ${target.title} (${target.app}):\n\"${message}\"", "Send") {
            PendingOutcome(
                if (BillyNotificationListener.reply(context, target, message)) "Replied to ${target.title}." else "That conversation can't be replied to anymore.",
            )
        }
        return CompanionToolExecution(GoogleAccess.ok("Asking the user to confirm on the watch."), clarificationCard = card)
    }

    // ---- media -----------------------------------------------------------

    private fun activeController(): MediaController? {
        if (!BillyNotificationListener.isEnabled(context)) return null
        return runCatching {
            context.getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(BillyNotificationListener.component(context))
                .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: context.getSystemService(MediaSessionManager::class.java)
                    .getActiveSessions(BillyNotificationListener.component(context)).firstOrNull()
        }.getOrNull()
    }

    private fun media(action: String): JSONObject {
        val controller = activeController()
        if (action == "now_playing") {
            val meta = controller?.metadata ?: return GoogleAccess.ok("Nothing seems to be playing.")
            val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            return GoogleAccess.ok("${if (playing) "Playing" else "Paused"}: $title${if (artist.isNotBlank()) " by $artist" else ""}.")
        }
        if (controller != null) {
            val transport = controller.transportControls
            when (action) {
                "play" -> transport.play()
                "pause" -> transport.pause()
                "toggle" -> if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) transport.pause() else transport.play()
                "next" -> transport.skipToNext()
                "previous" -> transport.skipToPrevious()
                else -> return GoogleAccess.error("Unknown media action.")
            }
        } else {
            val key = when (action) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> return GoogleAccess.error("Unknown media action.")
            }
            val audio = context.getSystemService(AudioManager::class.java)
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        }
        return GoogleAccess.ok("Done.")
    }

    private fun playMusic(args: JSONObject): JSONObject {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return GoogleAccess.error("What should I play?")
        val appName = args.optString("app").trim()
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            findPackage(appName.ifBlank { null })?.let { setPackage(it) }
        }
        return launch(intent, "Playing $query${if (appName.isNotBlank()) " on $appName" else ""}.")
    }

    // ---- device -----------------------------------------------------------

    private fun volume(args: JSONObject): JSONObject {
        val audio = context.getSystemService(AudioManager::class.java)
        val stream = when (args.optString("stream")) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val percent = args.optInt("level", 50).coerceIn(0, 100)
        return runCatching {
            audio.setStreamVolume(stream, (audio.getStreamMaxVolume(stream) * percent / 100.0).toInt(), 0)
            GoogleAccess.ok("Volume set to $percent%.")
        }.getOrElse { GoogleAccess.error("Android didn't allow that volume change (Do Not Disturb may be on).") }
    }

    private fun flashlight(on: Boolean): JSONObject {
        val camera = context.getSystemService(CameraManager::class.java)
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return GoogleAccess.error("This phone has no flashlight.")
        return runCatching {
            camera.setTorchMode(id, on)
            GoogleAccess.ok("Flashlight ${if (on) "on" else "off"}.")
        }.getOrElse { GoogleAccess.error("Couldn't change the flashlight: ${it.message}") }
    }

    private fun status(): JSONObject {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = battery.isCharging
        val ringer = when (context.getSystemService(AudioManager::class.java).ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "ring"
        }
        return GoogleAccess.ok("Phone battery $level%${if (charging) ", charging" else ""}. Ringer: $ringer.")
            .put("battery_percent", level).put("charging", charging).put("ringer", ringer)
    }

    private fun findPhone(): JSONObject {
        return runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            val previous = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            ringtone.play()
            Handler(Looper.getMainLooper()).postDelayed({
                ringtone.stop()
                audio.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0)
            }, 20_000)
            GoogleAccess.ok("Your phone is ringing.")
        }.getOrElse { GoogleAccess.error("Couldn't ring the phone: ${it.message}") }
    }

    private fun openApp(name: String): JSONObject {
        val pkg = findPackage(name) ?: return GoogleAccess.error("I couldn't find an app called \"$name\".")
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return GoogleAccess.error("That app can't be opened directly.")
        return launch(intent, "Opened $name on your phone.")
    }

    private fun navigate(args: JSONObject): JSONObject {
        val destination = args.optString("destination").trim()
        if (destination.isEmpty()) return GoogleAccess.error("Where to?")
        val mode = args.optString("travel_mode").ifBlank { "drive" }
        val uri = if (mode == "transit") {
            Uri.parse("https://www.google.com/maps/dir/?api=1&travelmode=transit&destination=${Uri.encode(destination)}")
        } else {
            val code = when (mode) { "walk" -> "w"; "bicycle" -> "b"; else -> "d" }
            Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$code")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (mode != "transit") intent.setPackage("com.google.android.apps.maps")
        return launch(intent, "Starting ${if (mode == "drive") "" else "$mode "}navigation to $destination on your phone.")
    }

    private fun googlePhotosSearch(query: String): JSONObject {
        if (query.isBlank()) return GoogleAccess.error("What should I search for?")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://photos.google.com/search/${Uri.encode(query.trim())}"))
            .setPackage("com.google.android.apps.photos")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.packageManager.resolveActivity(intent, 0) == null) intent.setPackage(null)
        return launch(intent, "Opened Google Photos on your phone searching \"$query\".")
    }

    // ---- helpers ------------------------------------------------------------

    private fun launch(intent: Intent, okSummary: String): JSONObject {
        if (!Settings.canDrawOverlays(context)) {
            return needs("Open apps from the watch")
        }
        val locked = LaunchActivity.isLocked(context)
        return runCatching {
            // LaunchActivity wakes the screen and asks to unlock first when needed.
            context.startActivity(LaunchActivity.intentFor(context, intent))
            GoogleAccess.ok(if (locked) "$okSummary Unlock your phone to finish opening it." else okSummary)
                .put("phone_locked", locked)
        }.getOrElse { GoogleAccess.error("The phone couldn't open that: ${it.message}") }
    }

    private fun findPackage(name: String?): String? {
        val wanted = name?.trim()?.lowercase().orEmpty()
        if (wanted.isEmpty()) return null
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val labeled = apps.map { it.activityInfo.packageName to it.loadLabel(pm).toString().lowercase() }
        return labeled.firstOrNull { it.second == wanted }?.first
            ?: labeled.firstOrNull { it.second.startsWith(wanted) }?.first
            ?: labeled.firstOrNull { wanted in it.second }?.first
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun needs(what: String): JSONObject = JSONObject()
        .put("status", "needs_permission")
        .put("summary", "Turn on \"$what\" in Billy Companion on your phone first.")

    private fun decl(name: String, description: String, required: List<String> = emptyList(), properties: Map<String, JSONObject> = emptyMap()) =
        JSONObject().put("name", name).put("description", description).put("parameters", objectSchema(required, properties))
}
