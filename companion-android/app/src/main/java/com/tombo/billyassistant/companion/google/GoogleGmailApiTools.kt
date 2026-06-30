package com.tombo.billyassistant.companion.google

import com.tombo.billyassistant.companion.auth.GoogleAccessTokenProvider
import com.tombo.billyassistant.companion.auth.GoogleAccessTokenResult
import com.tombo.billyassistant.companion.auth.GoogleApiScopes
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

class GoogleGmailApiTools(
    private val tokenProvider: GoogleAccessTokenProvider,
    private val http: GoogleApiHttp = GoogleApiHttp(),
) {
    fun searchMessages(query: String, maxResults: Int = 5): GoogleGmailResult {
        return withToken(GoogleApiScopes.identity + GoogleApiScopes.GMAIL_READONLY) { token ->
            val url = "$API_BASE/users/me/messages?q=${encode(query)}&maxResults=${maxResults.coerceIn(1, 10)}"
            when (val result = http.get(url, token)) {
                is GoogleHttpResult.Success -> {
                    val messageRefs = JSONObject(result.body).optJSONArray("messages") ?: JSONArray()
                    val messages = JSONArray()
                    for (i in 0 until messageRefs.length()) {
                        val id = messageRefs.optJSONObject(i)?.optString("id").orEmpty()
                        if (id.isNotBlank()) {
                            val metadata = http.get("$API_BASE/users/me/messages/${encode(id)}?format=metadata&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date", token)
                            if (metadata is GoogleHttpResult.Success) {
                                messages.put(summarizeMessage(JSONObject(metadata.body)))
                            }
                        }
                    }
                    val summary = when (messages.length()) {
                        0 -> "No Gmail messages found."
                        1 -> "Found 1 Gmail message."
                        else -> "Found ${messages.length()} Gmail messages."
                    }
                    GoogleGmailResult.Success(
                        summary = summary,
                        payload = JSONObject()
                            .put("status", "ok")
                            .put("summary", summary)
                            .put("messages", messages),
                    )
                }
                is GoogleHttpResult.HttpError -> GoogleGmailResult.Failed("Gmail HTTP ${result.responseCode}: ${result.reason}")
                is GoogleHttpResult.Failed -> GoogleGmailResult.Failed("Gmail failed: ${result.reason}")
            }
        }
    }

    fun createDraft(to: String?, subject: String?, body: String?): GoogleGmailResult {
        return withToken(GoogleApiScopes.identity + GoogleApiScopes.GMAIL_COMPOSE) { token ->
            val resolvedTo = resolveRecipient(token, to)
            if (resolvedTo is RecipientResolution.Rejected) {
                return@withToken GoogleGmailResult.Rejected(resolvedTo.reason)
            }
            resolvedTo as RecipientResolution.Resolved
            val raw = buildString {
                if (!resolvedTo.email.isNullOrBlank()) append("To: ${resolvedTo.email}\r\n")
                if (!subject.isNullOrBlank()) append("Subject: ${subject.safeHeaderValue()}\r\n")
                append("Content-Type: text/plain; charset=UTF-8\r\n")
                append("\r\n")
                append(body?.trim().orEmpty())
            }
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
            val request = JSONObject().put("message", JSONObject().put("raw", encoded))
            when (val result = http.post("$API_BASE/users/me/drafts", token, request)) {
                is GoogleHttpResult.Success -> {
                    val draft = JSONObject(result.body)
                    val draftId = draft.optString("id")
                    val messageId = draft.optJSONObject("message")?.optString("id").orEmpty()
                    val recipientSummary = resolvedTo.email?.let { " to $it" }.orEmpty()
                    val subjectSummary = subject?.trim().orEmpty().ifBlank { "(no subject)" }
                    val summary = "Created Gmail draft$recipientSummary.\nSubject: $subjectSummary"
                    GoogleGmailResult.Success(
                        summary = summary,
                        payload = JSONObject()
                            .put("status", "ok")
                            .put("summary", summary)
                            .put("to", resolvedTo.email.orEmpty())
                            .put("draft_id", draftId)
                            .put("message_id", messageId)
                            .put("verified", draftId.isNotBlank())
                            .put("draft", draft),
                    )
                }
                is GoogleHttpResult.HttpError -> GoogleGmailResult.Failed("Gmail HTTP ${result.responseCode}: ${result.reason}")
                is GoogleHttpResult.Failed -> GoogleGmailResult.Failed("Gmail failed: ${result.reason}")
            }
        }
    }

    fun prepareSend(to: String?, subject: String?, body: String?): GoogleGmailResult {
        return withToken(GoogleApiScopes.identity + GoogleApiScopes.GMAIL_SEND) { token ->
            val resolvedTo = resolveRecipient(token, to)
            if (resolvedTo is RecipientResolution.Rejected) {
                return@withToken GoogleGmailResult.Rejected(resolvedTo.reason)
            }
            resolvedTo as RecipientResolution.Resolved
            if (resolvedTo.email.isNullOrBlank()) {
                return@withToken GoogleGmailResult.Rejected("I need a recipient email address before sending.")
            }
            val subjectText = subject?.trim().orEmpty().ifBlank { "(no subject)" }
            val bodyText = body?.trim().orEmpty()
            val summary = "Ready to send email to ${resolvedTo.email}."
            GoogleGmailResult.Success(
                summary = summary,
                payload = JSONObject()
                    .put("status", "ok")
                    .put("summary", summary)
                    .put("to", resolvedTo.email)
                    .put("subject", subjectText)
                    .put("body", bodyText),
            )
        }
    }

    fun sendMessage(to: String?, subject: String?, body: String?): GoogleGmailResult {
        return withToken(GoogleApiScopes.identity + GoogleApiScopes.GMAIL_SEND) { token ->
            val resolvedTo = resolveRecipient(token, to)
            if (resolvedTo is RecipientResolution.Rejected) {
                return@withToken GoogleGmailResult.Rejected(resolvedTo.reason)
            }
            resolvedTo as RecipientResolution.Resolved
            if (resolvedTo.email.isNullOrBlank()) {
                return@withToken GoogleGmailResult.Rejected("I need a recipient email address before sending.")
            }
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                rawMessage(
                    to = resolvedTo.email,
                    subject = subject,
                    body = body,
                ).toByteArray(Charsets.UTF_8),
            )
            val request = JSONObject().put("raw", encoded)
            when (val result = http.post("$API_BASE/users/me/messages/send", token, request)) {
                is GoogleHttpResult.Success -> {
                    val sent = JSONObject(result.body)
                    val summary = "Sent email to ${resolvedTo.email}.\nSubject: ${(subject?.trim().orEmpty()).ifBlank { "(no subject)" }}"
                    GoogleGmailResult.Success(
                        summary = summary,
                        payload = JSONObject()
                            .put("status", "ok")
                            .put("summary", summary)
                            .put("to", resolvedTo.email)
                            .put("subject", subject?.trim().orEmpty())
                            .put("body", body?.trim().orEmpty())
                            .put("message_id", sent.optString("id"))
                            .put("thread_id", sent.optString("threadId"))
                            .put("verified", sent.optString("id").isNotBlank()),
                    )
                }
                is GoogleHttpResult.HttpError -> GoogleGmailResult.Failed("Gmail send HTTP ${result.responseCode}: ${result.reason}")
                is GoogleHttpResult.Failed -> GoogleGmailResult.Failed("Gmail send failed: ${result.reason}")
            }
        }
    }

    private fun resolveRecipient(token: String, to: String?): RecipientResolution {
        val value = to?.trim().orEmpty()
        if (value.isBlank()) {
            return RecipientResolution.Resolved(null)
        }
        val extracted = EMAIL_REGEX.find(value)?.value.orEmpty()
        if (extracted.isValidEmailAddress()) {
            return RecipientResolution.Resolved(extracted.safeHeaderValue())
        }
        if (value.isSelfReference()) {
            return when (val profile = http.get("https://www.googleapis.com/oauth2/v2/userinfo", token)) {
                is GoogleHttpResult.Success -> {
                    val email = JSONObject(profile.body).optString("email").trim()
                    if (email.isValidEmailAddress()) {
                        RecipientResolution.Resolved(email)
                    } else {
                        RecipientResolution.Rejected("Google did not return your account email. Ask with the exact recipient email address.")
                    }
                }
                is GoogleHttpResult.HttpError -> RecipientResolution.Rejected("Google profile HTTP ${profile.responseCode}: ${profile.reason}")
                is GoogleHttpResult.Failed -> RecipientResolution.Rejected("Google profile failed: ${profile.reason}")
            }
        }
        return RecipientResolution.Rejected("I need a real recipient email address for \"$value\".")
    }

    private fun rawMessage(to: String?, subject: String?, body: String?): String {
        return buildString {
            if (!to.isNullOrBlank()) append("To: ${to.safeHeaderValue()}\r\n")
            if (!subject.isNullOrBlank()) append("Subject: ${subject.safeHeaderValue()}\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("\r\n")
            append(body?.trim().orEmpty())
        }
    }

    private fun summarizeMessage(message: JSONObject): JSONObject {
        val headers = message.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
        fun header(name: String): String {
            for (i in 0 until headers.length()) {
                val item = headers.optJSONObject(i) ?: continue
                if (item.optString("name").equals(name, ignoreCase = true)) {
                    return item.optString("value")
                }
            }
            return ""
        }
        return JSONObject()
            .put("id", message.optString("id"))
            .put("thread_id", message.optString("threadId"))
            .put("from", header("From"))
            .put("subject", header("Subject"))
            .put("date", header("Date"))
            .put("snippet", message.optString("snippet"))
    }

    private fun withToken(scopes: List<String>, block: (String) -> GoogleGmailResult): GoogleGmailResult {
        return when (val token = tokenProvider.getAccessToken(scopes)) {
            is GoogleAccessTokenResult.Authorized -> block(token.accessToken)
            is GoogleAccessTokenResult.NeedsUserGrant -> GoogleGmailResult.NeedsScope(token.scopes)
            is GoogleAccessTokenResult.Failed -> GoogleGmailResult.Failed(token.reason)
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private companion object {
        private const val API_BASE = "https://gmail.googleapis.com/gmail/v1"
        private val EMAIL_REGEX = Regex("""[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""", RegexOption.IGNORE_CASE)
    }
}

sealed interface GoogleGmailResult {
    val summary: String

    data class Success(
        override val summary: String,
        val payload: JSONObject,
    ) : GoogleGmailResult

    data class NeedsScope(val scopes: List<String>) : GoogleGmailResult {
        override val summary: String = "Grant Gmail access in the companion app."
    }

    data class Rejected(val reason: String) : GoogleGmailResult {
        override val summary: String = reason
    }

    data class Failed(val reason: String) : GoogleGmailResult {
        override val summary: String = reason
    }
}

private sealed interface RecipientResolution {
    data class Resolved(val email: String?) : RecipientResolution
    data class Rejected(val reason: String) : RecipientResolution
}

private fun String.isSelfReference(): Boolean {
    val normalized = lowercase()
        .replace(Regex("[^a-z0-9@.]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return normalized in setOf("me", "myself", "self", "my email", "my account", "my gmail")
}

private fun String.isValidEmailAddress(): Boolean {
    return matches(Regex("""[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""", RegexOption.IGNORE_CASE))
}

private fun String.safeHeaderValue(): String {
    return replace(Regex("[\\r\\n]+"), " ").trim()
}
