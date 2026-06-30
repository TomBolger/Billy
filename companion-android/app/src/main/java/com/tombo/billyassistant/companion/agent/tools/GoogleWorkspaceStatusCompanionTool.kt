package com.tombo.billyassistant.companion.agent.tools

import com.tombo.billyassistant.companion.auth.GoogleAccessTokenProvider
import com.tombo.billyassistant.companion.auth.GoogleAccessTokenResult
import org.json.JSONArray
import org.json.JSONObject

class GoogleWorkspaceStatusCompanionTool(
    private val tokenProvider: GoogleAccessTokenProvider? = null,
    private val mapsApiKeyProvider: () -> String = { "" },
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        JSONObject()
            .put("name", "get_google_api_status")
            .put("description", "Report OAuth readiness for bounded Google Calendar, Tasks, Gmail, Drive, Contacts, Docs, Sheets, Slides, Forms, and Photos API capabilities.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "service" to stringSchema("Optional service filter: calendar, keep, tasks, drive, contacts, docs, sheets, slides, forms, gmail, maps, or photos."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "get_google_api_contracts")
            .put("description", "Describe the bounded Google API contracts Billy can use once OAuth or optional local API keys are connected.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "service" to stringSchema("Optional service filter: calendar, keep, tasks, drive, contacts, docs, sheets, slides, forms, gmail, maps, or photos."),
                    ),
                ),
            ),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        return when (name) {
            "get_google_api_status" -> CompanionToolExecution(status(args))
            "get_google_api_contracts" -> CompanionToolExecution(contracts(args))
            else -> null
        }
    }

    private fun status(args: JSONObject): JSONObject {
        val services = filteredContracts(args).map { contract ->
            contract.toJson(includeOperations = true)
                .put("oauth_status", if (contract.usesApiKey) apiKeyStatus(contract) else oauthStatus(contract.allScopes()))
        }
        return JSONObject()
            .put("status", "ok")
            .put("summary", "Reported Google API OAuth readiness.")
            .put("services", JSONArray(services))
    }

    private fun contracts(args: JSONObject): JSONObject {
        val services = filteredContracts(args).map { contract ->
            contract.toJson(includeOperations = true)
        }
        return JSONObject()
            .put("status", "ok")
            .put("summary", "Reported bounded Google API contracts.")
            .put("services", JSONArray(services))
    }

    private fun filteredContracts(args: JSONObject): List<GoogleApiContract> {
        val service = args.optString("service").trim().lowercase()
        return CONTRACTS.filter { contract -> service.isBlank() || contract.service == service }
    }

    private fun oauthStatus(requiredScopes: Set<String>): JSONObject {
        if (requiredScopes.isEmpty()) {
            return JSONObject()
                .put("status", "unavailable")
                .put("summary", "No personal OAuth scope is available for this service.")
                .put("required_scopes", JSONArray())
        }
        val provider = tokenProvider ?: return JSONObject()
            .put("status", "needs_sign_in")
            .put("summary", "Google OAuth provider is not connected in this companion app.")
            .put("required_scopes", JSONArray(requiredScopes))

        return when (val result = provider.getAccessToken(requiredScopes)) {
            is GoogleAccessTokenResult.Authorized -> JSONObject()
                .put("status", if (result.accessToken.isBlank()) "needs_sign_in" else "ok")
                .put("summary", if (result.accessToken.isBlank()) "Google OAuth returned an empty access token." else "OAuth token is available.")
                .put("required_scopes", JSONArray(requiredScopes))
            is GoogleAccessTokenResult.NeedsUserGrant -> JSONObject()
                .put("status", "needs_scope")
                .put("summary", "Grant Google API access in the companion app.")
                .put("required_scopes", JSONArray(requiredScopes))
                .put("missing_scopes", JSONArray(result.scopes))
            is GoogleAccessTokenResult.Failed -> JSONObject()
                .put("status", "error")
                .put("summary", result.reason)
                .put("required_scopes", JSONArray(requiredScopes))
        }
    }

    private fun apiKeyStatus(contract: GoogleApiContract): JSONObject {
        val hasKey = mapsApiKeyProvider().isNotBlank()
        return JSONObject()
            .put("status", if (hasKey) "ok" else "needs_api_key")
            .put("summary", if (hasKey) "${contract.summary} API key is stored locally." else "${contract.summary} Add a Maps key in Billy Companion.")
            .put("required_scopes", JSONArray())
    }

    private data class GoogleApiContract(
        val service: String,
        val summary: String,
        val operations: List<GoogleApiOperation>,
        val usesApiKey: Boolean = false,
    ) {
        fun allScopes(): Set<String> = operations.flatMap { it.requiredScopes }.toSet()

        fun toJson(includeOperations: Boolean): JSONObject {
            val json = JSONObject()
                .put("service", service)
                .put("summary", summary)
                .put("required_scopes", JSONArray(allScopes()))
            if (includeOperations) {
                json.put(
                    "operations",
                    JSONArray().also { array ->
                        operations.forEach { operation -> array.put(operation.toJson()) }
                    },
                )
            }
            return json
        }
    }

    private data class GoogleApiOperation(
        val name: String,
        val toolName: String?,
        val support: String,
        val requiredScopes: Set<String>,
        val limits: String,
    ) {
        fun toJson(): JSONObject {
            return JSONObject()
                .put("name", name)
                .put("tool_name", toolName)
                .put("support", support)
                .put("required_scopes", JSONArray(requiredScopes))
                .put("limits", limits)
        }
    }

    private companion object {
        private const val SCOPE_TASKS = "https://www.googleapis.com/auth/tasks"
        private const val SCOPE_CALENDAR = "https://www.googleapis.com/auth/calendar"
        private const val SCOPE_DRIVE_METADATA_READONLY = "https://www.googleapis.com/auth/drive.metadata.readonly"
        private const val SCOPE_DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
        private const val SCOPE_CONTACTS_READONLY = "https://www.googleapis.com/auth/contacts.readonly"
        private const val SCOPE_DOCS = "https://www.googleapis.com/auth/documents"
        private const val SCOPE_DOCS_READONLY = "https://www.googleapis.com/auth/documents.readonly"
        private const val SCOPE_SHEETS_READONLY = "https://www.googleapis.com/auth/spreadsheets.readonly"
        private const val SCOPE_SHEETS = "https://www.googleapis.com/auth/spreadsheets"
        private const val SCOPE_SLIDES_READONLY = "https://www.googleapis.com/auth/presentations.readonly"
        private const val SCOPE_SLIDES = "https://www.googleapis.com/auth/presentations"
        private const val SCOPE_FORMS_BODY_READONLY = "https://www.googleapis.com/auth/forms.body.readonly"
        private const val SCOPE_GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
        private const val SCOPE_GMAIL_COMPOSE = "https://www.googleapis.com/auth/gmail.compose"
        private const val SCOPE_GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send"
        private const val SCOPE_PHOTOS_PICKER_READONLY = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"
        private const val SCOPE_PHOTOS_LIBRARY_APP_CREATED_READONLY = "https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata"

        private val CONTRACTS = listOf(
            GoogleApiContract(
                service = "calendar",
                summary = "Calendar has API-backed create and list operations. Billy no longer creates Android-provider fallback events.",
                operations = listOf(
                    GoogleApiOperation(
                        name = "List calendar events",
                        toolName = "list_calendar_events",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_CALENDAR),
                        limits = "Returns a short time-windowed event list; Android provider is read-only fallback.",
                    ),
                    GoogleApiOperation(
                        name = "Create calendar event",
                        toolName = "create_calendar_event",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_CALENDAR),
                        limits = "Requires title, start, and end. Can add a Google Meet link. Success requires Google API readback.",
                    ),
                    GoogleApiOperation(
                        name = "Calendar free/busy",
                        toolName = "query_calendar_freebusy",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_CALENDAR),
                        limits = "Returns busy blocks across visible or selected calendars for scheduling.",
                    ),
                    GoogleApiOperation(
                        name = "Find availability",
                        toolName = "find_calendar_availability",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_CALENDAR),
                        limits = "Computes open slots from Google Calendar free/busy data.",
                    ),
                ),
            ),
            GoogleApiContract(
                service = "keep",
                summary = "Keep is unavailable through personal Google OAuth; Billy does not create substitutes for Keep requests.",
                operations = listOf(
                    GoogleApiOperation(
                        name = "Keep personal OAuth",
                        toolName = "create_google_keep_note",
                        support = "unavailable",
                        requiredScopes = emptySet(),
                        limits = "Google documents Keep as an enterprise/admin API; normal personal OAuth returns invalid_scope.",
                    ),
                ),
            ),
            GoogleApiContract(
                service = "tasks",
                summary = "Tasks has API-backed list, create, and complete operations.",
                operations = listOf(
                    GoogleApiOperation(
                        name = "List tasks",
                        toolName = "list_google_tasks",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_TASKS),
                        limits = "Returns at most 20 tasks per call and omits hidden tasks.",
                    ),
                    GoogleApiOperation(
                        name = "Create task",
                        toolName = "create_google_task",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_TASKS),
                        limits = "Requires a title; title and notes are capped at 4096 characters.",
                    ),
                    GoogleApiOperation(
                        name = "Complete task",
                        toolName = "complete_google_task",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_TASKS),
                        limits = "Ambiguous matches ask with the watch picker before mutating.",
                    ),
                ),
            ),
            GoogleApiContract(
                service = "drive",
                summary = "Drive can search file metadata and full text for readable files.",
                operations = listOf(
                    GoogleApiOperation(
                        name = "Drive metadata search",
                        toolName = "search_google_drive",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_DRIVE_METADATA_READONLY, SCOPE_DRIVE_READONLY),
                        limits = "Returns small result sets with file names, types, links, owners, and modified times.",
                    ),
                    GoogleApiOperation(
                        name = "Recent Drive files",
                        toolName = "list_recent_google_drive_files",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_DRIVE_METADATA_READONLY, SCOPE_DRIVE_READONLY),
                        limits = "Returns the most recently modified readable files.",
                    ),
                ),
            ),
            GoogleApiContract(
                service = "contacts",
                summary = "Contacts can resolve names to email addresses for Gmail and can be searched directly.",
                operations = listOf(
                    GoogleApiOperation(
                        name = "Search contacts",
                        toolName = "search_google_contacts",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_CONTACTS_READONLY),
                        limits = "Reads Google Contacts and asks with the watch picker when a recipient is ambiguous.",
                    ),
                    GoogleApiOperation(
                        name = "Resolve contact email",
                        toolName = "resolve_google_contact_email",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_CONTACTS_READONLY),
                        limits = "Never invents an address; returns needs-clarification or rejected when uncertain.",
                    ),
                ),
            ),
            GoogleApiContract(
                service = "docs",
                summary = "Docs can read text and create new Google Docs.",
                operations = listOf(
                    GoogleApiOperation(
                        name = "Read Google Doc",
                        toolName = "read_google_doc",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_DRIVE_METADATA_READONLY, SCOPE_DOCS_READONLY),
                        limits = "Reads a bounded text excerpt by file id or Drive search query.",
                    ),
                    GoogleApiOperation(
                        name = "Create Google Doc",
                        toolName = "create_google_doc",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_DOCS),
                        limits = "Creates a titled document and optional body text.",
                    ),
                ),
            ),
            GoogleApiContract(
                service = "sheets",
                summary = "Sheets can read small ranges for watch summaries.",
                operations = listOf(
                    GoogleApiOperation(
                        name = "Read Sheet range",
                        toolName = "read_google_sheet",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_DRIVE_METADATA_READONLY, SCOPE_SHEETS_READONLY),
                        limits = "Reads a bounded A1 range or the first rows of the first sheet.",
                    ),
                    GoogleApiOperation(
                        name = "Create Google Sheet",
                        toolName = "create_google_sheet",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_SHEETS),
                        limits = "Creates a blank spreadsheet by title.",
                    ),
                ),
            ),
            GoogleApiContract(
                service = "slides",
                summary = "Slides can read visible text from a deck.",
                operations = listOf(
                    GoogleApiOperation(
                        name = "Read Slides text",
                        toolName = "read_google_slides",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_DRIVE_METADATA_READONLY, SCOPE_SLIDES_READONLY),
                        limits = "Reads bounded visible shape text; images are not described yet.",
                    ),
                    GoogleApiOperation(
                        name = "Create Google Slides",
                        toolName = "create_google_slides",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_SLIDES),
                        limits = "Creates a blank presentation by title.",
                    ),
                ),
            ),
            GoogleApiContract(
                service = "forms",
                summary = "Forms can read form structure.",
                operations = listOf(
                    GoogleApiOperation(
                        name = "Read Google Form",
                        toolName = "read_google_form",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_FORMS_BODY_READONLY),
                        limits = "Reads title, description, and a bounded list of questions.",
                    ),
                ),
            ),
            GoogleApiContract(
                service = "gmail",
                summary = "Gmail can search messages, create drafts, and send after watch confirmation.",
                operations = listOf(
                    GoogleApiOperation(
                        name = "Gmail profile/status",
                        toolName = "prepare_gmail_send",
                        support = "implemented",
                        requiredScopes = setOf(SCOPE_GMAIL_SEND),
                        limits = "Uses Google identity email to resolve me/myself before showing the send confirmation.",
                    ),
                    GoogleApiOperation(
                        name = "Gmail draft compose",
                        toolName = "create_gmail_draft",
                        support = "implemented",
                        requiredScopes = setOf(SCOPE_GMAIL_COMPOSE),
                        limits = "Draft-only path is reserved for explicit draft requests.",
                    ),
                    GoogleApiOperation(
                        name = "Gmail confirmed send",
                        toolName = "prepare_gmail_send",
                        support = "implemented",
                        requiredScopes = setOf(SCOPE_GMAIL_SEND),
                        limits = "Billy shows a watch confirmation card and sends only after the user selects Send.",
                    ),
                    GoogleApiOperation(
                        name = "Contact recipient resolution",
                        toolName = "prepare_gmail_send",
                        support = "api_backed",
                        requiredScopes = setOf(SCOPE_GMAIL_SEND, SCOPE_CONTACTS_READONLY),
                        limits = "Named recipients are resolved through Google Contacts before the send confirmation card.",
                    ),
                ),
            ),
            GoogleApiContract(
                service = "maps",
                summary = "Map cards can use OpenStreetMap without a Maps key. Google Maps Platform tools use the optional local Maps API key setting, not OAuth.",
                usesApiKey = true,
                operations = listOf(
                    GoogleApiOperation(
                        name = "OpenStreetMap map card",
                        toolName = "show_map_directions",
                        support = "no_key_available",
                        requiredScopes = emptySet(),
                        limits = "Used only when no Google Maps API key is pasted. It does not replace a failing Google Maps key.",
                    ),
                    GoogleApiOperation(
                        name = "Nearby Google Places",
                        toolName = "find_nearby_google_places",
                        support = "api_key_backed",
                        requiredScopes = emptySet(),
                        limits = "Requires all-time Android location permission and a Maps key with Places API enabled. Searches are location-restricted around the phone.",
                    ),
                    GoogleApiOperation(
                        name = "Search Google Places",
                        toolName = "search_google_places",
                        support = "api_key_backed",
                        requiredScopes = emptySet(),
                        limits = "Requires a Maps key with Places API enabled. Use only for explicit city, address, or named-area searches.",
                    ),
                    GoogleApiOperation(
                        name = "Google Routes",
                        toolName = "get_google_route",
                        support = "api_key_backed",
                        requiredScopes = emptySet(),
                        limits = "Requires a Maps key with Routes API enabled.",
                    ),
                    GoogleApiOperation(
                        name = "Geocoding and Time Zone",
                        toolName = "geocode_google_maps",
                        support = "api_key_backed",
                        requiredScopes = emptySet(),
                        limits = "Requires a Maps key with Geocoding and Time Zone APIs enabled.",
                    ),
                ),
            ),
            GoogleApiContract(
                service = "photos",
                summary = "Google Photos support is experimental and bounded by Google's public APIs.",
                operations = listOf(
                    GoogleApiOperation(
                        name = "Google Photos Picker selected media",
                        toolName = "show_google_photos_picker_selection",
                        support = "api_backed_selected_media_only",
                        requiredScopes = setOf(SCOPE_PHOTOS_PICKER_READONLY),
                        limits = "Requires opening the Google Photos Picker on the phone and selecting media. Billy can only retrieve selected items.",
                    ),
                    GoogleApiOperation(
                        name = "Google Photos Library app-created search",
                        toolName = "search_google_photos_library",
                        support = "api_backed_app_created_only",
                        requiredScopes = setOf(SCOPE_PHOTOS_LIBRARY_APP_CREATED_READONLY),
                        limits = "Current Google Photos Library API search returns app-created media, not the user's full Google Photos library.",
                    ),
                ),
            ),
        )
    }
}
