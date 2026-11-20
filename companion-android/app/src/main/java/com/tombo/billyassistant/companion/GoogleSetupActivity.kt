package com.tombo.billyassistant.companion

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
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
import com.tombo.billyassistant.companion.auth.GoogleAccessTokenResult
import com.tombo.billyassistant.companion.auth.GoogleApiScopes
import com.tombo.billyassistant.companion.auth.GoogleAuthStore
import com.tombo.billyassistant.companion.auth.OwnGoogleProject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Step-by-step setup of the user's own free Google Cloud project, so Billy can
 * use their Calendar, Tasks, Gmail, Drive and Contacts without anyone adding
 * them as a tester. Every step has a button that opens the right Google page.
 */
class GoogleSetupActivity : ComponentActivity() {
    private lateinit var content: LinearLayout
    private var draftId = ""
    private var draftSecret = ""
    private var clientStatus = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        draftId = OwnGoogleProject.clientId(this)
        draftSecret = OwnGoogleProject.clientSecret(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        render()
    }

    // ---- layout ---------------------------------------------------------------

    private fun render() {
        content.removeAllViews()
        content.addView(text("Set up Google", 28f, COLOR_TEXT, bold = true))
        if (OwnGoogleProject.isConnected(this)) renderConnected() else renderSteps()
    }

    private fun renderConnected() {
        val email = OwnGoogleProject.email(this)
        content.addView(text("✓ Connected" + if (email.isNotBlank()) " as $email" else "", 16f, COLOR_GOOD, bold = true).padTop(12))
        card("What Billy can use") {
            val granted = GoogleAuthStore(this@GoogleSetupActivity).grantedScopes()
            SERVICES.forEach { (label, scopes) -> addView(checkRow(label, granted.containsAll(scopes))) }
            if (!granted.containsAll(GoogleApiScopes.allUseful)) {
                addView(text("Some boxes weren't ticked on Google's page. Tap Sign in again and tick them all.", 14f, COLOR_WARN).padTop(8))
            }
            addView(row(
                button("Check it works") { checkServices() },
                button("Sign in again", primary = false) { signIn() },
            ))
            addView(status(lastStatus))
        }
        card("Done") {
            addView(text("Ask Billy things like \"what's on my calendar tomorrow?\" or \"any new email from Sam?\".", 14f, COLOR_MUTED))
            addView(row(button("Back to Billy Companion") { finish() }))
        }
        card("Disconnect") {
            addView(text("Disconnect signs Billy out of Google. Start over also forgets your Client ID, for using a different Google project.", 14f, COLOR_MUTED))
            addView(row(
                button("Disconnect", primary = false) {
                    OwnGoogleProject.disconnect(this@GoogleSetupActivity)
                    lastStatus = ""
                    render()
                },
                button("Start over", primary = false) { confirmStartOver() },
            ))
        }
    }

    private fun renderSteps() {
        content.addView(text(
            "Billy reaches your Calendar, Tasks, Gmail, Drive and Contacts through your own free Google Cloud project. " +
                "It costs nothing, needs no credit card, and only you and your phone ever hold the keys. Nobody needs to invite you.\n\n" +
                "It takes about 10 minutes, once. Each step has a button that opens the right Google page. " +
                "Use the Google account you want Billy to use. A computer is easier, but a phone works too.",
            15f, COLOR_MUTED,
        ).padTop(8))
        content.addView(row(button("Send these steps to my computer", primary = false) { shareSteps() }))

        step(1, "Create a project",
            "Tap Open. Type Billy as the project name and tap Create. Wait until Google says it's ready.",
            LINK_PROJECT)
        step(2, "Turn on Google's services",
            "Tap Open. Check that the project at the top says Billy, then tap Next and Enable. " +
                "This turns on Calendar, Tasks, Gmail, Drive, Contacts, Docs, Sheets, Slides and Forms in one go.",
            LINK_APIS)
        step(3, "Set up the sign-in screen",
            "Tap Open, then Get started.\n" +
                "• App name: Billy. Support email: your email. Next.\n" +
                "• Audience: External. Next.\n" +
                "• Contact email: your email. Next.\n" +
                "• Tick \"I agree\", then Continue and Create.",
            LINK_BRANDING)
        step(4, "Publish it",
            "Tap Open. Under \"Publishing status\" tap Publish app, then Confirm. It should now say In production.\n\n" +
                "Why: while it says Testing, Google signs Billy out every 7 days. Publishing doesn't make anything public and " +
                "you don't need to send it to Google for review. Only you will use it.",
            LINK_AUDIENCE)

        card("5  Create Billy's key") {
            addView(text(
                "Tap Open. Set Application type to Desktop app, name it Billy, tap Create.\n\n" +
                    "Google then shows a Client ID and a Client secret, once. Copy each one and tap Paste here. " +
                    "(Or tap Download JSON there, open the file, copy all of it and tap Paste.)",
                14f, COLOR_MUTED,
            ))
            addView(row(button("Open", primary = false) { open(LINK_CLIENT) }))
            val idInput = field("Client ID", draftId) { draftId = it }
            val secretInput = field("Client secret", draftSecret) { draftSecret = it }
            addView(idInput, matchWrap().apply { topMargin = dp(8) })
            addView(secretInput, matchWrap().apply { topMargin = dp(8) })
            addView(row(
                button("Paste") { pasteInto(idInput, secretInput) },
                button("Save", primary = !OwnGoogleProject.hasClient(this@GoogleSetupActivity)) { saveClient() },
            ))
            val saved = OwnGoogleProject.hasClient(this@GoogleSetupActivity) &&
                draftId.trim() == OwnGoogleProject.clientId(this@GoogleSetupActivity)
            addView(status(clientStatus.ifBlank { if (saved) "✓ Saved." else "" }))
        }

        card("6  Sign in") {
            addView(text(
                "Tap Sign in with Google. Your browser opens.\n" +
                    "• Pick your Google account.\n" +
                    "• Google warns \"Google hasn't verified this app\". That's expected: it's your own project. " +
                    "Tap Advanced, then Go to Billy (unsafe).\n" +
                    "• Tick every box (or Select all) and tap Continue.\n" +
                    "• Tap Back to Billy Companion.",
                14f, COLOR_MUTED,
            ))
            val ready = OwnGoogleProject.hasClient(this@GoogleSetupActivity)
            addView(row(button("Sign in with Google", primary = ready) {
                if (ready) signIn() else toast("Do step 5 first.")
            }))
            addView(status(lastStatus))
        }

        card("Stuck?") {
            addView(text(
                "• \"Access blocked\" or \"app is being tested\": do step 4 again.\n" +
                    "• \"invalid client\" or \"redirect\" errors: the key in step 5 must be a Desktop app.\n" +
                    "• Something says it isn't turned on: do step 2 again.\n" +
                    "• Don't want to set this up? Billy still works without it. Card 5 on the main screen " +
                    "(Your Gemini account) can reach Gmail and Photos with just a sign-in.",
                14f, COLOR_MUTED,
            ))
            addView(row(button("Ask for help", primary = false) { open("https://github.com/TomBolger/Billy/issues") }))
        }
    }

    private fun step(number: Int, title: String, body: String, link: String) {
        val done = prefs().getBoolean("step_$number", false)
        card("$number  $title" + if (done) "  ✓" else "") {
            addView(text(body, 14f, COLOR_MUTED))
            addView(row(
                button("Open") { open(link) },
                button(if (done) "Done ✓" else "Mark done", primary = false) {
                    prefs().edit().putBoolean("step_$number", !done).apply()
                    render()
                },
            ))
        }
    }

    // ---- actions --------------------------------------------------------------

    private fun pasteInto(idInput: EditText, secretInput: EditText) {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        when {
            pasted.isBlank() -> toast("Nothing copied yet. Copy the value on Google's page first.")
            pasted.startsWith("{") -> {
                val error = OwnGoogleProject.saveClient(this, pasted, "")
                clientStatus = error?.let { "✗ $it" } ?: "✓ Saved from the file."
                if (error == null) {
                    draftId = OwnGoogleProject.clientId(this)
                    draftSecret = OwnGoogleProject.clientSecret(this)
                }
                render()
            }
            pasted.endsWith(".apps.googleusercontent.com") -> { draftId = pasted; idInput.setText(pasted); toast("Client ID pasted.") }
            pasted.startsWith("GOCSPX-") -> { draftSecret = pasted; secretInput.setText(pasted); toast("Client secret pasted.") }
            else -> toast("That doesn't look like a Client ID or secret. Paste it into the right box by hand.")
        }
        if (OwnGoogleProject.saveClient(this, draftId, draftSecret) == null && !pasted.startsWith("{") &&
            draftId.isNotBlank() && draftSecret.isNotBlank()
        ) {
            clientStatus = "✓ Saved. Now do step 6."
            render()
        }
    }

    private fun saveClient() {
        val error = OwnGoogleProject.saveClient(this, draftId, draftSecret)
        clientStatus = error?.let { "✗ $it" } ?: "✓ Saved. Now do step 6."
        if (error == null) {
            draftId = OwnGoogleProject.clientId(this)
            draftSecret = OwnGoogleProject.clientSecret(this)
        }
        render()
    }

    private fun signIn() {
        if (signingIn) {
            toast("Already waiting for Google. Finish in the browser, or wait a moment and try again.")
            return
        }
        signingIn = true
        lastStatus = "Waiting for you to finish in the browser..."
        render()
        val app = applicationContext
        Thread {
            val result = runCatching {
                OwnGoogleProject.signIn(app, GoogleApiScopes.allUseful, GoogleSetupActivity::class.java)
            }.getOrElse { OwnGoogleProject.Result.Failed("Sign-in failed: ${it.message}") }
            lastStatus = when (result) {
                is OwnGoogleProject.Result.Ok -> "✓ Connected" + if (result.email.isNotBlank()) " as ${result.email}." else "."
                is OwnGoogleProject.Result.Failed -> "✗ ${result.message}"
            }
            signingIn = false
            runOnUiThread { if (!isDestroyed) render() }
        }.start()
    }

    /** Calls each service once, so a step-2 miss shows up now instead of on the watch. */
    private fun checkServices() {
        lastStatus = "Checking..."
        render()
        val app = applicationContext
        Thread {
            val lines = CHECKS.map { (label, scopes, url) ->
                when (val token = OwnGoogleProject.accessToken(app, scopes)) {
                    is GoogleAccessTokenResult.Authorized -> {
                        val (code, body) = runCatching { fetch(url, token.accessToken) }.getOrElse { 0 to (it.message ?: "") }
                        when {
                            code in 200..299 -> "✓ $label"
                            body.contains("SERVICE_DISABLED") || body.contains("has not been used in project") || body.contains("is disabled") ->
                                "✗ $label is turned off: do step 2 again"
                            code == 401 || code == 403 -> "✗ $label wasn't allowed: tap Sign in again and tick every box"
                            else -> "✗ $label: couldn't reach Google ($code)"
                        }
                    }
                    is GoogleAccessTokenResult.NeedsUserGrant -> "✗ $label wasn't allowed: tap Sign in again and tick every box"
                    is GoogleAccessTokenResult.Failed -> "✗ $label: ${token.reason}"
                }
            }
            lastStatus = if (lines.all { it.startsWith("✓") }) "✓ Everything works." else lines.joinToString("\n")
            runOnUiThread { if (!isDestroyed) render() }
        }.start()
    }

    private fun fetch(url: String, token: String): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return code to stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun confirmStartOver() {
        AlertDialog.Builder(this)
            .setTitle("Start over?")
            .setMessage("Billy signs out of Google and forgets your Client ID and secret. Your Google project isn't changed.")
            .setPositiveButton("Start over") { _, _ ->
                OwnGoogleProject.forgetClient(this)
                prefs().edit().clear().apply()
                draftId = ""
                draftSecret = ""
                clientStatus = ""
                lastStatus = ""
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareSteps() {
        val steps = """
            Billy: set up Google (about 10 minutes, free)
            Use the Google account you want Billy to use.

            1. Create a project named Billy:
            $LINK_PROJECT

            2. Turn on Google's services (check the project says Billy, then Next, Enable):
            $LINK_APIS

            3. Set up the sign-in screen: Get started. App name Billy, your email, Audience External, your email, agree, Create.
            $LINK_BRANDING

            4. Publish it: under Publishing status tap Publish app, Confirm. (Otherwise Google signs Billy out every 7 days.)
            $LINK_AUDIENCE

            5. Create Billy's key: Application type Desktop app, name Billy, Create. Copy the Client ID and Client secret (or Download JSON) and send them to your phone.
            $LINK_CLIENT

            6. On your phone: Billy Companion > Set up Google. Paste the Client ID and secret, Save, then Sign in with Google.
            Google warns the app isn't verified: tap Advanced, then Go to Billy (unsafe), tick every box, Continue.
        """.trimIndent()
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Billy: set up Google")
                .putExtra(Intent.EXTRA_TEXT, steps),
            "Send the steps",
        ))
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("Couldn't open a browser.") }
    }

    private fun toast(message: String) = android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()

    private fun prefs() = getSharedPreferences("billy_google_setup", MODE_PRIVATE)

    // ---- views ----------------------------------------------------------------

    private fun card(title: String, build: LinearLayout.() -> Unit) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(COLOR_CARD, COLOR_STROKE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(text(title, 18f, COLOR_TEXT, bold = true).also { it.setPadding(0, 0, 0, dp(6)) })
            build()
        }, matchWrap().apply { topMargin = dp(14) })
    }

    private fun field(hint: String, value: String, onChange: (String) -> Unit) = EditText(this).apply {
        setText(value)
        this.hint = hint
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setTextColor(COLOR_TEXT)
        setHintTextColor(COLOR_MUTED)
        background = rounded(COLOR_FIELD, COLOR_STROKE)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = onChange(s?.toString().orEmpty())
        })
    }

    private fun checkRow(label: String, ok: Boolean): View =
        text(if (ok) "✓  $label" else "–  $label", 15f, if (ok) COLOR_GOOD else COLOR_MUTED).padTop(4)

    private fun status(message: String): View = text(
        message, 14f,
        when {
            message.startsWith("✓") -> COLOR_GOOD
            message.startsWith("✗") || message.contains("\n✗") -> COLOR_WARN
            else -> COLOR_MUTED
        },
    ).padTop(8).also { it.visibility = if (message.isBlank()) View.GONE else View.VISIBLE }

    private fun row(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(10), 0, 0)
        buttons.forEachIndexed { index, b ->
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (index > 0) marginStart = dp(8) })
        }
    }

    private fun button(label: String, primary: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(if (primary) COLOR_ACCENT_TEXT else COLOR_TEXT)
        background = rounded(if (primary) COLOR_ACCENT else COLOR_FIELD, if (primary) COLOR_ACCENT else COLOR_STROKE)
        minHeight = dp(46)
        setOnClickListener { onClick() }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.START
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun TextView.padTop(value: Int): TextView = apply { setPadding(0, dp(value), 0, 0) }

    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        // Kept across screen rotations and the trip to the browser.
        @Volatile private var signingIn = false
        @Volatile private var lastStatus = ""

        private const val LINK_PROJECT = "https://console.cloud.google.com/projectcreate"
        private const val LINK_APIS = "https://console.cloud.google.com/flows/enableapi?apiid=" +
            "calendar-json.googleapis.com,tasks.googleapis.com,gmail.googleapis.com,drive.googleapis.com,people.googleapis.com," +
            "docs.googleapis.com,sheets.googleapis.com,slides.googleapis.com,forms.googleapis.com"
        private const val LINK_BRANDING = "https://console.cloud.google.com/auth/overview"
        private const val LINK_AUDIENCE = "https://console.cloud.google.com/auth/audience"
        private const val LINK_CLIENT = "https://console.cloud.google.com/auth/clients/create"

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

        val SERVICES = listOf(
            "Calendar" to GoogleApiScopes.calendar,
            "Tasks" to GoogleApiScopes.tasks,
            "Gmail" to GoogleApiScopes.gmail,
            "Drive & Docs" to (GoogleApiScopes.drive + GoogleApiScopes.docs + GoogleApiScopes.sheets),
            "Contacts" to GoogleApiScopes.people,
        )

        private val CHECKS = listOf(
            Triple("Calendar", GoogleApiScopes.calendar, "https://www.googleapis.com/calendar/v3/users/me/calendarList?maxResults=1"),
            Triple("Tasks", GoogleApiScopes.tasks, "https://tasks.googleapis.com/tasks/v1/users/@me/lists?maxResults=1"),
            Triple("Gmail", GoogleApiScopes.gmail, "https://gmail.googleapis.com/gmail/v1/users/me/profile"),
            Triple("Drive", GoogleApiScopes.drive, "https://www.googleapis.com/drive/v3/about?fields=user"),
            Triple("Contacts", GoogleApiScopes.people, "https://people.googleapis.com/v1/people/me?personFields=names"),
        )
    }
}
