package com.tombo.billyassistant.companion.profile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class BillyUserProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): BillyUserProfile {
        val raw = preferences.getString(KEY_PROFILE, null)?.takeIf { it.isNotBlank() }
            ?: return BillyUserProfile()
        return runCatching { BillyUserProfile.fromJson(JSONObject(raw)) }.getOrDefault(BillyUserProfile())
    }

    fun shouldAttemptGoogleProfileHydration(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val profile = load()
        val lastAttemptAtMillis = preferences.getLong(KEY_GOOGLE_PROFILE_ATTEMPT_AT, 0L)
        val lastSuccessfulAtMillis = profile.googleProfileUpdatedAtMillis
        val newestAttemptAtMillis = maxOf(lastAttemptAtMillis, lastSuccessfulAtMillis)
        val retryIntervalMillis = if (profile.displayName.isBlank() && profile.email.isBlank()) {
            EMPTY_PROFILE_RETRY_INTERVAL_MILLIS
        } else {
            LOADED_PROFILE_REFRESH_INTERVAL_MILLIS
        }
        return newestAttemptAtMillis <= 0L || nowMillis - newestAttemptAtMillis >= retryIntervalMillis
    }

    fun markGoogleProfileHydrationAttempt(nowMillis: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putLong(KEY_GOOGLE_PROFILE_ATTEMPT_AT, nowMillis)
            .apply()
    }

    fun mergeGoogleProfile(payload: JSONObject): BillyUserProfile {
        val incoming = payload.optJSONObject("profile") ?: payload
        val current = load()
        val merged = current.copy(
            displayName = incoming.optString("display_name").ifBlank { current.displayName },
            email = incoming.optString("email").ifBlank { current.email },
            locale = incoming.optString("locale").ifBlank { current.locale },
            photoUrl = incoming.optString("photo_url").ifBlank { current.photoUrl },
            organizations = incoming.stringArray("organizations").ifEmpty { current.organizations },
            occupations = incoming.stringArray("occupations").ifEmpty { current.occupations },
            locations = incoming.stringArray("locations").ifEmpty { current.locations },
            relations = incoming.stringArray("relations").ifEmpty { current.relations },
            biographies = incoming.stringArray("biographies").ifEmpty { current.biographies },
            googleProfileUpdatedAtMillis = System.currentTimeMillis(),
        )
        save(merged)
        return merged
    }

    fun addMemory(fact: String, source: String = "watch"): BillyUserMemory? {
        val normalized = fact.memoryClean().take(MAX_MEMORY_LENGTH)
        if (normalized.isBlank()) {
            return null
        }
        val profile = load()
        val existing = profile.memories.filterNot {
            it.fact.equals(normalized, ignoreCase = true)
        }
        val memory = BillyUserMemory(
            fact = normalized,
            source = source.memoryClean().ifBlank { "watch" }.take(40),
            savedAtMillis = System.currentTimeMillis(),
        )
        save(profile.copy(memories = (existing + memory).takeLast(MAX_MEMORIES)))
        return memory
    }

    fun forgetMemory(query: String): ForgetMemoryResult {
        val cleanQuery = query.memoryClean()
        if (cleanQuery.isBlank()) {
            return ForgetMemoryResult(removed = emptyList(), remaining = load().memories)
        }
        val profile = load()
        val removed = profile.memories.filter { memory ->
            memory.fact.contains(cleanQuery, ignoreCase = true) ||
                cleanQuery.contains(memory.fact, ignoreCase = true)
        }
        if (removed.isNotEmpty()) {
            save(profile.copy(memories = profile.memories - removed.toSet()))
        }
        return ForgetMemoryResult(removed = removed, remaining = load().memories)
    }

    fun clear() {
        preferences.edit().remove(KEY_PROFILE).apply()
    }

    fun save(profile: BillyUserProfile) {
        preferences.edit()
            .putString(KEY_PROFILE, profile.toJson().toString())
            .apply()
    }

    fun promptContext(): String? {
        val profile = load()
        if (!profile.hasPromptContext()) {
            return null
        }
        return buildString {
            append("Billy user profile and memory. This is durable local context from Billy Companion. ")
            append("Use it when relevant, but do not reveal or dwell on it unless the user asks.\n")
            profile.displayName.takeIf { it.isNotBlank() }?.let { append("Name: $it\n") }
            profile.email.takeIf { it.isNotBlank() }?.let { append("Google account email: $it\n") }
            profile.locale.takeIf { it.isNotBlank() }?.let { append("Locale: $it\n") }
            appendList("Organizations", profile.organizations)
            appendList("Occupations", profile.occupations)
            appendList("Locations", profile.locations)
            appendList("Relations", profile.relations)
            appendList("Profile notes", profile.biographies)
            if (profile.memories.isNotEmpty()) {
                append("Billy memories:\n")
                profile.memories.takeLast(MAX_PROMPT_MEMORIES).forEach { memory ->
                    append("- ${memory.fact}\n")
                }
            }
        }.trim().take(MAX_PROMPT_CONTEXT_LENGTH)
    }

    fun homeLocationHint(): String? {
        val profile = load()
        val candidates = profile.locations +
            profile.memories.map { it.fact } +
            profile.biographies
        return candidates
            .map { it.memoryClean() }
            .firstOrNull { it.looksLikeConcreteHomeLocation() }
            ?.take(180)
    }

    companion object {
        private const val PREFERENCES_NAME = "billy_user_profile"
        private const val KEY_PROFILE = "profile_json"
        private const val KEY_GOOGLE_PROFILE_ATTEMPT_AT = "google_profile_attempt_at_millis"
        private const val MAX_MEMORY_LENGTH = 180
        private const val MAX_MEMORIES = 40
        private const val MAX_PROMPT_MEMORIES = 18
        private const val MAX_PROMPT_CONTEXT_LENGTH = 1800
        private const val EMPTY_PROFILE_RETRY_INTERVAL_MILLIS = 10 * 60 * 1000L
        private const val LOADED_PROFILE_REFRESH_INTERVAL_MILLIS = 7 * 24 * 60 * 60 * 1000L
    }
}

data class BillyUserProfile(
    val displayName: String = "",
    val email: String = "",
    val locale: String = "",
    val photoUrl: String = "",
    val organizations: List<String> = emptyList(),
    val occupations: List<String> = emptyList(),
    val locations: List<String> = emptyList(),
    val relations: List<String> = emptyList(),
    val biographies: List<String> = emptyList(),
    val memories: List<BillyUserMemory> = emptyList(),
    val googleProfileUpdatedAtMillis: Long = 0L,
) {
    fun hasPromptContext(): Boolean {
        return listOf(displayName, email, locale, photoUrl).any { it.isNotBlank() } ||
            organizations.isNotEmpty() ||
            occupations.isNotEmpty() ||
            locations.isNotEmpty() ||
            relations.isNotEmpty() ||
            biographies.isNotEmpty() ||
            memories.isNotEmpty()
    }

    fun statusSummary(): String {
        if (!hasPromptContext()) {
            return "No Billy profile or memory is stored yet."
        }
        val identity = displayName.ifBlank { email.ifBlank { "Google profile loaded" } }
        val memoryText = when (memories.size) {
            0 -> "No saved Billy memories."
            1 -> "1 saved Billy memory."
            else -> "${memories.size} saved Billy memories."
        }
        val updated = if (googleProfileUpdatedAtMillis > 0L) {
            "Google profile loaded."
        } else {
            "Google profile not loaded."
        }
        return "$identity\n$memoryText\n$updated"
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("display_name", displayName)
            .put("email", email)
            .put("locale", locale)
            .put("photo_url", photoUrl)
            .put("organizations", organizations.toJsonArray())
            .put("occupations", occupations.toJsonArray())
            .put("locations", locations.toJsonArray())
            .put("relations", relations.toJsonArray())
            .put("biographies", biographies.toJsonArray())
            .put("memories", JSONArray().also { array -> memories.forEach { array.put(it.toJson()) } })
            .put("google_profile_updated_at_millis", googleProfileUpdatedAtMillis)
    }

    companion object {
        fun fromJson(json: JSONObject): BillyUserProfile {
            val memoriesArray = json.optJSONArray("memories") ?: JSONArray()
            val memories = buildList {
                for (i in 0 until memoriesArray.length()) {
                    memoriesArray.optJSONObject(i)?.let { add(BillyUserMemory.fromJson(it)) }
                }
            }.filter { it.fact.isNotBlank() }
            return BillyUserProfile(
                displayName = json.optString("display_name"),
                email = json.optString("email"),
                locale = json.optString("locale"),
                photoUrl = json.optString("photo_url"),
                organizations = json.stringArray("organizations"),
                occupations = json.stringArray("occupations"),
                locations = json.stringArray("locations"),
                relations = json.stringArray("relations"),
                biographies = json.stringArray("biographies"),
                memories = memories,
                googleProfileUpdatedAtMillis = json.optLong("google_profile_updated_at_millis", 0L),
            )
        }
    }
}

data class BillyUserMemory(
    val fact: String,
    val source: String,
    val savedAtMillis: Long,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("fact", fact)
            .put("source", source)
            .put("saved_at_millis", savedAtMillis)
    }

    companion object {
        fun fromJson(json: JSONObject): BillyUserMemory {
            return BillyUserMemory(
                fact = json.optString("fact"),
                source = json.optString("source", "watch"),
                savedAtMillis = json.optLong("saved_at_millis", 0L),
            )
        }
    }
}

data class ForgetMemoryResult(
    val removed: List<BillyUserMemory>,
    val remaining: List<BillyUserMemory>,
)

private fun String.memoryClean(): String {
    return replace(Regex("[\\r\\n]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun String.looksLikeConcreteHomeLocation(): Boolean {
    val lower = lowercase()
    val hasHomeWord = Regex("""\b(home|house|address|live|living|place)\b""").containsMatchIn(lower)
    val hasStreetNumber = Regex("""\b\d{1,6}\b""").containsMatchIn(lower)
    val hasAddressWord = Regex("""\b(st|street|ave|avenue|rd|road|dr|drive|ln|lane|way|blvd|boulevard|ct|court|pl|place|apt|apartment|unit)\b""").containsMatchIn(lower)
    val hasCoordinate = Regex("""-?\d{1,3}\.\d{3,}\s*,\s*-?\d{1,3}\.\d{3,}""").containsMatchIn(lower)
    return hasCoordinate || (hasHomeWord && hasStreetNumber && hasAddressWord)
}

private fun JSONObject.stringArray(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            array.optString(i).memoryClean().takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }.distinct()
}

private fun List<String>.toJsonArray(): JSONArray {
    return JSONArray().also { array -> forEach { array.put(it) } }
}

private fun StringBuilder.appendList(label: String, values: List<String>) {
    val cleanValues = values.map { it.memoryClean() }.filter { it.isNotBlank() }.distinct().take(6)
    if (cleanValues.isNotEmpty()) {
        append("$label: ${cleanValues.joinToString("; ")}\n")
    }
}
