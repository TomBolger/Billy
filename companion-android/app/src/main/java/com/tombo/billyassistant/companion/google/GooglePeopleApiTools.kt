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
    fun fetchOwnProfile(): GooglePeopleResult {
        return when (val token = tokenProvider.getAccessToken(GoogleApiScopes.identity)) {
            is GoogleAccessTokenResult.Authorized -> fetchOwnProfile(token.accessToken)
            is GoogleAccessTokenResult.NeedsUserGrant -> GooglePeopleResult.NeedsScope(token.scopes)
            is GoogleAccessTokenResult.Failed -> GooglePeopleResult.Failed(token.reason)
        }
    }

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

    private fun fetchOwnProfile(identityToken: String): GooglePeopleResult {
        val profile = JSONObject()
        when (val userInfo = http.get(OAUTH_USERINFO_URL, identityToken)) {
            is GoogleHttpResult.Success -> {
                val json = JSONObject(userInfo.body)
                profile
                    .put("display_name", json.optString("name"))
                    .put("email", json.optString("email"))
                    .put("locale", json.optString("locale"))
                    .put("photo_url", json.optString("picture"))
            }
            is GoogleHttpResult.HttpError -> return GooglePeopleResult.Failed("Google profile HTTP ${userInfo.responseCode}: ${userInfo.reason}")
            is GoogleHttpResult.Failed -> return GooglePeopleResult.Failed("Google profile failed: ${userInfo.reason}")
        }

        when (val peopleToken = tokenProvider.getAccessToken(GoogleApiScopes.people)) {
            is GoogleAccessTokenResult.Authorized -> mergePeopleMeProfile(profile, peopleToken.accessToken)
            is GoogleAccessTokenResult.NeedsUserGrant -> Unit
            is GoogleAccessTokenResult.Failed -> Unit
        }

        val summaryName = profile.optString("display_name").ifBlank { profile.optString("email") }.ifBlank { "Google profile" }
        return GooglePeopleResult.Success(
            summary = "Loaded $summaryName.",
            payload = JSONObject()
                .put("status", "ok")
                .put("summary", "Loaded $summaryName.")
                .put("profile", profile),
        )
    }

    private fun mergePeopleMeProfile(profile: JSONObject, accessToken: String) {
        val url = "$API_BASE/people/me?personFields=${encode(PEOPLE_ME_FIELDS)}"
        when (val result = http.get(url, accessToken)) {
            is GoogleHttpResult.Success -> {
                val person = JSONObject(result.body)
                profile.putIfBlank("display_name", firstName(person))
                profile.putIfBlank("email", firstEmail(person))
                profile.putIfBlank("photo_url", firstPhotoUrl(person))
                firstLocale(person)?.let { profile.putIfBlank("locale", it) }
                profile.put("organizations", organizations(person))
                profile.put("occupations", occupations(person))
                profile.put("locations", locations(person))
                profile.put("relations", relations(person))
                profile.put("biographies", biographies(person))
            }
            is GoogleHttpResult.HttpError -> Unit
            is GoogleHttpResult.Failed -> Unit
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

    private fun firstName(person: JSONObject): String {
        val names = person.optJSONArray("names") ?: JSONArray()
        return (0 until names.length())
            .asSequence()
            .mapNotNull { names.optJSONObject(it)?.optString("displayName")?.takeIf { name -> name.isNotBlank() } }
            .firstOrNull()
            .orEmpty()
    }

    private fun firstEmail(person: JSONObject): String {
        val emails = person.optJSONArray("emailAddresses") ?: JSONArray()
        return (0 until emails.length())
            .asSequence()
            .mapNotNull { emails.optJSONObject(it)?.optString("value")?.takeIf { value -> value.isValidEmailAddress() } }
            .firstOrNull()
            .orEmpty()
    }

    private fun firstPhotoUrl(person: JSONObject): String {
        val photos = person.optJSONArray("photos") ?: JSONArray()
        return (0 until photos.length())
            .asSequence()
            .mapNotNull { photos.optJSONObject(it)?.optString("url")?.takeIf { value -> value.isNotBlank() } }
            .firstOrNull()
            .orEmpty()
    }

    private fun firstLocale(person: JSONObject): String? {
        val locales = person.optJSONArray("locales") ?: return null
        return (0 until locales.length())
            .asSequence()
            .mapNotNull { locales.optJSONObject(it)?.optString("value")?.takeIf { value -> value.isNotBlank() } }
            .firstOrNull()
    }

    private fun organizations(person: JSONObject): JSONArray {
        val organizations = person.optJSONArray("organizations") ?: JSONArray()
        return JSONArray().also { array ->
            for (i in 0 until organizations.length()) {
                val org = organizations.optJSONObject(i) ?: continue
                val parts = listOf(
                    org.optString("name"),
                    org.optString("title"),
                    org.optString("department"),
                ).filter { it.isNotBlank() }
                if (parts.isNotEmpty()) {
                    array.put(parts.joinToString(" | ").take(120))
                }
            }
        }
    }

    private fun occupations(person: JSONObject): JSONArray {
        val occupations = person.optJSONArray("occupations") ?: JSONArray()
        return JSONArray().also { array ->
            for (i in 0 until occupations.length()) {
                occupations.optJSONObject(i)?.optString("value")?.takeIf { it.isNotBlank() }?.let { array.put(it.take(120)) }
            }
        }
    }

    private fun locations(person: JSONObject): JSONArray {
        val locations = person.optJSONArray("locations") ?: JSONArray()
        return JSONArray().also { array ->
            for (i in 0 until locations.length()) {
                val location = locations.optJSONObject(i) ?: continue
                val value = location.optString("value").ifBlank {
                    listOf(location.optString("city"), location.optString("region"), location.optString("country"))
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                }
                if (value.isNotBlank()) {
                    array.put(value.take(120))
                }
            }
        }
    }

    private fun relations(person: JSONObject): JSONArray {
        val relations = person.optJSONArray("relations") ?: JSONArray()
        return JSONArray().also { array ->
            for (i in 0 until relations.length()) {
                val relation = relations.optJSONObject(i) ?: continue
                val personName = relation.optString("person")
                val type = relation.optString("type").ifBlank { relation.optString("formattedType") }
                val label = listOf(personName, type).filter { it.isNotBlank() }.joinToString(" | ")
                if (label.isNotBlank()) {
                    array.put(label.take(120))
                }
            }
        }
    }

    private fun biographies(person: JSONObject): JSONArray {
        val biographies = person.optJSONArray("biographies") ?: JSONArray()
        return JSONArray().also { array ->
            for (i in 0 until biographies.length()) {
                biographies.optJSONObject(i)?.optString("value")?.takeIf { it.isNotBlank() }?.let { array.put(it.take(220)) }
            }
        }
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
        private const val OAUTH_USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"
        private const val PEOPLE_ME_FIELDS = "names,emailAddresses,photos,locales,organizations,occupations,locations,relations,biographies"
        private const val PAGE_SIZE = 500
        private const val MAX_RESULTS = 30
        private const val MAX_FETCHED_CONTACTS = 2_000
        private const val MAX_CHOICE_RESULTS = 4
        private const val SCORE_CONFIDENT = 65
        private const val SCORE_AMBIGUITY_WINDOW = 15
        private val EMAIL_REGEX = Regex("""[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""", RegexOption.IGNORE_CASE)
    }
}

private fun JSONObject.putIfBlank(name: String, value: String) {
    if (optString(name).isBlank() && value.isNotBlank()) {
        put(name, value)
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
