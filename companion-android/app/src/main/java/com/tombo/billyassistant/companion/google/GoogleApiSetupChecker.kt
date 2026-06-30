package com.tombo.billyassistant.companion.google

import com.tombo.billyassistant.companion.auth.GoogleAccessTokenProvider
import com.tombo.billyassistant.companion.auth.GoogleAccessTokenResult
import com.tombo.billyassistant.companion.auth.GoogleApiScopes

class GoogleApiSetupChecker(
    private val tokenProvider: GoogleAccessTokenProvider,
    private val http: GoogleApiHttp = GoogleApiHttp(),
) {
    fun checkAll(): List<GoogleApiSetupCheck> {
        return API_PROBES.map { probe -> check(probe) }
    }

    private fun check(probe: GoogleApiProbe): GoogleApiSetupCheck {
        return when (val token = tokenProvider.getAccessToken(GoogleApiScopes.identity + probe.scopes)) {
            is GoogleAccessTokenResult.NeedsUserGrant -> GoogleApiSetupCheck(
                service = probe.label,
                status = GoogleApiSetupStatus.NEEDS_GRANT,
                detail = "Grant access in Billy Companion.",
            )
            is GoogleAccessTokenResult.Failed -> GoogleApiSetupCheck(
                service = probe.label,
                status = GoogleApiSetupStatus.ERROR,
                detail = token.reason,
            )
            is GoogleAccessTokenResult.Authorized -> when (val response = http.get(probe.url, token.accessToken)) {
                is GoogleHttpResult.Success -> GoogleApiSetupCheck(
                    service = probe.label,
                    status = GoogleApiSetupStatus.OK,
                    detail = "API reachable.",
                )
                is GoogleHttpResult.HttpError -> {
                    val status = when {
                        response.reason.contains("has not been used", ignoreCase = true) ||
                            response.reason.contains("is disabled", ignoreCase = true) -> GoogleApiSetupStatus.API_DISABLED
                        response.responseCode == 404 && probe.missingResourceMeansApiReachable -> GoogleApiSetupStatus.OK
                        response.responseCode == 400 && probe.missingResourceMeansApiReachable -> GoogleApiSetupStatus.OK
                        response.responseCode == 403 && probe.missingResourceMeansApiReachable &&
                            response.reason.contains("permission", ignoreCase = true) -> GoogleApiSetupStatus.OK
                        else -> GoogleApiSetupStatus.ERROR
                    }
                    GoogleApiSetupCheck(
                        service = probe.label,
                        status = status,
                        detail = if (status == GoogleApiSetupStatus.OK) {
                            "API reachable; probe document is intentionally missing."
                        } else {
                            "HTTP ${response.responseCode}: ${response.reason}"
                        },
                    )
                }
                is GoogleHttpResult.Failed -> GoogleApiSetupCheck(
                    service = probe.label,
                    status = GoogleApiSetupStatus.ERROR,
                    detail = response.reason,
                )
            }
        }
    }

    private data class GoogleApiProbe(
        val label: String,
        val scopes: List<String>,
        val url: String,
        val missingResourceMeansApiReachable: Boolean = false,
    )

    companion object {
        private val API_PROBES = listOf(
            GoogleApiProbe(
                label = "Calendar",
                scopes = listOf(GoogleApiScopes.CALENDAR),
                url = "https://www.googleapis.com/calendar/v3/users/me/calendarList?maxResults=1",
            ),
            GoogleApiProbe(
                label = "Tasks",
                scopes = listOf(GoogleApiScopes.TASKS),
                url = "https://tasks.googleapis.com/tasks/v1/users/@me/lists?maxResults=1",
            ),
            GoogleApiProbe(
                label = "Gmail",
                scopes = listOf(GoogleApiScopes.GMAIL_READONLY),
                url = "https://gmail.googleapis.com/gmail/v1/users/me/profile",
            ),
            GoogleApiProbe(
                label = "Drive",
                scopes = listOf(GoogleApiScopes.DRIVE_METADATA_READONLY),
                url = "https://www.googleapis.com/drive/v3/files?pageSize=1&fields=files(id,name)",
            ),
            GoogleApiProbe(
                label = "People / Contacts",
                scopes = listOf(GoogleApiScopes.CONTACTS_READONLY),
                url = "https://people.googleapis.com/v1/people/me/connections?pageSize=1&personFields=names,emailAddresses",
            ),
            GoogleApiProbe(
                label = "Docs",
                scopes = listOf(GoogleApiScopes.DOCS, GoogleApiScopes.DOCS_READONLY),
                url = "https://docs.googleapis.com/v1/documents/__billy_api_probe__",
                missingResourceMeansApiReachable = true,
            ),
            GoogleApiProbe(
                label = "Sheets",
                scopes = listOf(GoogleApiScopes.SHEETS, GoogleApiScopes.SHEETS_READONLY),
                url = "https://sheets.googleapis.com/v4/spreadsheets/__billy_api_probe__?fields=spreadsheetId",
                missingResourceMeansApiReachable = true,
            ),
            GoogleApiProbe(
                label = "Slides",
                scopes = listOf(GoogleApiScopes.SLIDES, GoogleApiScopes.SLIDES_READONLY),
                url = "https://slides.googleapis.com/v1/presentations/__billy_api_probe__?fields=presentationId",
                missingResourceMeansApiReachable = true,
            ),
            GoogleApiProbe(
                label = "Forms",
                scopes = listOf(GoogleApiScopes.FORMS_BODY_READONLY),
                url = "https://forms.googleapis.com/v1/forms/__billy_api_probe__",
                missingResourceMeansApiReachable = true,
            ),
            GoogleApiProbe(
                label = "Google Photos Library",
                scopes = listOf(GoogleApiScopes.PHOTOS_LIBRARY_APP_CREATED_READONLY),
                url = "https://photoslibrary.googleapis.com/v1/mediaItems?pageSize=1",
            ),
            GoogleApiProbe(
                label = "Google Photos Picker",
                scopes = listOf(GoogleApiScopes.PHOTOS_PICKER_READONLY),
                url = "https://photospicker.googleapis.com/v1/sessions/__billy_api_probe__",
                missingResourceMeansApiReachable = true,
            ),
        )
    }
}

data class GoogleApiSetupCheck(
    val service: String,
    val status: GoogleApiSetupStatus,
    val detail: String,
)

enum class GoogleApiSetupStatus {
    OK,
    NEEDS_GRANT,
    API_DISABLED,
    ERROR,
}
