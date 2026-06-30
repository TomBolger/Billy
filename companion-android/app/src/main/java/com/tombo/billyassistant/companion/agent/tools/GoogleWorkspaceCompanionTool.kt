package com.tombo.billyassistant.companion.agent.tools

import com.tombo.billyassistant.companion.google.GoogleDriveApiTools
import com.tombo.billyassistant.companion.google.GoogleDriveResult
import com.tombo.billyassistant.companion.google.GoogleGmailApiTools
import com.tombo.billyassistant.companion.google.GoogleGmailResult
import com.tombo.billyassistant.companion.google.GooglePeopleApiTools
import com.tombo.billyassistant.companion.google.GooglePeopleResult
import org.json.JSONArray
import org.json.JSONObject

class GoogleWorkspaceCompanionTool(
    private val driveApiTools: GoogleDriveApiTools,
    private val gmailApiTools: GoogleGmailApiTools,
    private val peopleApiTools: GooglePeopleApiTools,
) : CompanionTool {
    override val declarations: List<JSONObject> = listOf(
        JSONObject()
            .put("name", "list_recent_google_drive_files")
            .put("description", "List recently modified Google Drive files.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "max_results" to integerSchema("Maximum number of files to return. Defaults to 10."),
                        "mime_type" to stringSchema("Optional exact Drive mimeType filter."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "search_google_drive")
            .put("description", "Search Google Drive file metadata and return matching file names, links, owners, and modified times.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("query"),
                    properties = mapOf(
                        "query" to stringSchema("Drive search query."),
                        "max_results" to integerSchema("Maximum number of files to return. Defaults to 10."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "read_google_doc")
            .put("description", "Read text from a Google Doc by Drive file id or by searching Drive for a matching Google Doc.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "file_id" to stringSchema("Optional exact Drive file id for the Google Doc."),
                        "query" to stringSchema("Optional Drive search text when file_id is not known."),
                        "max_chars" to integerSchema("Maximum text characters to return. Defaults to 1800."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "create_google_doc")
            .put("description", "Create a Google Doc in the user's Drive when the user explicitly asks for a Google Doc.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("title"),
                    properties = mapOf(
                        "title" to stringSchema("Document title."),
                        "text" to stringSchema("Optional document body text."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "create_google_sheet")
            .put("description", "Create a blank Google Sheet in the user's Drive when explicitly requested.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("title"),
                    properties = mapOf(
                        "title" to stringSchema("Spreadsheet title."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "read_google_sheet")
            .put("description", "Read a small range from a Google Sheet by file id or Drive search query.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "file_id" to stringSchema("Optional exact Drive file id for the Google Sheet."),
                        "query" to stringSchema("Optional Drive search text when file_id is not known."),
                        "range" to stringSchema("Optional A1 range such as Sheet1!A1:D12."),
                        "max_rows" to integerSchema("Maximum rows to return when no range is supplied. Defaults to 12."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "create_google_slides")
            .put("description", "Create a blank Google Slides presentation in the user's Drive when explicitly requested.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("title"),
                    properties = mapOf(
                        "title" to stringSchema("Presentation title."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "read_google_slides")
            .put("description", "Read visible text from a Google Slides deck by file id or Drive search query.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "file_id" to stringSchema("Optional exact Drive file id for the Google Slides deck."),
                        "query" to stringSchema("Optional Drive search text when file_id is not known."),
                        "max_chars" to integerSchema("Maximum text characters to return. Defaults to 1800."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "read_google_form")
            .put("description", "Read a Google Form's title, description, and question structure by file id or Drive search query.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "file_id" to stringSchema("Optional exact Drive file id for the Google Form."),
                        "query" to stringSchema("Optional Drive search text when file_id is not known."),
                        "max_items" to integerSchema("Maximum form items to return. Defaults to 10."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "search_google_contacts")
            .put("description", "Search the user's Google Contacts for people, email addresses, and phone numbers.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("query"),
                    properties = mapOf(
                        "query" to stringSchema("Contact name, organization, phone, or email search text."),
                        "max_results" to integerSchema("Maximum number of contacts to return. Defaults to 10."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "resolve_google_contact_email")
            .put("description", "Resolve a contact name to an email address. Use before Gmail send when the recipient is a name rather than an email.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("name_or_email"),
                    properties = mapOf(
                        "name_or_email" to stringSchema("Contact name or email address."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "search_gmail")
            .put("description", "Search Gmail messages and return concise metadata and snippets.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "query" to stringSchema("Gmail search query. Can use Gmail search syntax."),
                        "max_results" to integerSchema("Maximum number of messages to return. Defaults to 5."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "create_gmail_draft")
            .put("description", "Create a Gmail draft when the user explicitly asks for a draft. Do not use for normal send/email requests.")
            .put(
                "parameters",
                objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "to" to stringSchema("Optional recipient email address."),
                        "subject" to stringSchema("Optional draft subject."),
                        "body" to stringSchema("Optional draft body."),
                    ),
                ),
            ),
        JSONObject()
            .put("name", "prepare_gmail_send")
            .put("description", "Prepare a Gmail email for watch confirmation before sending. Use when the user asks to email or send a message.")
            .put(
                "parameters",
                objectSchema(
                    required = listOf("to", "body"),
                    properties = mapOf(
                        "to" to stringSchema("Recipient email address, me/myself for the signed-in Gmail account, or a Google Contacts name."),
                        "subject" to stringSchema("Email subject."),
                        "body" to stringSchema("Email body."),
                    ),
                ),
            ),
    )

    override fun execute(name: String, args: JSONObject): CompanionToolExecution? {
        return when (name) {
            "list_recent_google_drive_files" -> driveApiTools.listRecentFiles(
                maxResults = args.optionalInt("max_results") ?: 10,
                mimeType = args.optString("mime_type").ifBlank { null },
            ).toExecution(finalOnSuccess = true)
            "search_google_drive" -> driveApiTools.searchFiles(
                query = args.optString("query"),
                maxResults = args.optionalInt("max_results") ?: 10,
            ).toExecution(finalOnSuccess = true)
            "read_google_doc" -> driveApiTools.readGoogleDoc(
                fileId = args.optString("file_id").ifBlank { null },
                query = args.optString("query").ifBlank { null },
                maxChars = args.optionalInt("max_chars") ?: 1800,
            ).toExecution(finalOnSuccess = true)
            "create_google_doc" -> driveApiTools.createGoogleDoc(
                title = args.optString("title"),
                text = args.optString("text").ifBlank { null },
            ).toExecution(finalOnSuccess = true)
            "create_google_sheet" -> driveApiTools.createGoogleSheet(
                title = args.optString("title"),
            ).toExecution(finalOnSuccess = true)
            "read_google_sheet" -> driveApiTools.readGoogleSheet(
                fileId = args.optString("file_id").ifBlank { null },
                query = args.optString("query").ifBlank { null },
                range = args.optString("range").ifBlank { null },
                maxRows = args.optionalInt("max_rows") ?: 12,
            ).toExecution(finalOnSuccess = true)
            "create_google_slides" -> driveApiTools.createGoogleSlides(
                title = args.optString("title"),
            ).toExecution(finalOnSuccess = true)
            "read_google_slides" -> driveApiTools.readGoogleSlides(
                fileId = args.optString("file_id").ifBlank { null },
                query = args.optString("query").ifBlank { null },
                maxChars = args.optionalInt("max_chars") ?: 1800,
            ).toExecution(finalOnSuccess = true)
            "read_google_form" -> driveApiTools.readGoogleForm(
                fileId = args.optString("file_id").ifBlank { null },
                query = args.optString("query").ifBlank { null },
                maxItems = args.optionalInt("max_items") ?: 10,
            ).toExecution(finalOnSuccess = true)
            "search_google_contacts" -> peopleApiTools.searchContacts(
                query = args.optString("query"),
                maxResults = args.optionalInt("max_results") ?: 10,
            ).toExecution(finalOnSuccess = true)
            "resolve_google_contact_email" -> peopleApiTools.resolveEmail(
                nameOrEmail = args.optString("name_or_email"),
            ).toExecution(finalOnSuccess = true)
            "search_gmail" -> gmailApiTools.searchMessages(
                query = args.optString("query"),
                maxResults = args.optionalInt("max_results") ?: 5,
            ).toExecution(finalOnSuccess = true)
            "create_gmail_draft" -> gmailApiTools.createDraft(
                to = args.optString("to").ifBlank { null },
                subject = args.optString("subject").ifBlank { null },
                body = args.optString("body").ifBlank { null },
            ).toExecution(finalOnSuccess = true)
            "prepare_gmail_send" -> prepareGmailSend(args)
            else -> null
        }
    }

    private fun prepareGmailSend(args: JSONObject): CompanionToolExecution {
        resolveGmailRecipient(args)?.let { recipientResult ->
            when (recipientResult) {
                is GmailRecipientToolResolution.Resolved -> args.put("to", recipientResult.email)
                is GmailRecipientToolResolution.Execution -> return recipientResult.execution
            }
        }
        val result = gmailApiTools.prepareSend(
            to = args.optString("to").ifBlank { null },
            subject = args.optString("subject").ifBlank { null },
            body = args.optString("body").ifBlank { null },
        )
        val response = result.toJson()
        if (response.optString("status") != "ok") {
            return CompanionToolExecution(
                response = response,
                finalText = response.optString("summary").ifBlank { result.summary },
            )
        }
        val pending = PendingGmailSend(
            to = response.optString("to"),
            subject = response.optString("subject"),
            body = response.optString("body"),
        )
        val token = PendingGmailSends.put(pending)
        val question = buildString {
            append("Send email?\n")
            append("To: ${pending.to}\n")
            append("Subject: ${pending.subject.ifBlank { "(no subject)" }}\n")
            append("Body: ${pending.body.ifBlank { "(blank)" }}")
        }.take(220)
        return CompanionToolExecution(
            response = response
                .put("confirmation", "required")
                .put("gmail_send_token", token),
            clarificationCard = ClarificationCard(
                question = question,
                context = "gmail_send_token=$token",
                options = listOf("Send", "Cancel"),
            ),
        )
    }

    private fun resolveGmailRecipient(args: JSONObject): GmailRecipientToolResolution? {
        val to = args.optString("to").trim()
        if (to.isBlank() || to.containsValidEmail() || to.isSelfReference()) {
            return null
        }
        return when (val result = peopleApiTools.resolveEmail(to)) {
            is GooglePeopleResult.Success -> {
                val response = result.payload
                when (response.optString("status")) {
                    "ok" -> response.optString("email").takeIf { it.containsValidEmail() }?.let {
                        GmailRecipientToolResolution.Resolved(it)
                    } ?: GmailRecipientToolResolution.Execution(
                        CompanionToolExecution(
                            response = response,
                            finalText = response.optString("summary").ifBlank { "I could not resolve that contact to an email address." },
                        ),
                    )
                    "needs_clarification" -> {
                        val contacts = response.optJSONArray("contacts") ?: JSONArray()
                        val options = mutableListOf<PendingGmailRecipientOption>()
                        for (i in 0 until minOf(contacts.length(), 4)) {
                            val contact = contacts.optJSONObject(i) ?: continue
                            val email = contact.optString("email")
                            if (email.containsValidEmail()) {
                                options += PendingGmailRecipientOption(
                                    label = contact.optString("label").ifBlank { "${contact.optString("display_name")} | $email" },
                                    displayName = contact.optString("display_name"),
                                    email = email,
                                )
                            }
                        }
                        if (options.isEmpty()) {
                            return GmailRecipientToolResolution.Execution(
                                CompanionToolExecution(
                                    response = response,
                                    finalText = "I found contacts for \"$to\", but none had an email address.",
                                ),
                            )
                        }
                        val token = PendingGmailRecipientChoices.put(
                            PendingGmailRecipientChoice(
                                originalQuery = to,
                                subject = args.optString("subject"),
                                body = args.optString("body"),
                                options = options,
                            ),
                        )
                        GmailRecipientToolResolution.Execution(
                            CompanionToolExecution(
                                response = response.put("gmail_recipient_token", token),
                                clarificationCard = ClarificationCard(
                                    question = "Which contact?",
                                    context = "gmail_recipient_token=$token",
                                    options = options.map { it.label },
                                ),
                            ),
                        )
                    }
                    else -> GmailRecipientToolResolution.Execution(
                        CompanionToolExecution(
                            response = response,
                            finalText = response.optString("summary").ifBlank { result.summary },
                        ),
                    )
                }
            }
            is GooglePeopleResult.NeedsScope -> GmailRecipientToolResolution.Execution(
                CompanionToolExecution(
                    response = result.toJson(),
                    finalText = result.summary,
                ),
            )
            is GooglePeopleResult.Rejected -> GmailRecipientToolResolution.Execution(
                CompanionToolExecution(
                    response = result.toJson(),
                    finalText = result.reason,
                ),
            )
            is GooglePeopleResult.Failed -> GmailRecipientToolResolution.Execution(
                CompanionToolExecution(
                    response = result.toJson(),
                    finalText = result.reason,
                ),
            )
        }
    }
}

private sealed interface GmailRecipientToolResolution {
    data class Resolved(val email: String) : GmailRecipientToolResolution
    data class Execution(val execution: CompanionToolExecution) : GmailRecipientToolResolution
}

private fun GoogleDriveResult.toExecution(finalOnSuccess: Boolean = false): CompanionToolExecution {
    val response = toJson()
    return CompanionToolExecution(
        response = response,
        finalText = if (finalOnSuccess || response.optString("status") != "ok") driveWatchSummary(response, summary) else null,
    )
}

private fun GoogleGmailResult.toExecution(finalOnSuccess: Boolean = false): CompanionToolExecution {
    val response = toJson()
    return CompanionToolExecution(
        response = response,
        finalText = if (finalOnSuccess || response.optString("status") != "ok") summary else null,
    )
}

private fun GooglePeopleResult.toExecution(finalOnSuccess: Boolean = false): CompanionToolExecution {
    val response = toJson()
    return CompanionToolExecution(
        response = response,
        finalText = if (finalOnSuccess || response.optString("status") != "ok") peopleWatchSummary(response, summary) else null,
    )
}

private fun GoogleDriveResult.toJson(): JSONObject {
    return when (this) {
        is GoogleDriveResult.Success -> payload
        is GoogleDriveResult.NeedsScope -> JSONObject()
            .put("status", "needs_scope")
            .put("summary", summary)
            .put("missing_scopes", JSONArray(scopes))
        is GoogleDriveResult.Rejected -> JSONObject()
            .put("status", "rejected")
            .put("summary", reason)
            .put("reason", reason)
        is GoogleDriveResult.Failed -> JSONObject()
            .put("status", "error")
            .put("summary", reason)
            .put("reason", reason)
    }
}

private fun GoogleGmailResult.toJson(): JSONObject {
    return when (this) {
        is GoogleGmailResult.Success -> payload
        is GoogleGmailResult.NeedsScope -> JSONObject()
            .put("status", "needs_scope")
            .put("summary", summary)
            .put("missing_scopes", JSONArray(scopes))
        is GoogleGmailResult.Rejected -> JSONObject()
            .put("status", "rejected")
            .put("summary", reason)
            .put("reason", reason)
        is GoogleGmailResult.Failed -> JSONObject()
            .put("status", "error")
            .put("summary", reason)
            .put("reason", reason)
    }
}

private fun GooglePeopleResult.toJson(): JSONObject {
    return when (this) {
        is GooglePeopleResult.Success -> payload
        is GooglePeopleResult.NeedsScope -> JSONObject()
            .put("status", "needs_scope")
            .put("summary", summary)
            .put("missing_scopes", JSONArray(scopes))
        is GooglePeopleResult.Rejected -> JSONObject()
            .put("status", "rejected")
            .put("summary", reason)
            .put("reason", reason)
        is GooglePeopleResult.Failed -> JSONObject()
            .put("status", "error")
            .put("summary", reason)
            .put("reason", reason)
    }
}

private fun JSONObject.optionalInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

private fun driveWatchSummary(response: JSONObject, fallback: String): String {
    if (response.optString("status") != "ok") {
        return response.optString("summary").ifBlank { fallback }
    }
    response.optString("text").takeIf { it.isNotBlank() }?.let { text ->
        val title = response.optString("title").ifBlank { "Google file" }
        return "$title:\n${text.take(420).trim()}"
    }
    response.optJSONArray("values")?.let { values ->
        val title = response.optString("title").ifBlank { "Google Sheet" }
        val lines = mutableListOf(title)
        for (i in 0 until minOf(values.length(), 5)) {
            val row = values.optJSONArray(i) ?: continue
            val cells = mutableListOf<String>()
            for (j in 0 until minOf(row.length(), 4)) {
                cells += row.optString(j)
            }
            if (cells.any { it.isNotBlank() }) {
                lines += "- ${cells.joinToString(" | ").take(80)}"
            }
        }
        return lines.joinToString("\n")
    }
    response.optJSONArray("items")?.let { items ->
        val title = response.optString("title").ifBlank { "Google Form" }
        val lines = mutableListOf(title)
        for (i in 0 until minOf(items.length(), 5)) {
            val item = items.optJSONObject(i) ?: continue
            val itemTitle = item.optString("title").ifBlank { "(untitled question)" }
            lines += "- ${itemTitle.take(80)}"
        }
        return lines.joinToString("\n")
    }
    response.optString("document_id").takeIf { it.isNotBlank() }?.let {
        return response.optString("summary").ifBlank { fallback }
    }
    val files = response.optJSONArray("files") ?: return response.optString("summary").ifBlank { fallback }
    if (files.length() == 0) {
        return response.optString("summary").ifBlank { fallback }
    }
    val lines = mutableListOf("Drive:")
    for (i in 0 until minOf(files.length(), 5)) {
        val file = files.optJSONObject(i) ?: continue
        val name = file.optString("name").ifBlank { "(untitled)" }
        val kind = file.optString("mimeType").substringAfterLast(".").replace("vnd.google-apps.", "")
        lines += "- $name${kind.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}"
    }
    if (files.length() > 5) {
        lines += "- +${files.length() - 5} more"
    }
    return lines.joinToString("\n")
}

private fun peopleWatchSummary(response: JSONObject, fallback: String): String {
    if (response.optString("status") != "ok") {
        return response.optString("summary").ifBlank { fallback }
    }
    response.optString("email").takeIf { it.isNotBlank() }?.let { email ->
        val name = response.optString("display_name").ifBlank { "Contact" }
        return "$name:\n$email"
    }
    val contacts = response.optJSONArray("contacts") ?: JSONArray()
    if (contacts.length() == 0) {
        return response.optString("summary").ifBlank { fallback }
    }
    val lines = mutableListOf("Contacts:")
    for (i in 0 until minOf(contacts.length(), 5)) {
        val contact = contacts.optJSONObject(i) ?: continue
        val label = contact.optString("label")
            .ifBlank { listOf(contact.optString("display_name"), contact.optString("email")).filter { it.isNotBlank() }.joinToString(" | ") }
        lines += "- ${label.take(84)}"
    }
    return lines.joinToString("\n")
}

private fun String.containsValidEmail(): Boolean {
    return Regex("""[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""", RegexOption.IGNORE_CASE).containsMatchIn(this)
}

private fun String.isSelfReference(): Boolean {
    val normalized = lowercase()
        .replace(Regex("[^a-z0-9@.]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return normalized in setOf("me", "myself", "self", "my email", "my account", "my gmail")
}
