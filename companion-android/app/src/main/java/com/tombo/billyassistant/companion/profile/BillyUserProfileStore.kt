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
            category = "manual",
            topics = BillyProfileContextIndex.topicsForText(normalized),
            keywords = BillyProfileContextIndex.keywordsFor(normalized),
        )
        save(profile.copy(memories = (existing + memory).takeLast(MAX_MEMORIES)))
        return memory
    }

    fun importProfilePack(facts: List<BillyImportedProfileFact>): BillyProfilePackStoreResult {
        val cleanFacts = facts
            .filter { it.fact.isNotBlank() }
            .distinctBy { "${it.path}|${it.fact.lowercase()}" }
            .take(MAX_PROFILE_PACK_FACTS)
        if (cleanFacts.isEmpty()) {
            return BillyProfilePackStoreResult(imported = 0, replaced = 0, sensitive = 0, totalMemories = load().memories.size)
        }
        val profile = load()
        val replaced = profile.memories.count { it.source == PROFILE_PACK_SOURCE }
        val nonPackMemories = profile.memories.filterNot { it.source == PROFILE_PACK_SOURCE }
        val now = System.currentTimeMillis()
        val importedMemories = cleanFacts.map { fact ->
            BillyUserMemory(
                fact = fact.fact.memoryClean().take(MAX_MEMORY_LENGTH),
                source = PROFILE_PACK_SOURCE,
                savedAtMillis = now,
                category = fact.category.take(40),
                path = fact.path.take(120),
                topics = fact.topics.ifEmpty { BillyProfileContextIndex.topicsForPath(fact.path) }.take(12),
                confidence = fact.confidence.take(20),
                sourceHint = fact.sourceHint.take(80),
                lastConfirmed = fact.lastConfirmed.take(32),
                sensitive = fact.sensitive,
                keywords = fact.keywords.ifEmpty { BillyProfileContextIndex.keywordsFor("${fact.path} ${fact.fact}") }.take(24),
            )
        }
        val merged = profile.copy(
            memories = (nonPackMemories + importedMemories).takeLast(MAX_MEMORIES),
            profilePackImportedAtMillis = now,
        )
        save(merged)
        return BillyProfilePackStoreResult(
            imported = importedMemories.size,
            replaced = replaced,
            sensitive = importedMemories.count { it.sensitive },
            totalMemories = merged.memories.size,
        )
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

    fun promptContext(prompt: String? = null): String? {
        val profile = load()
        if (!profile.hasPromptContext()) {
            return null
        }
        val promptTopics = BillyProfileContextIndex.topicsForText(prompt.orEmpty()).toSet()
        val promptKeywords = BillyProfileContextIndex.keywordsFor(prompt.orEmpty()).toSet()
        val selectedMemories = BillyProfileContextIndex.selectMemories(
            memories = profile.memories,
            promptTopics = promptTopics,
            promptKeywords = promptKeywords,
            limit = MAX_PROMPT_MEMORIES,
        )
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
            if (selectedMemories.isNotEmpty()) {
                if (promptTopics.isNotEmpty()) {
                    append("Relevant profile topics: ${promptTopics.joinToString(", ")}\n")
                }
                append("Relevant Billy profile facts:\n")
                selectedMemories.forEach { memory ->
                    append("- ${memory.fact}")
                    val metadata = buildList {
                        memory.category.takeIf { it.isNotBlank() }?.let { add(it) }
                        memory.confidence.takeIf { it.isNotBlank() }?.let { add("confidence $it") }
                        if (memory.sensitive) add("sensitive")
                    }
                    if (metadata.isNotEmpty()) {
                        append(" (${metadata.joinToString("; ")})")
                    }
                    append('\n')
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
        private const val PROFILE_PACK_SOURCE = "profile_pack"
        private const val MAX_MEMORY_LENGTH = 240
        private const val MAX_MEMORIES = 1_000
        private const val MAX_PROFILE_PACK_FACTS = 900
        private const val MAX_PROMPT_MEMORIES = 36
        private const val MAX_PROMPT_CONTEXT_LENGTH = 3800
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
    val profilePackImportedAtMillis: Long = 0L,
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
        val importedFacts = memories.count { it.source == "profile_pack" }
        val manualFacts = memories.size - importedFacts
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
        val packText = if (importedFacts > 0) {
            "Profile Pack imported: $importedFacts facts. Manual memories: $manualFacts."
        } else {
            "No Profile Pack imported."
        }
        return "$identity\n$memoryText\n$packText\n$updated"
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
            .put("profile_pack_imported_at_millis", profilePackImportedAtMillis)
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
                profilePackImportedAtMillis = json.optLong("profile_pack_imported_at_millis", 0L),
            )
        }
    }
}

data class BillyUserMemory(
    val fact: String,
    val source: String,
    val savedAtMillis: Long,
    val category: String = "",
    val path: String = "",
    val topics: List<String> = emptyList(),
    val confidence: String = "",
    val sourceHint: String = "",
    val lastConfirmed: String = "",
    val sensitive: Boolean = false,
    val keywords: List<String> = emptyList(),
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("fact", fact)
            .put("source", source)
            .put("saved_at_millis", savedAtMillis)
            .put("category", category)
            .put("path", path)
            .put("topics", topics.toJsonArray())
            .put("confidence", confidence)
            .put("source_hint", sourceHint)
            .put("last_confirmed", lastConfirmed)
            .put("sensitive", sensitive)
            .put("keywords", keywords.toJsonArray())
    }

    companion object {
        fun fromJson(json: JSONObject): BillyUserMemory {
            val fact = json.optString("fact")
            return BillyUserMemory(
                fact = fact,
                source = json.optString("source", "watch"),
                savedAtMillis = json.optLong("saved_at_millis", 0L),
                category = json.optString("category"),
                path = json.optString("path"),
                topics = json.stringArray("topics").ifEmpty { BillyProfileContextIndex.topicsForText(fact) },
                confidence = json.optString("confidence"),
                sourceHint = json.optString("source_hint"),
                lastConfirmed = json.optString("last_confirmed"),
                sensitive = json.optBoolean("sensitive", false),
                keywords = json.stringArray("keywords").ifEmpty { BillyProfileContextIndex.keywordsFor(fact) },
            )
        }
    }
}

data class BillyProfilePackStoreResult(
    val imported: Int,
    val replaced: Int,
    val sensitive: Int,
    val totalMemories: Int,
)

data class ForgetMemoryResult(
    val removed: List<BillyUserMemory>,
    val remaining: List<BillyUserMemory>,
)

internal object BillyProfileContextIndex {
    fun topicsForPath(path: String): List<String> {
        val root = path.substringBefore('.').lowercase()
        return when (root) {
            "identity" -> listOf("identity", "conversation", "people")
            "household",
            "people" -> listOf("people", "gmail", "calendar", "photos", "conversation")
            "places" -> listOf("maps", "weather", "calendar", "places")
            "routines" -> listOf("calendar", "reminders", "tasks", "weather", "routines")
            "calendar_context" -> listOf("calendar")
            "gmail_context" -> listOf("gmail", "people")
            "tasks_and_reminders_context" -> listOf("tasks", "reminders", "calendar")
            "projects" -> listOf("projects", "drive", "gmail", "calendar")
            "work" -> listOf("work", "calendar", "gmail", "drive", "projects")
            "hobbies_and_interests" -> listOf("hobbies", "conversation", "search", "shopping")
            "preferences" -> listOf("preferences", "conversation", "maps", "weather", "gmail", "calendar")
            "health_and_lifestyle" -> listOf("health", "reminders", "calendar", "conversation")
            "photos_context" -> listOf("photos", "people", "places")
            "drive_docs_context" -> listOf("drive", "docs", "sheets", "slides", "projects")
            "search_and_web_context" -> listOf("search", "web", "shopping", "travel")
            "assistant_behavior" -> listOf("assistant_behavior", "preferences", "conversation")
            "common_phrases_and_intents" -> listOf("phrases", "conversation", "reminders", "calendar")
            "examples" -> listOf("examples", "conversation")
            "memory_management" -> listOf("memory", "conversation")
            else -> listOf(root.ifBlank { "general" })
        }.distinct()
    }

    fun topicsForText(text: String): List<String> {
        val lower = text.lowercase()
        val topics = mutableSetOf<String>()
        if (Regex("""\b(map|maps|direction|directions|navigate|route|walk|drive|bike|transit|near|nearby|home|house|coffee|restaurant|place|station|airport)\b""").containsMatchIn(lower)) topics += "maps"
        if (Regex("""\b(weather|temperature|rain|snow|wind|forecast|umbrella|humidity|hot|cold)\b""").containsMatchIn(lower)) topics += "weather"
        if (Regex("""\b(calendar|event|appointment|meeting|schedule|availability|free|busy|today|tomorrow|next week)\b""").containsMatchIn(lower)) topics += "calendar"
        if (Regex("""\b(email|gmail|mail|inbox|sender|recipient|message|draft|send)\b""").containsMatchIn(lower)) topics += "gmail"
        if (Regex("""\b(task|todo|to-do|complete|check off|remind|reminder|alarm|timer)\b""").containsMatchIn(lower)) topics += listOf("tasks", "reminders")
        if (Regex("""\b(photo|picture|image|camera|screenshot|album|dog|cat|daughter|son|wife|husband|family|friend)\b""").containsMatchIn(lower)) topics += listOf("photos", "people")
        if (Regex("""\b(drive|doc|docs|sheet|sheets|slide|slides|file|folder|presentation|spreadsheet)\b""").containsMatchIn(lower)) topics += listOf("drive", "docs")
        if (Regex("""\b(project|repo|github|code|work on|working on|build|plan)\b""").containsMatchIn(lower)) topics += "projects"
        if (Regex("""\b(work|job|client|coworker|boss|team|office)\b""").containsMatchIn(lower)) topics += "work"
        if (Regex("""\b(hobby|hobbies|interest|interests|game|book|music|movie|tv|sport|learn|learning)\b""").containsMatchIn(lower)) topics += "hobbies"
        if (Regex("""\b(prefer|preference|like|dislike|favorite|style|tone|units)\b""").containsMatchIn(lower)) topics += "preferences"
        if (Regex("""\b(name|who am i|about me|know about me|remember about me|profile)\b""").containsMatchIn(lower)) topics += listOf("identity", "conversation")
        if (topics.isEmpty()) topics += "conversation"
        return topics.toList()
    }

    fun keywordsFor(text: String): List<String> {
        return text
            .lowercase()
            .split(Regex("[^a-z0-9@._+-]+"))
            .map { it.trim('.', '_', '-') }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .distinct()
            .take(40)
    }

    fun selectMemories(
        memories: List<BillyUserMemory>,
        promptTopics: Set<String>,
        promptKeywords: Set<String>,
        limit: Int,
    ): List<BillyUserMemory> {
        if (memories.isEmpty()) {
            return emptyList()
        }
        return memories
            .map { memory -> memory to score(memory, promptTopics, promptKeywords) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<BillyUserMemory, Int>> { it.second }
                    .thenByDescending { it.first.savedAtMillis },
            )
            .take(limit)
            .map { it.first }
    }

    private fun score(
        memory: BillyUserMemory,
        promptTopics: Set<String>,
        promptKeywords: Set<String>,
    ): Int {
        var score = 0
        if (memory.source != "profile_pack") score += 5
        if (memory.topics.any { it in promptTopics }) score += 10
        if (memory.category in listOf("identity", "assistant_behavior")) score += 4
        if (promptTopics.isEmpty() || promptTopics.contains("conversation")) {
            if (memory.topics.any { it in listOf("identity", "people", "preferences", "assistant_behavior") }) score += 3
        }
        val keywordMatches = memory.keywords.count { it in promptKeywords }
        score += (keywordMatches * 3).coerceAtMost(12)
        if (memory.fact.lowercase().split(Regex("[^a-z0-9]+")).any { it in promptKeywords }) score += 2
        if (memory.sensitive && score < 10) score -= 4
        return score
    }

    private val STOP_WORDS = setOf(
        "the",
        "and",
        "for",
        "with",
        "that",
        "this",
        "from",
        "about",
        "what",
        "when",
        "where",
        "which",
        "into",
        "your",
        "you",
        "user",
        "billy",
        "profile",
        "context",
        "value",
    )
}

internal fun String.memoryClean(): String {
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
