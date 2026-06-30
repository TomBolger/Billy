package com.tombo.billyassistant.companion.agent.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tombo.billyassistant.companion.google.GoogleMapsTravelMode
import com.tombo.billyassistant.companion.google.googleMapsTravelModeValues
import com.tombo.billyassistant.companion.google.toCanonicalGoogleMapsTravelMode
import org.json.JSONArray
import org.json.JSONObject

class AppIntentCompanionTool(
    private val context: Context,
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        JSONObject()
            .put("name", "compose_email_draft")
            .put("description", "Open an email compose draft on the Android phone, preferring Gmail. This cannot silently send email.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "to" to stringSchema("Optional recipient email address."),
                        "subject" to stringSchema("Optional email subject."),
                        "body" to stringSchema("Optional email body."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "open_drive_search")
            .put("description", "Open Google Drive search on the Android phone for a query. This does not grant Billy direct Drive file access.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("query"),
                    properties = mapOf(
                        "query" to stringSchema("Drive search query."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "open_maps_directions")
            .put("description", "Open Google Maps directions or navigation on the Android phone. Preserve the user's requested travel mode by passing the canonical travel_mode enum. Use this first for navigation/start-guidance requests, then use show_map_directions separately for the watch map card.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("destination"),
                    properties = mapOf(
                        "destination" to stringSchema("Destination name or address."),
                        "travel_mode" to enumStringSchema("Canonical travel mode selected from the user's intent. Use WALK for walking directions or on-foot requests.", googleMapsTravelModeValues),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "get_google_service_status")
            .put("description", "Report what Billy can currently do for Google services through this companion.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "service" to stringSchema("Optional service name, such as keep, gmail, drive, tasks, docs, sheets, slides, photos, or calendar."),
                    ),
                ),
            ),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        return when (name) {
            "compose_email_draft" -> composeEmailDraft(args)
            "open_drive_search" -> openDriveSearch(args)
            "open_maps_directions" -> openMapsDirections(args)
            "get_google_service_status" -> serviceStatus(args)
            else -> null
        }
    }

    private fun composeEmailDraft(args: JSONObject): CompanionToolExecution {
        val to = args.optString("to").trim()
        val subject = args.optString("subject").trim()
        val body = args.optString("body").trim()
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:${Uri.encode(to)}")
            setPackage(PACKAGE_GMAIL)
            if (to.isNotBlank()) {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            }
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchWithFallback(
            primary = intent,
            fallback = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:${Uri.encode(to)}")
                if (to.isNotBlank()) {
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                }
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            okSummary = "Opened an email draft on the phone. Review and send it there.",
        )
    }

    private fun openDriveSearch(args: JSONObject): CompanionToolExecution {
        val query = args.optString("query").trim()
        if (query.isBlank()) {
            return rejected("Drive search query is required.")
        }
        val url = "https://drive.google.com/drive/search?q=${Uri.encode(query)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(PACKAGE_DRIVE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launchWithFallback(
            primary = intent,
            fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            okSummary = "Opened Drive search for \"$query\" on the phone.",
        )
    }

    private fun openMapsDirections(args: JSONObject): CompanionToolExecution {
        val destination = args.optString("destination").trim()
        if (destination.isBlank()) {
            return rejected("Destination is required.")
        }
        val rawTravelMode = args.optString("travel_mode")
        val travelMode = if (rawTravelMode.isBlank()) {
            GoogleMapsTravelMode.DRIVE
        } else {
            rawTravelMode.toCanonicalGoogleMapsTravelMode()
                ?: return rejected("Invalid travel_mode \"$rawTravelMode\". Use DRIVE, WALK, BICYCLE, TRANSIT, or TWO_WHEELER.")
        }
        val intent = Intent(Intent.ACTION_VIEW, mapsNavigationUri(destination, travelMode)).apply {
            setPackage(PACKAGE_MAPS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launch = launchDirect(intent)
        return CompanionToolExecution(
            response = JSONObject()
                .put("status", if (launch.ok) "ok" else "error")
                .put("summary", launch.summary)
                .put("destination", destination)
                .put("travel_mode", travelMode.routesValue),
            finalText = if (launch.ok) null else launch.summary,
        )
    }

    private fun mapsNavigationUri(destination: String, travelMode: GoogleMapsTravelMode): Uri {
        if (travelMode == GoogleMapsTravelMode.TRANSIT) {
            return Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}&travelmode=transit")
        }
        val mode = travelMode.androidNavigationMode ?: GoogleMapsTravelMode.DRIVE.androidNavigationMode.orEmpty()
        return Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$mode")
    }

    private fun serviceStatus(args: JSONObject): CompanionToolExecution {
        val requested = args.optString("service").trim().lowercase()
        val services = serviceStatuses().filter { status ->
            requested.isBlank() || status.optString("service") == requested
        }
        val response = JSONObject()
            .put("status", "ok")
            .put("summary", if (services.size == 1) services.first().optString("summary") else "Reported current Google service support.")
            .put("services", JSONArray().also { array -> services.forEach { array.put(it) } })
        return CompanionToolExecution(response)
    }

    private fun launchWithFallback(primary: Intent, fallback: Intent, okSummary: String): CompanionToolExecution {
        val launch = launchDirectWithFallback(primary, fallback)
        return if (launch.ok) {
            ok(okSummary)
        } else {
            error(launch.summary)
        }
    }

    private fun launchDirectWithFallback(primary: Intent, fallback: Intent): AppLaunchResult {
        return try {
            context.startActivity(primary)
            AppLaunchResult(ok = true, summary = "Opened requested Android action.")
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(fallback)
                AppLaunchResult(ok = true, summary = "Opened fallback Android action.")
            } catch (e: Exception) {
                AppLaunchResult(ok = false, summary = "No compatible Android app could handle this action: ${e.message ?: e.javaClass.simpleName}")
            }
        } catch (e: Exception) {
            AppLaunchResult(ok = false, summary = "Android could not open the requested app action: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun launchDirect(intent: Intent): AppLaunchResult {
        return try {
            context.startActivity(intent)
            AppLaunchResult(ok = true, summary = "Opened Maps directions on the phone.")
        } catch (_: ActivityNotFoundException) {
            AppLaunchResult(ok = false, summary = "Google Maps could not handle navigation on this phone.")
        } catch (e: Exception) {
            AppLaunchResult(ok = false, summary = "Android could not open Google Maps navigation: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun ok(summary: String): CompanionToolExecution {
        return CompanionToolExecution(
            response = JSONObject()
                .put("status", "ok")
                .put("summary", summary),
            finalText = summary,
        )
    }

    private fun rejected(reason: String): CompanionToolExecution {
        val response = JSONObject()
            .put("status", "rejected")
            .put("summary", reason)
            .put("reason", reason)
        return CompanionToolExecution(response, finalText = reason)
    }

    private fun error(reason: String): CompanionToolExecution {
        val response = JSONObject()
            .put("status", "error")
            .put("summary", reason)
            .put("reason", reason)
        return CompanionToolExecution(response, finalText = reason)
    }

    private fun serviceStatuses(): List<JSONObject> {
        return listOf(
            service("calendar", "api_backed", "Can read and create Google Calendar events through OAuth. Android Calendar Provider is read-only fallback and ghost cleanup only."),
            service("photos", "available", "Can list/search local or selected Android photos and attach one image for Gemini analysis."),
            service("keep", "unavailable", "Google Keep personal OAuth returns invalid_scope. Billy does not create a substitute note."),
            service("gmail", "api_backed", "Can search Gmail, create drafts, and send after watch confirmation through OAuth."),
            service("drive", "api_backed", "Can search Drive metadata/full text through OAuth; opening Drive search remains available as a phone fallback."),
            service("docs", "api_backed", "Can read text from Google Docs and create Google Docs through OAuth."),
            service("sheets", "api_backed", "Can read small Google Sheets ranges through OAuth."),
            service("slides", "api_backed", "Can read visible text from Google Slides through OAuth."),
            service("tasks", "api_backed", "Can list, create, and complete Google Tasks through OAuth with readback verification."),
        )
    }

    private fun service(service: String, support: String, summary: String): JSONObject {
        return JSONObject()
            .put("service", service)
            .put("support", support)
            .put("summary", summary)
    }

    private companion object {
        private const val PACKAGE_DRIVE = "com.google.android.apps.docs"
        private const val PACKAGE_GMAIL = "com.google.android.gm"
        private const val PACKAGE_MAPS = "com.google.android.apps.maps"
    }

    private data class AppLaunchResult(
        val ok: Boolean,
        val summary: String,
    )
}
