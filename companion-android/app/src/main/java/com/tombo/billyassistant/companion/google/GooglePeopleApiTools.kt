package com.tombo.billyassistant.companion.google

import com.tombo.billyassistant.companion.auth.GoogleAccessTokenProvider
import com.tombo.billyassistant.companion.auth.GoogleAccessTokenResult
import com.tombo.billyassistant.companion.auth.GoogleApiScopes
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

class GooglePeopleApiTools(
    private val tokenProvider: GoogleAccessTokenProvider,
    private val http: GoogleApiHttp = GoogleApiHttp(),
) {
    fun searchContacts(query: String, maxResults: Int = 10): GooglePeopleResult {
        val cleanQuery = query.trim()
        return withToken { token ->
            val contacts = fetchContacts(token)
                ?: return@withToken GooglePeopleResult.Failed("Google Contacts lookup failed.")
            val matches = contacts
                .map { contact -> contact to contact.matchScore(cleanQuery) }
                .filter { (_, score) -> cleanQuery.isBlank() || score > 0 }
                .sortedWith(compareByDescending<Pair<ContactSummary, Int>> { it.second }.thenBy { it.first.displayName.lowercase(Locale.US) })
                .map { (contact, _) -> contact }
                .take(maxResults.coerceIn(1, MAX_RESULTS))

            val summary = when {
                matches.isEmpty() && cleanQuery.isBlank() -> "No Google Contacts found."
                matches.isEmpty() -> "No Google Contacts matched \"$cleanQuery\"."
                matches.size == 1 -> "Found 1 Google Contact."
                else -> "Found ${matches.size} Google Contacts."
            }
            GooglePeopleResult.Success(
                summary = summary,
                payload = JSONObject()
                    .put("status", "ok")
                    .put("summary", summary)
                    .put("contacts", JSONArray().also { array -> matches.forEach { array.put(it.toJson()) } }),
            )
        }
    }

    fun resolveEmail(nameOrEmail: String): GooglePeopleResult {
        val query = nameOrEmail.trim()
        if (query.isBlank()) {
            return GooglePeopleResult.Rejected("I need a contact name or email address.")
        }
        EMAIL_REGEX.find(query)?.value?.takeIf { it.isValidEmailAddress() }?.let { email ->
            return GooglePeopleResult.Success(
                summary = "Resolved recipient $email.",
                payload = JSONObject()
                    .put("status", "ok")
                    .put("summary", "Resolved recipient $email.")
                    .put("email", email.safeHeaderValue())
                    .put("display_name", email)
                    .put("contacts", JSONArray()),
            )
        }

        return withToken { token ->
            val contacts = fetchContacts(token)
                ?: return@withToken GooglePeopleResult.Failed("Google Contacts lookup failed.")
            val scored = contacts
                .map { contact -> contact to contact.matchScore(query) }
                .filter { (_, score) -> score > 0 }
                .sortedWith(compareByDescending<Pair<ContactSummary, Int>> { it.second }.thenBy { it.first.displayName.lowercase(Locale.US) })
            if (scored.isEmpty()) {
                return@withToken GooglePeopleResult.Rejected("No Google Contact matched \"$query\".")
            }
            val bestScore = scored.first().second
            val candidates = scored
                .filter { (_, score) -> score >= bestScore - SCORE_AMBIGUITY_WINDOW }
                .map { (contact, _) -> contact }
                .filter { it.primaryEmail.isNotBlank() }
                .distinctBy { it.primaryEmail.lowercase(Locale.US) }
                .take(MAX_CHOICE_RESULTS)
            if (candidates.isEmpty()) {
                return@withToken GooglePeopleResult.Rejected("Matched a contact for \"$query\", but it has no email address.")
            }
            if (candidates.size == 1 && bestScore >= SCORE_CONFIDENT) {
                val contact = candidates.first()
                val summary = "Resolved ${contact.displayName} to ${contact.primaryEmail}."
                return@withToken GooglePeopleResult.Success(
                    summary = summary,
                    payload = JSONObject()
                        .put("status", "ok")
                        .put("summary", summary)
                        .put("email", contact.primaryEmail)
                        .put("display_name", contact.displayName)
                        .put("contact", contact.toJson())
                        .put("contacts", JSONArray().put(contact.toJson())),
                )
            }
            val summary = "Which contact should I use?"
            GooglePeopleResult.Success(
                summary = summary,
                payload = JSONObject()
                    .put("status", "needs_clarification")
                    .put("summary", summary)
                    .put("query", query)
                    .put("contacts", JSONArray().also { array -> candidates.forEach { array.put(it.toJson()) } }),
            )
        }
    }

    private fun fetchContacts(token: String): List<ContactSummary>? {
        val contacts = mutableListOf<ContactSummary>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("$API_BASE/people/me/connections")
                append("?personFields=${encode("names,emailAddresses,phoneNumbers,organizations,metadata")}")
                append("&pageSize=$PAGE_SIZE")
                append("&sortOrder=FIRST_NAME_ASCENDING")
                pageToken?.takeIf { it.isNotBlank() }?.let { append("&pageToken=${encode(it)}") }
            }
            when (val result = http.get(url, token)) {
                is GoogleHttpResult.Success -> {
                    val json = JSONObject(result.body)
                    val people = json.optJSONArray("connections") ?: JSONArray()
                    for (i in 0 until people.length()) {
                        val person = people.optJSONObject(i) ?: continue
                        ContactSummary.fromPerson(person)?.let { contacts += it }
                    }
                    pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
                }
                is GoogleHttpResult.HttpError -> return null
                is GoogleHttpResult.Failed -> return null
            }
        } while (pageToken != null && contacts.size < MAX_FETCHED_CONTACTS)
        return contacts
    }

    private fun withToken(block: (String) -> GooglePeopleResult): GooglePeopleResult {
        return when (val token = tokenProvider.getAccessToken(GoogleApiScopes.people)) {
            is GoogleAccessTokenResult.Authorized -> block(token.accessToken)
            is GoogleAccessTokenResult.NeedsUserGrant -> GooglePeopleResult.NeedsScope(token.scopes)
            is GoogleAccessTokenResult.Failed -> GooglePeopleResult.Failed(token.reason)
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private data class ContactSummary(
        val resourceName: String,
        val displayName: String,
        val primaryEmail: String,
        val emails: List<String>,
        val phoneNumbers: List<String>,
        val organization: String,
    ) {
        fun toJson(): JSONObject {
            return JSONObject()
                .put("resource_name", resourceName)
                .put("display_name", displayName)
                .put("email", primaryEmail)
                .put("emails", JSONArray(emails))
                .put("phone_numbers", JSONArray(phoneNumbers))
                .put("organization", organization)
                .put("label", label())
        }

        fun label(): String {
            return if (primaryEmail.isNotBlank()) {
                "$displayName | $primaryEmail"
            } else {
                displayName
            }.take(80)
        }

        fun matchScore(query: String): Int {
            if (query.isBlank()) {
                return 1
            }
            val normalizedQuery = query.normalized()
            val queryWords = normalizedQuery.split(" ").filter { it.isNotBlank() }.toSet()
            val haystack = listOf(displayName, primaryEmail, organization, emails.joinToString(" ")).joinToString(" ").normalized()
            var score = 0
            if (displayName.normalized() == normalizedQuery) score += 100
            if (primaryEmail.equals(query, ignoreCase = true)) score += 100
            if (haystack.contains(normalizedQuery)) score += 50
            score += queryWords.count { word -> word.length >= 2 && haystack.split(" ").contains(word) } * 15
            score += queryWords.count { word -> word.length >= 3 && haystack.contains(word) } * 5
            return score
        }

        companion object {
            fun fromPerson(person: JSONObject): ContactSummary? {
                if (person.optJSONObject("metadata")?.optBoolean("deleted") == true) {
                    return null
                }
                val names = person.optJSONArray("names") ?: JSONArray()
                val displayName = (0 until names.length())
                    .asSequence()
                    .mapNotNull { names.optJSONObject(it)?.optString("displayName")?.takeIf { name -> name.isNotBlank() } }
                    .firstOrNull()
                    .orEmpty()
                val emailsArray = person.optJSONArray("emailAddresses") ?: JSONArray()
                val emails = (0 until emailsArray.length())
                    .mapNotNull { emailsArray.optJSONObject(it)?.optString("value")?.trim()?.takeIf { value -> value.isValidEmailAddress() } }
                    .distinct()
                val phonesArray = person.optJSONArray("phoneNumbers") ?: JSONArray()
                val phones = (0 until phonesArray.length())
                    .mapNotNull { phonesArray.optJSONObject(it)?.optString("value")?.trim()?.takeIf { value -> value.isNotBlank() } }
                    .distinct()
                val organizations = person.optJSONArray("organizations") ?: JSONArray()
                val organization = (0 until organizations.length())
                    .asSequence()
                    .mapNotNull { organizations.optJSONObject(it)?.optString("name")?.takeIf { name -> name.isNotBlank() } }
                    .firstOrNull()
                    .orEmpty()
                val resourceName = person.optString("resourceName")
                val labelName = displayName.ifBlank { emails.firstOrNull().orEmpty() }
                if (labelName.isBlank()) {
                    return null
                }
                return ContactSummary(
                    resourceName = resourceName,
                    displayName = labelName,
                    primaryEmail = emails.firstOrNull().orEmpty(),
                    emails = emails,
                    phoneNumbers = phones,
                    organization = organization,
                )
            }
        }
    }

    private companion object {
        private const val API_BASE = "https://people.googleapis.com/v1"
        private const val PAGE_SIZE = 500
        private const val MAX_RESULTS = 30
        private const val MAX_FETCHED_CONTACTS = 2_000
        private const val MAX_CHOICE_RESULTS = 4
        private const val SCORE_CONFIDENT = 65
        private const val SCORE_AMBIGUITY_WINDOW = 15
        private val EMAIL_REGEX = Regex("""[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""", RegexOption.IGNORE_CASE)
    }
}

sealed interface GooglePeopleResult {
    val summary: String

    data class Success(
        override val summary: String,
        val payload: JSONObject,
    ) : GooglePeopleResult

    data class NeedsScope(val scopes: List<String>) : GooglePeopleResult {
        override val summary: String = "Grant Google Contacts access in the companion app."
    }

    data class Rejected(val reason: String) : GooglePeopleResult {
        override val summary: String = reason
    }

    data class Failed(val reason: String) : GooglePeopleResult {
        override val summary: String = reason
    }
}

private fun String.normalized(): String {
    return lowercase(Locale.US)
        .replace(Regex("[^a-z0-9@._+-]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun String.isValidEmailAddress(): Boolean {
    return matches(Regex("""[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""", RegexOption.IGNORE_CASE))
}

private fun String.safeHeaderValue(): String {
    return replace(Regex("[\\r\\n]+"), " ").trim()
}
