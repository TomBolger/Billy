package com.tombo.billyassistant.companion

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.tombo.billyassistant.companion.agent.GeminiClient
import com.tombo.billyassistant.companion.agent.GeminiKeyTestResult
import com.tombo.billyassistant.companion.auth.GoogleApiAuthorization
import com.tombo.billyassistant.companion.auth.GoogleApiAuthorizationResult
import com.tombo.billyassistant.companion.auth.GoogleApiScopes
import com.tombo.billyassistant.companion.auth.GoogleAuthStore
import com.tombo.billyassistant.companion.gemini.GeminiAccountBridge
import com.tombo.billyassistant.companion.gemini.GeminiSignInActivity
import com.tombo.billyassistant.companion.phone.BillyNotificationListener
import com.tombo.billyassistant.companion.pebble.BillyPebbleProtocol
import com.tombo.billyassistant.companion.pebble.PebbleWatchStore
import com.tombo.billyassistant.companion.pebble.PendingWatchPromptStore
import com.tombo.billyassistant.companion.profile.BillyProfilePackParseResult
import com.tombo.billyassistant.companion.profile.BillyProfilePackParser
import com.tombo.billyassistant.companion.profile.BillyUserProfileStore
import com.tombo.billyassistant.companion.settings.SettingsStore
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Billy Companion setup: one screen, four steps. Everything Billy can do on
 * the phone is listed with a single button to turn it on.
 */
class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var authStore: GoogleAuthStore
    private lateinit var profileStore: BillyUserProfileStore
    private lateinit var googleAuth: GoogleApiAuthorization
    private lateinit var content: LinearLayout
    private var keyStatus = ""
    private var googleStatus = ""
    private var memoryStatus = ""
    private var testStatus = ""
    private var myGeminiStatus = ""
    private var showAdvanced = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Background location can only be asked for after foreground location.
        if (hasLocation() && !hasBackgroundLocation() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        render()
    }
    private val backgroundLocationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        handleGoogleResult(googleAuth.completeAccessRequest(result.data), allowConsentUi = false)
    }
    private val profilePackLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importProfilePack(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        authStore = GoogleAuthStore(this)
        profileStore = BillyUserProfileStore(this)
        googleAuth = GoogleApiAuthorization(this)
        settingsStore.save(settingsStore.load().copy(pebbleBridgeEnabled = true))
        window.statusBarColor = COLOR_BG
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            fitsSystemWindows = true
            addView(content)
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    // ---- layout -------------------------------------------------------------

    private fun render() {
        content.removeAllViews()
        val missing = missingSteps()
        content.addView(text("Billy Companion", 28f, COLOR_TEXT, bold = true))
        content.addView(text(versionLabel(), 13f, COLOR_MUTED))
        content.addView(
            text(
                if (missing.isEmpty()) "✓ Ready. Ask Billy anything from your watch." else "${missing.size} step${if (missing.size == 1) "" else "s"} left: ${missing.joinToString(", ")}",
                16f,
                if (missing.isEmpty()) COLOR_GOOD else COLOR_WARN,
                bold = true,
            ).padTop(12),
        )

        card("1  Gemini API key") {
            val settings = settingsStore.load()
            val input = EditText(this@MainActivity).apply {
                setText(settings.geminiApiKey)
                hint = "Paste your key"
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setTextColor(COLOR_TEXT)
                setHintTextColor(COLOR_MUTED)
                background = rounded(COLOR_FIELD, COLOR_STROKE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            addView(input, matchWrap())
            addView(row(
                button("Save & test") { saveAndTestKey(input.text.toString()) },
                button("Get a free key", primary = false) { open("https://aistudio.google.com/app/apikey") },
            ))
            addView(status(keyStatus.ifBlank { if (settings.geminiApiKey.isBlank()) "" else "Saved." }))
            val active = settings.modelOverride.ifBlank { settings.lastWatchModel }.ifBlank { GeminiClient.DEFAULT_MODEL }
            val label = SettingsStore.MODELS.firstOrNull { it.first == active }?.second ?: active
            addView(text("Model: $label" + if (settings.modelOverride.isBlank()) " (from the watch app's settings)" else "", 14f, COLOR_MUTED).padTop(10))
            addView(row(button("Change model", primary = false) { pickModel() }))
        }

        card("2  Google account") {
            addView(text("Calendar, Tasks, Gmail, Drive & Docs, and Contacts. One consent screen.", 14f, COLOR_MUTED))
            val granted = authStore.grantedScopes()
            GOOGLE_SERVICES.forEach { (label, scopes) ->
                addView(checkRow(label, granted.containsAll(scopes)))
            }
            val connected = granted.containsAll(GoogleApiScopes.allUseful)
            addView(row(button(if (connected) "Reconnect Google" else "Connect Google", primary = !connected) { connectGoogle() }))
            addView(status(googleStatus))
        }

        card("3  Phone access") {
            addView(text("Turn on what you want Billy to do on this phone.", 14f, COLOR_MUTED))
            if (runtimePermissionsMissing().isNotEmpty()) {
                addView(row(button("Allow all") { permissionLauncher.launch(runtimePermissionsMissing().toTypedArray()) }))
            }
            accessRow("Location", "Weather, \"near me\", maps.", hasLocation() && hasBackgroundLocation()) {
                if (!hasLocation()) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    openAppSettings("Choose \"Allow all the time\" so Billy can use location while the screen is off.")
                }
            }
            accessRow("Photos", "Find and show your photos.", hasPhotos()) { permissionLauncher.launch(photoPermissions()) }
            accessRow("Contacts", "Text and call people by name.", granted(Manifest.permission.READ_CONTACTS)) {
                permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            }
            accessRow("Text messages", "Send SMS (you confirm on the watch).", granted(Manifest.permission.SEND_SMS)) {
                permissionLauncher.launch(arrayOf(Manifest.permission.SEND_SMS))
            }
            accessRow("Phone calls", "Call contacts (you confirm on the watch).", granted(Manifest.permission.CALL_PHONE)) {
                permissionLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
            }
            accessRow("Notifications & music", "Read and reply to messages, control what's playing.", BillyNotificationListener.isEnabled(this@MainActivity)) {
                explainThen("Find Billy in the list and turn it on.") {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
            accessRow("Open apps from the watch", "Navigation, music, and opening apps while the phone is locked.", Settings.canDrawOverlays(this@MainActivity)) {
                explainThen("Turn on \"Allow display over other apps\" for Billy. Android needs this to open apps from the background.") {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }

        card("4  Memory") {
            addView(text(memoryStatus.ifBlank { profileStore.load().statusSummary() }, 14f, COLOR_MUTED))
            addView(row(
                button("Add memory") { addMemory() },
                button("Import Profile Pack", primary = false) { profilePackLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*")) },
            ))
            addView(row(
                button("What's a Profile Pack?", primary = false) { profilePackHelp() },
                button("Clear", primary = false) { confirmClear() },
            ))
        }

        card("5  Your Gemini account (optional)") {
            addView(text(
                "Lets Billy ask your own Gemini for things only it can reach: your Google Photos library, Gemini's saved info and past chats, Gems, Keep, YouTube, and Google Home. " +
                    "Experimental: it uses the Gemini website the way a browser does, which Google doesn't officially support. If it ever stops working, Billy just uses its normal tools. Your sign-in stays on this phone.",
                14f,
                COLOR_MUTED,
            ))
            val state = GeminiAccountBridge.state(this@MainActivity)
            val signedIn = GeminiAccountBridge.isSignedIn(this@MainActivity)
            val headline = when {
                state == GeminiAccountBridge.State.WORKING -> "✓ On" + GeminiAccountBridge.lastSuccessAt(this@MainActivity).takeIf { it > 0 }
                    ?.let { " · last answer ${android.text.format.DateUtils.getRelativeTimeSpanString(it)}" }.orEmpty()
                state == GeminiAccountBridge.State.NEEDS_ATTENTION -> "✗ Gemini account link needs attention. " + GeminiAccountBridge.lastError(this@MainActivity)
                signedIn -> "Off"
                else -> "Not set up"
            }
            addView(status(headline).also { it.visibility = View.VISIBLE })
            when {
                state == GeminiAccountBridge.State.WORKING -> {
                    addView(row(
                        button("Test") { testMyGemini() },
                        button("Turn off", primary = false) { GeminiAccountBridge.setEnabled(this@MainActivity, false); render() },
                        button("Sign out", primary = false) { signOutMyGemini() },
                    ))
                }
                state == GeminiAccountBridge.State.NEEDS_ATTENTION -> {
                    addView(row(
                        button("Sign in again") { startActivity(Intent(this@MainActivity, GeminiSignInActivity::class.java)) },
                        button("Turn off", primary = false) { GeminiAccountBridge.setEnabled(this@MainActivity, false); render() },
                    ))
                    addView(row(button("Use Chrome's sign-in (root)", primary = false) { importChromeSignIn() }))
                }
                signedIn -> {
                    addView(row(
                        button("Turn on") { GeminiAccountBridge.setEnabled(this@MainActivity, true); render() },
                        button("Sign out", primary = false) { signOutMyGemini() },
                    ))
                }
                else -> {
                    addView(row(button("Sign in to Gemini") { startActivity(Intent(this@MainActivity, GeminiSignInActivity::class.java)) }))
                    addView(row(button("Use Chrome's sign-in (root)", primary = false) { importChromeSignIn() }))
                }
            }
            addView(status(myGeminiStatus))
        }

        content.addView(button(if (showAdvanced) "Hide advanced" else "Advanced", primary = false) {
            showAdvanced = !showAdvanced
            render()
        }.let { it.layoutParams = matchWrap().apply { topMargin = dp(16) }; it })

        if (showAdvanced) {
            card("Google Maps key (optional)") {
                addView(text("Only adds route lines to watch maps. Places, hours, and travel questions already work without it.", 14f, COLOR_MUTED))
                val mapsInput = EditText(this@MainActivity).apply {
                    setText(settingsStore.load().googleMapsApiKey)
                    hint = "Maps API key"
                    isSingleLine = true
                    setTextColor(COLOR_TEXT)
                    setHintTextColor(COLOR_MUTED)
                    background = rounded(COLOR_FIELD, COLOR_STROKE)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                addView(mapsInput, matchWrap())
                addView(row(button("Save", primary = false) {
                    settingsStore.save(settingsStore.load().copy(googleMapsApiKey = mapsInput.text.toString().trim()))
                    toastStatus("Maps key saved.")
                }))
            }
            card("Test from the phone") {
                addView(text("Type a prompt and Billy opens on the watch with it.", 14f, COLOR_MUTED))
                val promptInput = EditText(this@MainActivity).apply {
                    hint = "What's the weather tomorrow?"
                    setTextColor(COLOR_TEXT)
                    setHintTextColor(COLOR_MUTED)
                    background = rounded(COLOR_FIELD, COLOR_STROKE)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                addView(promptInput, matchWrap())
                addView(row(button("Send to watch") { sendToWatch(promptInput.text.toString()) }))
                addView(status(testStatus))
            }
            card("About this install") {
                addView(text("Package: $packageName\nSigning SHA-1: ${signingSha1()}", 13f, COLOR_MUTED).also { it.setTextIsSelectable(true) })
                addView(row(button("Forget Google sign-in", primary = false) {
                    authStore.clear()
                    googleStatus = "Forgotten. Tap Connect Google to sign in again."
                    render()
                }))
            }
        }
    }

    private fun missingSteps(): List<String> = buildList {
        if (settingsStore.load().geminiApiKey.isBlank()) add("Gemini key")
        if (!authStore.grantedScopes().containsAll(GoogleApiScopes.allUseful)) add("Google")
        if (!hasLocation()) add("Location")
    }

    // ---- actions --------------------------------------------------------------

    private fun saveAndTestKey(raw: String) {
        val key = raw.filterNot { it.isWhitespace() }
        settingsStore.save(settingsStore.load().copy(geminiApiKey = key))
        keyStatus = "Testing..."
        render()
        Thread {
            val result = GeminiClient().testKey(key)
            runOnUiThread {
                keyStatus = when (result) {
                    is GeminiKeyTestResult.Passed -> "✓ ${result.message}"
                    is GeminiKeyTestResult.Failed -> "✗ ${result.reason}"
                }
                render()
            }
        }.start()
    }

    private fun pickModel() {
        val settings = settingsStore.load()
        val labels = listOf("Same as watch app settings") + SettingsStore.MODELS.map { it.second }
        val ids = listOf("") + SettingsStore.MODELS.map { it.first }
        AlertDialog.Builder(this)
            .setTitle("Gemini model")
            .setSingleChoiceItems(labels.toTypedArray(), ids.indexOf(settings.modelOverride).coerceAtLeast(0)) { dialog, which ->
                settingsStore.save(settingsStore.load().copy(modelOverride = ids[which]))
                dialog.dismiss()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectGoogle() {
        googleStatus = "Opening Google sign-in..."
        render()
        googleAuth.requestAccess(GoogleApiScopes.allUseful) { result ->
            runOnUiThread { handleGoogleResult(result, allowConsentUi = true) }
        }
    }

    private fun handleGoogleResult(result: GoogleApiAuthorizationResult, allowConsentUi: Boolean) {
        when (result) {
            is GoogleApiAuthorizationResult.Authorized -> {
                authStore.saveGrant(result.grantedScopes.filter { it.isNotBlank() }, result.accessToken)
                googleStatus = "✓ Connected."
            }
            is GoogleApiAuthorizationResult.NeedsUserConsent -> {
                if (allowConsentUi) {
                    runCatching {
                        consentLauncher.launch(IntentSenderRequest.Builder(result.pendingIntent.intentSender).build())
                    }.onFailure { googleStatus = "✗ Couldn't open Google consent: ${it.message}" }
                } else {
                    googleStatus = "✗ Google still wants consent. If this repeats, the Google Cloud OAuth client may not match this app's SHA-1 (see Advanced)."
                }
            }
            is GoogleApiAuthorizationResult.Failed -> googleStatus = "✗ ${result.reason}"
        }
        render()
    }

    private fun testMyGemini() {
        myGeminiStatus = "Asking your Gemini..."
        render()
        Thread {
            val reply = GeminiAccountBridge.ask(this, "In one short sentence, what's something you know about me? If nothing, just say hello.")
            runOnUiThread {
                myGeminiStatus = when (reply) {
                    is GeminiAccountBridge.Reply.Answer -> "✓ ${reply.text.take(200)}"
                    is GeminiAccountBridge.Reply.Failed -> "✗ ${if (reply.reason == "signed_out") "Signed out. Tap Sign in again." else reply.reason}"
                }
                render()
            }
        }.start()
    }

    private fun importChromeSignIn() {
        explainThen("Billy will ask for root access to copy your Google sign-in from Chrome into its own private storage on this phone. Sign in to gemini.google.com in Chrome first.") {
            myGeminiStatus = "Copying from Chrome..."
            render()
            Thread {
                val error = GeminiAccountBridge.importFromChrome(this)
                val ok = error == null && GeminiAccountBridge.verifySignIn(this)
                if (ok) GeminiAccountBridge.markSignedIn(this)
                runOnUiThread {
                    myGeminiStatus = when {
                        ok -> "✓ Signed in using Chrome."
                        error != null -> "✗ $error"
                        else -> "✗ Copied, but Gemini still isn't signed in. Try Sign in to Gemini instead."
                    }
                    render()
                }
            }.start()
        }
    }

    private fun signOutMyGemini() {
        GeminiAccountBridge.signOut(this)
        myGeminiStatus = "Signed out."
        render()
    }

    private fun sendToWatch(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty()) return
        testStatus = "Sending..."
        render()
        lifecycleScope.launch {
            val store = PendingWatchPromptStore(this@MainActivity)
            store.save(text)
            val watches = PebbleWatchStore(this@MainActivity).lastWatch()?.let { listOf(it) }
            val sender = DefaultPebbleSender(this@MainActivity)
            try {
                sender.startAppOnTheWatch(BillyPebbleProtocol.APP_UUID, watches)
                delay(900)
                if (store.peek() == text) {
                    sender.sendDataToPebble(
                        BillyPebbleProtocol.APP_UUID,
                        mapOf(BillyPebbleProtocol.WATCH_PROMPT to PebbleDictionaryItem.Text(text.take(240))),
                        watches,
                    )
                    store.clearIf(text)
                }
                testStatus = "✓ Sent. Check your watch."
            } catch (e: Exception) {
                testStatus = "✗ ${e.message ?: e.javaClass.simpleName}"
            } finally {
                sender.close()
            }
            render()
        }
    }

    private fun addMemory() {
        val input = EditText(this@MainActivity).apply {
            hint = "My dog is named Scout."
            minLines = 2
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Something Billy should remember")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val memory = profileStore.addMemory(input.text.toString(), source = "companion")
                memoryStatus = if (memory == null) "Nothing saved." else "Remembered: ${memory.fact}"
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importProfilePack(uri: Uri) {
        Thread {
            val parsed = runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    require(bytes.size <= 4 * 1024 * 1024) { "File is too large." }
                    BillyProfilePackParser.parseMarkdown(bytes.toString(Charsets.UTF_8))
                } ?: error("Couldn't open the file.")
            }
            runOnUiThread {
                parsed.fold(onSuccess = ::confirmProfilePack, onFailure = {
                    memoryStatus = "Import failed: ${it.message}"
                    render()
                })
            }
        }.start()
    }

    private fun confirmProfilePack(parsed: BillyProfilePackParseResult) {
        if (parsed.facts.isEmpty()) {
            memoryStatus = parsed.summary()
            render()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Import Profile Pack?")
            .setMessage(parsed.summary() + "\n\n" + parsed.facts.take(5).joinToString("\n") { "- ${it.fact}" })
            .setPositiveButton("Import") { _, _ ->
                val stored = profileStore.importProfilePack(parsed.facts)
                memoryStatus = "Imported ${stored.imported} facts."
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun profilePackHelp() {
        AlertDialog.Builder(this)
            .setTitle("Profile Pack")
            .setMessage(
                "Give the Billy Profile Pack template (docs folder in the Billy repo) to the Gemini app and ask it to fill it in from what it knows about you. " +
                    "Review it, delete anything you don't want stored, save it as a text file, and import it here. It stays on this phone.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear Billy's memory?")
            .setMessage("Removes everything Billy remembers about you on this phone.")
            .setPositiveButton("Clear") { _, _ ->
                profileStore.clear()
                memoryStatus = ""
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun explainThen(message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Open settings") { _, _ -> runCatching(action) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppSettings(message: String) {
        explainThen(message) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun toastStatus(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---- permission checks ----------------------------------------------------

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasBackgroundLocation() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun photoPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasPhotos() = photoPermissions().all(::granted)

    private fun runtimePermissionsMissing(): List<String> =
        (listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
        ) + photoPermissions()).filterNot(::granted)

    // ---- view helpers -----------------------------------------------------------

    private fun card(title: String, build: LinearLayout.() -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_CARD, COLOR_STROKE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(text(title, 18f, COLOR_TEXT, bold = true).also { it.setPadding(0, 0, 0, dp(6)) })
            build()
        }
        content.addView(box, matchWrap().apply { topMargin = dp(16) })
    }

    private fun LinearLayout.accessRow(title: String, detail: String, ok: Boolean, onAllow: () -> Unit) {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 15f, COLOR_TEXT, bold = true))
                addView(text(detail, 13f, COLOR_MUTED))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (ok) {
                addView(text("✓", 20f, COLOR_GOOD, bold = true))
            } else {
                addView(button("Allow") { onAllow() })
            }
        })
    }

    private fun checkRow(label: String, ok: Boolean): View = text(
        "${if (ok) "✓" else "○"}  $label",
        15f,
        if (ok) COLOR_GOOD else COLOR_MUTED,
    ).padTop(4)

    private fun status(message: String): View = text(
        message,
        14f,
        when {
            message.startsWith("✓") -> COLOR_GOOD
            message.startsWith("✗") -> COLOR_WARN
            else -> COLOR_MUTED
        },
    ).padTop(6).also { it.visibility = if (message.isBlank()) View.GONE else View.VISIBLE }

    private fun row(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(10), 0, 0)
        buttons.forEach { addView(it) }
    }

    private fun button(label: String, primary: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(if (primary) COLOR_ACCENT_TEXT else COLOR_TEXT)
        background = rounded(if (primary) COLOR_ACCENT else COLOR_FIELD, if (primary) COLOR_ACCENT else COLOR_STROKE)
        setPadding(dp(14), 0, dp(14), 0)
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8) }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.1f)
    }

    private fun TextView.padTop(value: Int): TextView = apply { setPadding(0, dp(value), 0, 0) }

    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun versionLabel(): String = runCatching {
        "Version " + packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrDefault("")

    @Suppress("DEPRECATION")
    private fun signingSha1(): String = runCatching {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners.orEmpty()
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        }
        MessageDigest.getInstance("SHA-1").digest(signers.first().toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("unknown")

    companion object {
        private val COLOR_BG = Color.rgb(15, 20, 30)
        private val COLOR_CARD = Color.rgb(24, 31, 45)
        private val COLOR_FIELD = Color.rgb(35, 44, 60)
        private val COLOR_STROKE = Color.rgb(60, 72, 92)
        private val COLOR_TEXT = Color.rgb(245, 247, 250)
        private val COLOR_MUTED = Color.rgb(170, 180, 195)
        private val COLOR_GOOD = Color.rgb(74, 222, 128)
        private val COLOR_WARN = Color.rgb(251, 191, 36)
        private val COLOR_ACCENT = Color.rgb(170, 255, 255)
        private val COLOR_ACCENT_TEXT = Color.rgb(15, 23, 42)
        private val GOOGLE_SERVICES = listOf(
            "Calendar" to GoogleApiScopes.calendar,
            "Tasks" to GoogleApiScopes.tasks,
            "Gmail" to GoogleApiScopes.gmail,
            "Drive & Docs" to (GoogleApiScopes.drive + GoogleApiScopes.docs + GoogleApiScopes.sheets),
            "Contacts" to GoogleApiScopes.people,
        )
    }
}
