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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.tombo.billyassistant.companion.agent.GeminiClient
import com.tombo.billyassistant.companion.agent.GeminiKeyTestResult
import com.tombo.billyassistant.companion.auth.GoogleApiAuthorization
import com.tombo.billyassistant.companion.auth.GoogleApiAuthorizationResult
import com.tombo.billyassistant.companion.auth.GoogleApiScopes
import com.tombo.billyassistant.companion.auth.GoogleAuthStore
import com.tombo.billyassistant.companion.calendar.DeleteCalendarEventsResult
import com.tombo.billyassistant.companion.calendar.AndroidCalendarTools
import com.tombo.billyassistant.companion.auth.GoogleAccessTokenProvider
import com.tombo.billyassistant.companion.google.GoogleApiSetupChecker
import com.tombo.billyassistant.companion.google.GoogleApiSetupStatus
import com.tombo.billyassistant.companion.google.GoogleCalendarApiTools
import com.tombo.billyassistant.companion.google.GooglePhotosApiTools
import com.tombo.billyassistant.companion.google.GooglePhotosPickerCreateResult
import com.tombo.billyassistant.companion.google.GooglePhotosPickerStore
import com.tombo.billyassistant.companion.google.GooglePeopleApiTools
import com.tombo.billyassistant.companion.google.GooglePeopleResult
import com.tombo.billyassistant.companion.media.AndroidPhotoTools
import com.tombo.billyassistant.companion.media.PhotoAccessLevel
import com.tombo.billyassistant.companion.media.PhotoPermissionStatus
import com.tombo.billyassistant.companion.pebble.BillyPebbleProtocol
import com.tombo.billyassistant.companion.pebble.PendingWatchPromptStore
import com.tombo.billyassistant.companion.pebble.PebbleWatchStore
import com.tombo.billyassistant.companion.profile.BillyUserProfileStore
import com.tombo.billyassistant.companion.settings.CompanionSettings
import com.tombo.billyassistant.companion.settings.SettingsStore
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import java.security.MessageDigest

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var googleApiAuthorization: GoogleApiAuthorization
    private lateinit var googleAuthStore: GoogleAuthStore
    private lateinit var apiKeyInput: EditText
    private lateinit var apiKeyStateText: TextView
    private lateinit var mapsApiKeyInput: EditText
    private lateinit var mapsKeyStateText: TextView
    private lateinit var bridgeStateText: TextView
    private lateinit var calendarPermissionRow: LinearLayout
    private lateinit var locationPermissionRow: LinearLayout
    private lateinit var photoPermissionRow: LinearLayout
    private lateinit var calendarCleanupText: TextView
    private lateinit var googleAccessRows: LinearLayout
    private lateinit var googleAccessButton: Button
    private lateinit var googleActionText: TextView
    private lateinit var googleSetupCheckText: TextView
    private lateinit var googlePhotosPickerText: TextView
    private lateinit var oauthIdentityText: TextView
    private lateinit var watchPromptInput: EditText
    private lateinit var watchPromptStatusText: TextView
    private lateinit var userProfileStore: BillyUserProfileStore
    private lateinit var profileStatusText: TextView

    private var googleApiAccessState = "not requested"
    private var pendingGoogleScopes: List<String> = emptyList()
    private val googleAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        handleGoogleAuthorizationActivityResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        userProfileStore = BillyUserProfileStore(this)
        googleApiAuthorization = GoogleApiAuthorization(this)
        googleAuthStore = GoogleAuthStore(this)
        forceBridgeEnabled()
        configureSystemBars()

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(COLOR_PANEL)
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), statusBarHeight() + dp(18), dp(20), dp(28))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        panel.addView(title("Billy Companion ${appVersionLabel()}", 26f))
        panel.addView(body("Private phone and Google tools for Billy on Pebble."))
        panel.addView(spacer(20))
        panel.addView(buildGeminiSection())
        panel.addView(sectionDivider())
        panel.addView(buildBridgeSection())
        panel.addView(sectionDivider())
        panel.addView(buildWatchPromptSection())
        panel.addView(sectionDivider())
        panel.addView(buildAndroidAccessSection())
        panel.addView(sectionDivider())
        panel.addView(buildGoogleAccessSection())
        panel.addView(sectionDivider())
        panel.addView(buildProfileSection())
        panel.addView(sectionDivider())
        panel.addView(buildDiagnosticsSection())

        root.addView(panel)
        scrollView.addView(root)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(COLOR_PANEL)
            addView(scrollView)
            addView(topScrim())
        })

        renderSettings(settingsStore.load())
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::settingsStore.isInitialized) {
            renderStatus()
        }
    }

    private fun configureSystemBars() {
        window.statusBarColor = COLOR_STATUS_SCRIM
        window.navigationBarColor = COLOR_PANEL
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
    }

    private fun topScrim(): View {
        return View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(COLOR_STATUS_SCRIM, Color.TRANSPARENT),
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                statusBarHeight() + dp(48),
            )
        }
    }

    private fun buildGeminiSection(): View {
        return section("Gemini API key").apply {
            addView(apiKeyLabel("Gemini API key"))
            apiKeyInput = EditText(this@MainActivity).apply {
                hint = "Paste key"
                textSize = 16f
                minLines = 2
                maxLines = 4
                isSingleLine = false
                setHorizontallyScrolling(false)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setTextColor(COLOR_TEXT)
                setHintTextColor(COLOR_MUTED)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(COLOR_FIELD, dp(10).toFloat(), COLOR_STROKE)
            }
            addView(apiKeyInput, matchWrap())
            apiKeyStateText = muted("")
            addView(apiKeyStateText)
            addView(horizontalActions().apply {
                addView(actionButton("Save key") {
                    settingsStore.save(readSettingsFromForm())
                    renderStatus()
                })
                addView(actionButton("Verify key") {
                    testGeminiKey()
                })
                addView(actionButton("Instructions", emphasis = false) {
                    showGeminiKeyInstructions()
                })
            })
            addView(spacer(14))
            addView(apiKeyLabel("Google Maps API key"))
            mapsApiKeyInput = EditText(this@MainActivity).apply {
                hint = "Optional Places, Routes, Geocoding, Static Maps key"
                textSize = 16f
                minLines = 2
                maxLines = 4
                isSingleLine = false
                setHorizontallyScrolling(false)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setTextColor(COLOR_TEXT)
                setHintTextColor(COLOR_MUTED)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(COLOR_FIELD, dp(10).toFloat(), COLOR_STROKE)
            }
            addView(mapsApiKeyInput, matchWrap())
            mapsKeyStateText = muted("")
            addView(mapsKeyStateText)
            addView(horizontalActions().apply {
                addView(actionButton("Save keys") {
                    settingsStore.save(readSettingsFromForm())
                    renderStatus()
                })
                addView(actionButton("Maps setup", emphasis = false) {
                    showMapsKeyInstructions()
                })
            })
        }
    }

    private fun buildBridgeSection(): View {
        return section("Pebble bridge").apply {
            bridgeStateText = statusLine("Always enabled", granted = true)
            addView(bridgeStateText)
            addView(body("The companion listens for Billy watch requests whenever Android allows the app to run."))
        }
    }

    private fun buildWatchPromptSection(): View {
        return section("Watch test prompt").apply {
            watchPromptInput = EditText(this@MainActivity).apply {
                hint = "Type a prompt to send to Billy"
                textSize = 16f
                minLines = 2
                maxLines = 5
                isSingleLine = false
                setHorizontallyScrolling(false)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setTextColor(COLOR_TEXT)
                setHintTextColor(COLOR_MUTED)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(COLOR_FIELD, dp(10).toFloat(), COLOR_STROKE)
            }
            addView(watchPromptInput, matchWrap())
            addView(horizontalActions().apply {
                addView(actionButton("Send to watch") {
                    sendTypedPromptToWatch()
                })
            })
            watchPromptStatusText = muted("")
            addView(watchPromptStatusText)
        }
    }

    private fun buildAndroidAccessSection(): View {
        return section("Android access").apply {
            calendarPermissionRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            locationPermissionRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            photoPermissionRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(calendarPermissionRow)
            addView(locationPermissionRow)
            addView(photoPermissionRow)
            addView(actionButton("Remove Billy calendar ghosts", emphasis = false) {
                removeBillyCalendarGhosts()
            })
            calendarCleanupText = muted("")
            addView(calendarCleanupText)
        }
    }

    private fun buildGoogleAccessSection(): View {
        return section("Google account access").apply {
            googleAccessRows = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(googleAccessRows)
            googleAccessButton = actionButton("Grant Google account access") {
                showGoogleAccessDialog()
            }
            addView(googleAccessButton)
            addView(actionButton("Check Google API setup", emphasis = false) {
                checkGoogleApiSetup()
            })
            googleSetupCheckText = muted("")
            addView(googleSetupCheckText)
            addView(actionButton("Open Google Photos picker", emphasis = false) {
                openGooglePhotosPicker()
            })
            googlePhotosPickerText = muted("")
            addView(googlePhotosPickerText)
            googleActionText = muted("")
            addView(googleActionText)
            addView(actionButton("Clear Google grant cache", emphasis = false) {
                googleAuthStore.clear()
                pendingGoogleScopes = emptyList()
                googleApiAccessState = "local Google grant cache cleared"
                renderStatus()
            })
        }
    }

    private fun buildProfileSection(): View {
        return section("Billy profile and memory").apply {
            addView(body("Stored locally on this phone and included with Billy Companion requests when relevant."))
            profileStatusText = muted("")
            addView(profileStatusText)
            addView(horizontalActions().apply {
                addView(actionButton("Load Google profile") {
                    loadGoogleProfile()
                })
                addView(actionButton("Add memory", emphasis = false) {
                    showAddMemoryDialog()
                })
                addView(actionButton("Clear", emphasis = false) {
                    confirmClearProfile()
                })
            })
        }
    }

    private fun buildDiagnosticsSection(): View {
        return section("OAuth setup").apply {
            oauthIdentityText = TextView(this@MainActivity).apply {
                textSize = 15f
                setTextColor(COLOR_TEXT)
                typeface = Typeface.MONOSPACE
                setLineSpacing(0f, 1.08f)
            }
            addView(oauthIdentityText)
        }
    }

    private fun renderSettings(settings: CompanionSettings) {
        apiKeyInput.setText(settings.geminiApiKey)
        mapsApiKeyInput.setText(settings.googleMapsApiKey)
    }

    private fun readSettingsFromForm(): CompanionSettings {
        return CompanionSettings(
            geminiApiKey = apiKeyInput.text.toString(),
            googleMapsApiKey = mapsApiKeyInput.text.toString(),
            pebbleBridgeEnabled = true,
        )
    }

    private fun forceBridgeEnabled() {
        val settings = settingsStore.load()
        if (!settings.pebbleBridgeEnabled) {
            settingsStore.save(settings.copy(pebbleBridgeEnabled = true))
        }
    }

    private fun renderStatus() {
        forceBridgeEnabled()
        val settings = settingsStore.load()
        apiKeyStateText.text = if (settings.geminiApiKey.isBlank()) {
            "Missing. Stored locally after you save."
        } else {
            "Stored locally on this phone."
        }
        apiKeyStateText.setTextColor(if (settings.geminiApiKey.isBlank()) COLOR_WARNING else COLOR_MUTED)
        mapsKeyStateText.text = if (settings.googleMapsApiKey.isBlank()) {
            "Not set. Map cards use OpenStreetMap. Nearby search, Routes, Geocoding, and Time Zone need a Google Maps key."
        } else {
            "Stored locally on this phone for Google Maps Platform calls."
        }
        mapsKeyStateText.setTextColor(if (settings.googleMapsApiKey.isBlank()) COLOR_MUTED else COLOR_SUCCESS)
        bridgeStateText.text = "✓ Enabled"
        bridgeStateText.setTextColor(COLOR_SUCCESS)
        renderAndroidPermissionRows()
        renderGoogleAccessRows()
        renderProfileStatus()
        oauthIdentityText.text = oauthClientIdentity()
    }

    private fun renderProfileStatus() {
        profileStatusText.text = userProfileStore.load().statusSummary()
        profileStatusText.setTextColor(
            if (userProfileStore.load().hasPromptContext()) COLOR_SUCCESS else COLOR_MUTED,
        )
    }

    private fun renderAndroidPermissionRows() {
        calendarPermissionRow.removeAllViews()
        locationPermissionRow.removeAllViews()
        photoPermissionRow.removeAllViews()
        val calendarGranted = hasCalendarPermissions()
        calendarPermissionRow.addView(accessRow(
            title = "Android Calendar provider",
            detail = "Fallback read/write access for calendars synced to this phone.",
            granted = calendarGranted,
            grantText = "Grant",
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                REQUEST_CALENDAR_PERMISSIONS,
            )
        })

        val foregroundLocationGranted = hasForegroundLocationPermission()
        val backgroundLocationGranted = hasBackgroundLocationPermission()
        val locationGranted = foregroundLocationGranted && backgroundLocationGranted
        val locationDetail = when {
            !foregroundLocationGranted -> "Allows Billy to fetch local weather and local-place context from this phone's current location."
            !backgroundLocationGranted -> "Foreground location is granted. Set Location to Allow all the time so weather works while the phone is locked."
            else -> "Allows Billy to fetch local weather while the companion is open or running in the background."
        }
        locationPermissionRow.addView(accessRow(
            title = "Location for weather",
            detail = locationDetail,
            granted = locationGranted,
            grantText = if (foregroundLocationGranted && !backgroundLocationGranted) "Open settings" else "Grant",
        ) {
            requestLocationPermissions(foregroundLocationGranted, backgroundLocationGranted)
        })

        val photoStatus = AndroidPhotoTools.checkImageReadAccess(this)
        val photosGranted = photoStatus is PhotoPermissionStatus.Authorized &&
            photoStatus.accessLevel != PhotoAccessLevel.SelectedImages
        val photoDetail = when (photoStatus) {
            is PhotoPermissionStatus.Authorized -> when (photoStatus.accessLevel) {
                PhotoAccessLevel.FullImages,
                PhotoAccessLevel.LegacyExternalStorage -> "Allows Billy to attach local camera photos and screenshots for Gemini vision."
                PhotoAccessLevel.SelectedImages -> "Limited selected-photo access is active. Choose full Photos access for latest camera roll photos."
            }
            is PhotoPermissionStatus.NotAuthorized -> "Allows Billy to attach local camera photos and screenshots for Gemini vision."
        }
        photoPermissionRow.addView(accessRow(
            title = "Photos and screenshots",
            detail = photoDetail,
            granted = photosGranted,
            grantText = if (photoStatus is PhotoPermissionStatus.Authorized && photoStatus.accessLevel == PhotoAccessLevel.SelectedImages) {
                "Open settings"
            } else {
                "Grant"
            },
        ) {
            if (photoStatus is PhotoPermissionStatus.Authorized && photoStatus.accessLevel == PhotoAccessLevel.SelectedImages) {
                openAppSettings()
            } else {
                requestPermissions(
                    AndroidPhotoTools.imageReadPermissionsForRuntimeRequest().toTypedArray(),
                    REQUEST_PHOTO_PERMISSIONS,
                )
            }
        })
    }

    private fun renderGoogleAccessRows() {
        googleAccessRows.removeAllViews()
        val grantedScopes = googleAuthStore.grantedScopes()
        GOOGLE_SERVICES.forEach { service ->
            googleAccessRows.addView(googleStatusRow(service.label, grantedScopes.containsAll(service.scopes)))
        }
        val missingAny = GOOGLE_SERVICES.any { !grantedScopes.containsAll(it.scopes) }
        googleAccessButton.text = if (missingAny) "Grant Google account access" else "Manage Google account access"
        googleActionText.text = if (googleApiAccessState == "not requested") {
            ""
        } else {
            "Google OAuth: $googleApiAccessState"
        }
    }

    private fun testGeminiKey() {
        val settings = readSettingsFromForm()
        settingsStore.save(settings)
        apiKeyStateText.text = "Verifying Gemini key..."
        apiKeyStateText.setTextColor(COLOR_MUTED)
        Thread {
            val result = GeminiClient().testKey(settings.geminiApiKey)
            runOnUiThread {
                apiKeyStateText.text = when (result) {
                    is GeminiKeyTestResult.Passed -> "${result.message} Stored locally."
                    is GeminiKeyTestResult.Failed -> result.reason
                }
                apiKeyStateText.setTextColor(if (result is GeminiKeyTestResult.Passed) COLOR_SUCCESS else COLOR_WARNING)
            }
        }.start()
    }

    private fun removeBillyCalendarGhosts() {
        calendarCleanupText.text = "Removing Billy/Bobby calendar ghosts..."
        calendarCleanupText.setTextColor(COLOR_MUTED)
        Thread {
            val localResult = AndroidCalendarTools(this).deleteBillyLocalGhostEvents()
            val googleResult = GoogleCalendarApiTools(GoogleAccessTokenProvider(this)).deleteBillyGhostEvents()
            runOnUiThread {
                calendarCleanupText.text = "${localResult.summary}\n${googleResult.summary}"
                calendarCleanupText.setTextColor(
                    when (localResult) {
                        is DeleteCalendarEventsResult.Deleted -> COLOR_SUCCESS
                        else -> COLOR_WARNING
                    },
                )
                renderStatus()
            }
        }.start()
    }

    private fun checkGoogleApiSetup() {
        googleSetupCheckText.text = "Checking Google APIs..."
        googleSetupCheckText.setTextColor(COLOR_MUTED)
        Thread {
            val checks = GoogleApiSetupChecker(GoogleAccessTokenProvider(this)).checkAll()
            runOnUiThread {
                googleSetupCheckText.text = checks.joinToString("\n") { check ->
                    val marker = when (check.status) {
                        GoogleApiSetupStatus.OK -> "✓"
                        GoogleApiSetupStatus.NEEDS_GRANT -> "!"
                        GoogleApiSetupStatus.API_DISABLED -> "!"
                        GoogleApiSetupStatus.ERROR -> "!"
                    }
                    "$marker ${check.service}: ${check.detail}"
                }
                googleSetupCheckText.setTextColor(
                    if (checks.all { it.status == GoogleApiSetupStatus.OK }) COLOR_SUCCESS else COLOR_WARNING,
                )
                renderStatus()
            }
        }.start()
    }

    private fun openGooglePhotosPicker() {
        googlePhotosPickerText.text = "Creating Google Photos Picker session..."
        googlePhotosPickerText.setTextColor(COLOR_MUTED)
        Thread {
            val result = GooglePhotosApiTools(GoogleAccessTokenProvider(this)).createPickerSession()
            runOnUiThread {
                when (result) {
                    is GooglePhotosPickerCreateResult.Success -> {
                        GooglePhotosPickerStore(this).save(result.session)
                        googlePhotosPickerText.text = result.summary
                        googlePhotosPickerText.setTextColor(COLOR_SUCCESS)
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.session.pickerUri)))
                    }
                    is GooglePhotosPickerCreateResult.NeedsScope -> {
                        googlePhotosPickerText.text = result.summary
                        googlePhotosPickerText.setTextColor(COLOR_WARNING)
                    }
                    is GooglePhotosPickerCreateResult.Failed -> {
                        googlePhotosPickerText.text = result.reason
                        googlePhotosPickerText.setTextColor(COLOR_WARNING)
                    }
                }
                renderStatus()
            }
        }.start()
    }

    private fun loadGoogleProfile() {
        profileStatusText.text = "Loading Google profile..."
        profileStatusText.setTextColor(COLOR_MUTED)
        Thread {
            val result = GooglePeopleApiTools(GoogleAccessTokenProvider(this)).fetchOwnProfile()
            runOnUiThread {
                when (result) {
                    is GooglePeopleResult.Success -> {
                        val profile = userProfileStore.mergeGoogleProfile(result.payload)
                        profileStatusText.text = "Loaded Google profile.\n${profile.statusSummary()}"
                        profileStatusText.setTextColor(COLOR_SUCCESS)
                    }
                    is GooglePeopleResult.NeedsScope -> {
                        profileStatusText.text = "Google profile access needs consent. Grant it, then tap Load Google profile again."
                        profileStatusText.setTextColor(COLOR_WARNING)
                        authorizeGoogleApiAccess(result.scopes)
                        profileStatusText.text = "Google profile access needs consent. Grant it, then tap Load Google profile again."
                        profileStatusText.setTextColor(COLOR_WARNING)
                    }
                    is GooglePeopleResult.Rejected -> {
                        profileStatusText.text = result.reason
                        profileStatusText.setTextColor(COLOR_WARNING)
                    }
                    is GooglePeopleResult.Failed -> {
                        profileStatusText.text = result.reason
                        profileStatusText.setTextColor(COLOR_WARNING)
                    }
                }
                renderStatus()
            }
        }.start()
    }

    private fun showAddMemoryDialog() {
        val input = EditText(this).apply {
            hint = "Example: My dog is named Scout."
            textSize = 16f
            minLines = 3
            maxLines = 6
            isSingleLine = false
            setHorizontallyScrolling(false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(COLOR_FIELD, dp(10).toFloat(), COLOR_STROKE)
        }
        AlertDialog.Builder(this)
            .setTitle("Add Billy memory")
            .setMessage("Save a short durable fact or preference Billy should use in future answers.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val memory = userProfileStore.addMemory(input.text.toString(), source = "companion")
                profileStatusText.text = if (memory == null) {
                    "No memory saved."
                } else {
                    "Remembered: ${memory.fact}\n${userProfileStore.load().statusSummary()}"
                }
                profileStatusText.setTextColor(if (memory == null) COLOR_WARNING else COLOR_SUCCESS)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearProfile() {
        AlertDialog.Builder(this)
            .setTitle("Clear Billy profile?")
            .setMessage("This removes the local Google profile summary and Billy memories stored by the companion.")
            .setPositiveButton("Clear") { _, _ ->
                userProfileStore.clear()
                renderStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendTypedPromptToWatch() {
        val prompt = watchPromptInput.text.toString().trim()
        if (prompt.isBlank()) {
            watchPromptStatusText.text = "Enter a prompt first."
            watchPromptStatusText.setTextColor(COLOR_WARNING)
            return
        }
        watchPromptStatusText.text = "Sending prompt to Pebble..."
        watchPromptStatusText.setTextColor(COLOR_MUTED)
        lifecycleScope.launch {
            val pendingPromptStore = PendingWatchPromptStore(this@MainActivity)
            pendingPromptStore.save(prompt)
            val watch = PebbleWatchStore(this@MainActivity).lastWatch()
            val watches = watch?.let { listOf(it) }
            val targetLabel = watch?.value ?: "all connected watches"
            val sender = DefaultPebbleSender(this@MainActivity)
            try {
                val launchResults = if (watches == null) {
                    sender.startAppOnTheWatch(BillyPebbleProtocol.APP_UUID)
                } else {
                    sender.startAppOnTheWatch(BillyPebbleProtocol.APP_UUID, watches)
                }
                delay(WATCH_PROMPT_SEND_DELAY_MS)
                if (pendingPromptStore.peek() != prompt) {
                    watchPromptStatusText.text = "Prompt delivered after Billy opened on the watch."
                    watchPromptStatusText.setTextColor(COLOR_SUCCESS)
                    return@launch
                }
                val payload = mapOf(BillyPebbleProtocol.WATCH_PROMPT to PebbleDictionaryItem.Text(prompt.take(WATCH_PROMPT_MAX_LENGTH)))
                val sendResults = if (watches == null) {
                    sender.sendDataToPebble(BillyPebbleProtocol.APP_UUID, payload)
                } else {
                    sender.sendDataToPebble(BillyPebbleProtocol.APP_UUID, payload, watches)
                }
                if (!sendResults.isNullOrEmpty()) {
                    pendingPromptStore.clearIf(prompt)
                }
                val sendSucceeded = sendResults.allSucceeded()
                watchPromptStatusText.text = if (sendSucceeded) {
                    "Prompt sent to $targetLabel.\n${sendResults.describePebbleResults("Send")}"
                } else {
                    "Prompt queued for $targetLabel.\n${launchResults.describePebbleResults("Launch")}\n${sendResults.describePebbleResults("Send")}"
                }
                watchPromptStatusText.setTextColor(if (sendSucceeded) COLOR_SUCCESS else COLOR_WARNING)
            } catch (e: Exception) {
                watchPromptStatusText.text = "Prompt send failed: ${e.message ?: e.javaClass.simpleName}"
                watchPromptStatusText.setTextColor(COLOR_WARNING)
            } finally {
                sender.close()
            }
        }
    }

    private fun showGeminiKeyInstructions() {
        AlertDialog.Builder(this)
            .setTitle("Gemini API key")
            .setMessage(
                "1. Open Google AI Studio:\n$GEMINI_API_KEY_URL\n\n" +
                    "2. Sign in with the Google account that should pay for Gemini API usage.\n\n" +
                    "3. Create a Gemini API key and copy it.\n\n" +
                    "4. Paste that same key into Billy Companion and the Billy watch settings.\n\n" +
                    "The key is stored locally. Billy builds do not include a shared Gemini API key.",
            )
            .setPositiveButton("Open AI Studio") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GEMINI_API_KEY_URL)))
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun showMapsKeyInstructions() {
        AlertDialog.Builder(this)
            .setTitle("Google Maps API key")
            .setMessage(
                "This is optional and separate from the Gemini key.\n\n" +
                    "1. Open Google Cloud Console Maps credentials.\n\n" +
                    "2. Create an API key in the project that should pay for Maps usage.\n\n" +
                    "3. Enable billing on that same project. Billing on a different project will not satisfy the pasted key.\n\n" +
                    "4. Enable Places API, Routes API, Geocoding API, Time Zone API, and Maps Static API on that same project.\n\n" +
                    "5. Restrict the key to those APIs when possible, then paste it here.\n\n" +
                    "Billy stores this key locally. It is not bundled into the app.",
            )
            .setPositiveButton("Open Console") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GOOGLE_MAPS_API_KEY_URL)))
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun showGoogleAccessDialog() {
        val grantedScopes = googleAuthStore.grantedScopes()
        val checked = GOOGLE_SERVICES.map { service ->
            !grantedScopes.containsAll(service.scopes) && service.defaultCheckedWhenMissing
        }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Grant Google access")
            .setMultiChoiceItems(
                GOOGLE_SERVICES.map { it.label }.toTypedArray(),
                checked,
            ) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Grant") { _, _ ->
                val scopes = GOOGLE_SERVICES
                    .filterIndexed { index, _ -> checked[index] }
                    .flatMap { it.scopes }
                    .distinct()
                if (scopes.isEmpty()) {
                    googleApiAccessState = "no Google services selected"
                    renderStatus()
                } else {
                    authorizeGoogleApiAccess(scopes)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun authorizeGoogleApiAccess(scopes: List<String>) {
        pendingGoogleScopes = scopes
        googleApiAccessState = "requesting access..."
        renderStatus()
        googleApiAuthorization.requestAccess(scopes) { result ->
            runOnUiThread {
                handleGoogleAuthorizationResult(result, allowConsentUi = true)
            }
        }
    }

    private fun handleGoogleAuthorizationResult(
        result: GoogleApiAuthorizationResult,
        allowConsentUi: Boolean,
    ) {
        when (result) {
            is GoogleApiAuthorizationResult.Authorized -> {
                val scopesToSave = result.grantedScopes.filter { it.isNotBlank() }.distinct()
                googleAuthStore.saveGrant(
                    scopes = scopesToSave,
                    accessToken = result.accessToken,
                )
                val tokenState = if (result.accessToken.isNullOrBlank()) "no access token returned" else "access token ready"
                googleApiAccessState = "$tokenState; ${scopesToSave.size} scopes saved"
                pendingGoogleScopes = emptyList()
                renderStatus()
            }
            is GoogleApiAuthorizationResult.NeedsUserConsent -> {
                if (!allowConsentUi) {
                    googleApiAccessState = "Google still requires consent after returning from account picker. Check OAuth package/SHA-1, enabled APIs, allowed users, and requested scopes."
                    renderStatus()
                    return
                }
                googleApiAccessState = "waiting for Google consent"
                renderStatus()
                try {
                    googleAuthorizationLauncher.launch(
                        IntentSenderRequest.Builder(result.pendingIntent.intentSender).build(),
                    )
                } catch (e: Exception) {
                    googleApiAccessState = "failed to launch Google consent: ${e.message ?: e.javaClass.simpleName}"
                    renderStatus()
                }
            }
            is GoogleApiAuthorizationResult.Failed -> {
                googleApiAccessState = "failed: ${result.reason}"
                renderStatus()
            }
        }
    }

    private fun handleGoogleAuthorizationActivityResult(resultCode: Int, data: Intent?) {
        val extracted = googleApiAuthorization.completeAccessRequest(data)
        if (resultCode != RESULT_OK) {
            val detail = when (extracted) {
                is GoogleApiAuthorizationResult.Failed -> " ${extracted.reason}"
                is GoogleApiAuthorizationResult.NeedsUserConsent -> " Google still requires consent after the account picker."
                is GoogleApiAuthorizationResult.Authorized -> " Authorization data was present despite canceled result; saving it."
            }
            if (extracted is GoogleApiAuthorizationResult.Authorized) {
                handleGoogleAuthorizationResult(extracted, allowConsentUi = false)
                return
            }
            googleApiAccessState = "Google consent did not complete. resultCode=$resultCode.$detail"
            renderStatus()
            return
        }
        handleGoogleAuthorizationResult(
            extracted,
            allowConsentUi = false,
        )
    }

    @Deprecated("Existing runtime permission path; Google authorization already uses Activity Result APIs.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (
            requestCode == REQUEST_CALENDAR_PERMISSIONS ||
            requestCode == REQUEST_LOCATION_PERMISSIONS ||
            requestCode == REQUEST_BACKGROUND_LOCATION_PERMISSION ||
            requestCode == REQUEST_PHOTO_PERMISSIONS
        ) {
            renderStatus()
        }
    }

    private fun hasCalendarPermissions(): Boolean {
        return checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasForegroundLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissions(
        foregroundLocationGranted: Boolean = hasForegroundLocationPermission(),
        backgroundLocationGranted: Boolean = hasBackgroundLocationPermission(),
    ) {
        if (!foregroundLocationGranted) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSIONS,
            )
            return
        }
        if (!backgroundLocationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                AlertDialog.Builder(this)
                    .setTitle("Allow locked-phone weather")
                    .setMessage("Open Android settings, choose Permissions > Location, then select Allow all the time.")
                    .setPositiveButton("Open settings") { _, _ -> openAppSettings() }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    REQUEST_BACKGROUND_LOCATION_PERMISSION,
                )
            }
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun oauthClientIdentity(): String {
        return "Package\n$packageName\n\nSHA-1\n${installedSigningSha1()}"
    }

    private fun appVersionLabel(): String {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "v${info.versionName} (${versionCodeLabel()})"
        } catch (_: Exception) {
            ""
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCodeLabel(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    @Suppress("DEPRECATION")
    private fun installedSigningSha1(): String {
        return try {
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo
                    ?.apkContentsSigners
                    .orEmpty()
            } else {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
            }
            val certificate = signatures.firstOrNull()?.toByteArray() ?: return "unknown"
            MessageDigest.getInstance("SHA-1")
                .digest(certificate)
                .joinToString(":") { byte -> "%02X".format(byte) }
        } catch (e: Exception) {
            "unavailable (${e.message ?: e.javaClass.simpleName})"
        }
    }

    private fun section(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle(title))
        }
    }

    private fun apiKeyLabel(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
            setPadding(0, dp(8), 0, dp(6))
        }
    }

    private fun sectionTitle(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
            setPadding(0, 0, 0, dp(8))
        }
    }

    private fun title(textValue: String, size: Float): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
        }
    }

    private fun body(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 15f
            setTextColor(COLOR_MUTED)
            setLineSpacing(0f, 1.08f)
        }
    }

    private fun muted(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(8), 0, dp(2))
        }
    }

    private fun statusLine(textValue: String, granted: Boolean): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (granted) COLOR_SUCCESS else COLOR_WARNING)
            setPadding(0, 0, 0, dp(6))
        }
    }

    private fun googleStatusRow(label: String, granted: Boolean): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 15f
                setTextColor(COLOR_TEXT)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = if (granted) "✓ Granted" else "Grant needed"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (granted) COLOR_SUCCESS else COLOR_WARNING)
            })
        }
    }

    private fun accessRow(
        title: String,
        detail: String,
        granted: Boolean,
        grantText: String,
        onGrant: () -> Unit,
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(10))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(COLOR_TEXT)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (granted) {
                    addView(TextView(this@MainActivity).apply {
                        text = "✓ Granted"
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(COLOR_SUCCESS)
                    })
                } else {
                    addView(actionButton(grantText) { onGrant() })
                }
            })
            addView(body(detail))
        }
    }

    private fun horizontalActions(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
    }

    private fun actionButton(textValue: String, emphasis: Boolean = true, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = textValue
            textSize = 14f
            isAllCaps = false
            setTextColor(if (emphasis) COLOR_ACCENT_TEXT else COLOR_TEXT)
            background = rounded(if (emphasis) COLOR_ACCENT else COLOR_FIELD, dp(10).toFloat(), if (emphasis) COLOR_ACCENT else COLOR_STROKE)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
    }

    private fun sectionDivider(): View {
        return View(this).apply {
            setBackgroundColor(COLOR_STROKE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(0, dp(20), 0, dp(20))
            }
        }
    }

    private fun spacer(height: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(height))
        }
    }

    private fun rounded(color: Int, radius: Float, strokeColor: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            strokeColor?.let { setStroke(dp(1), it) }
        }
    }

    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun Map<WatchIdentifier, TransmissionResult>?.allSucceeded(): Boolean {
        return !isNullOrEmpty() && values.all { it is TransmissionResult.Success }
    }

    private fun Map<WatchIdentifier, TransmissionResult>?.describePebbleResults(action: String): String {
        if (this == null) {
            return "$action: no PebbleKit result"
        }
        if (isEmpty()) {
            return "$action: no watch result"
        }
        return "$action: " + entries.joinToString { (watch, result) -> "${watch.value}=$result" }
    }

    companion object {
        private const val REQUEST_CALENDAR_PERMISSIONS = 1001
        private const val REQUEST_PHOTO_PERMISSIONS = 1002
        private const val REQUEST_LOCATION_PERMISSIONS = 1003
        private const val REQUEST_BACKGROUND_LOCATION_PERMISSION = 1004
        private const val WATCH_PROMPT_MAX_LENGTH = 240
        private const val WATCH_PROMPT_SEND_DELAY_MS = 900L
        private const val GEMINI_API_KEY_URL = "https://aistudio.google.com/app/apikey"
        private const val GOOGLE_MAPS_API_KEY_URL = "https://console.cloud.google.com/google/maps-apis/credentials"
        private val COLOR_PANEL = Color.rgb(17, 24, 39)
        private val COLOR_FIELD = Color.rgb(31, 41, 55)
        private val COLOR_STROKE = Color.rgb(75, 85, 99)
        private val COLOR_TEXT = Color.rgb(248, 250, 252)
        private val COLOR_MUTED = Color.rgb(203, 213, 225)
        private val COLOR_SUCCESS = Color.rgb(74, 222, 128)
        private val COLOR_WARNING = Color.rgb(251, 191, 36)
        private val COLOR_ACCENT = Color.rgb(170, 255, 255)
        private val COLOR_ACCENT_TEXT = Color.rgb(15, 23, 42)
        private val GOOGLE_SERVICES = listOf(
            GoogleService("Calendar", GoogleApiScopes.calendar),
            GoogleService("Tasks", GoogleApiScopes.tasks),
            GoogleService("Gmail", GoogleApiScopes.gmail),
            GoogleService("Drive", GoogleApiScopes.drive),
            GoogleService("Contacts", GoogleApiScopes.people),
            GoogleService("Docs", GoogleApiScopes.docs),
            GoogleService("Sheets", GoogleApiScopes.sheets),
            GoogleService("Slides", GoogleApiScopes.slides),
            GoogleService("Forms", GoogleApiScopes.forms, defaultCheckedWhenMissing = false),
            GoogleService("Google Photos APIs", GoogleApiScopes.photos, defaultCheckedWhenMissing = false),
        )
        private val COLOR_STATUS_SCRIM = Color.rgb(3, 7, 18)
    }
}

private data class GoogleService(
    val label: String,
    val scopes: List<String>,
    val defaultCheckedWhenMissing: Boolean = true,
)
