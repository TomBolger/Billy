package com.tombo.billyassistant.companion.profile

data class BillyProfilePackParseResult(
    val facts: List<BillyImportedProfileFact>,
    val warnings: List<String> = emptyList(),
) {
    fun summary(): String {
        val categoryText = facts
            .groupingBy { it.category.ifBlank { "general" } }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(8)
            .joinToString { "${it.key}: ${it.value}" }
        val sensitiveCount = facts.count { it.sensitive }
        return buildString {
            append("Found ${facts.size} profile facts")
            if (sensitiveCount > 0) {
                append(", including $sensitiveCount sensitive facts")
            }
            append(".")
            if (categoryText.isNotBlank()) {
                append("\n$categoryText")
            }
            if (warnings.isNotEmpty()) {
                append("\nWarnings: ${warnings.take(3).joinToString("; ")}")
            }
        }
    }
}

data class BillyImportedProfileFact(
    val fact: String,
    val category: String,
    val path: String,
    val topics: List<String>,
    val confidence: String = "",
    val sourceHint: String = "",
    val lastConfirmed: String = "",
    val sensitive: Boolean = false,
    val keywords: List<String> = emptyList(),
)

object BillyProfilePackParser {
    fun parseMarkdown(markdown: String): BillyProfilePackParseResult {
        val body = extractBoundedBody(markdown)
            ?: return BillyProfilePackParseResult(
                facts = emptyList(),
                warnings = listOf("Missing BEGIN_BILLY_PROFILE_PACK or END_BILLY_PROFILE_PACK markers."),
            )
        val lines = body
            .lineSequence()
            .mapIndexedNotNull { index, raw ->
                val trimmed = raw.trim()
                if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("```")) {
                    null
                } else {
                    PackLine(index = index, indent = raw.takeWhile { it == ' ' }.length, text = trimmed)
                }
            }
            .toList()
        val facts = mutableListOf<BillyImportedProfileFact>()
        val warnings = mutableListOf<String>()
        val pathStack = mutableListOf<PathSegment>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            pathStack.removeAll { it.indent >= line.indent }
            if (line.text.startsWith("- ")) {
                val item = parseListItem(lines, index, pathStack.map { it.name })
                item.fact?.let { facts.add(it) }
                index = item.nextIndex
                continue
            }
            val keyValue = line.text.parseKeyValue()
            if (keyValue == null) {
                warnings += "Skipped line ${line.index + 1}: ${line.text.take(60)}"
                index += 1
                continue
            }
            val key = keyValue.first.cleanKey()
            val value = cleanYamlValue(keyValue.second)
            if (value == null) {
                pathStack += PathSegment(line.indent, key)
                index += 1
                continue
            }
            if (!key.isProfileMetadataKey()) {
                val path = pathStack.map { it.name } + key
                val metadata = siblingMetadata(lines, index, parentIndent = pathStack.lastOrNull()?.indent ?: -1)
                facts += factFromScalar(path, key, value, metadata)
            }
            index += 1
        }
        return BillyProfilePackParseResult(
            facts = facts
                .filter { it.fact.isNotBlank() }
                .distinctBy { "${it.path}|${it.fact.lowercase()}" }
                .take(MAX_IMPORTED_FACTS),
            warnings = warnings,
        )
    }

    private fun extractBoundedBody(markdown: String): String? {
        val start = markdown.indexOf(BEGIN_MARKER)
        val end = markdown.indexOf(END_MARKER)
        if (start < 0 || end < 0 || end <= start) {
            return null
        }
        return markdown.substring(start + BEGIN_MARKER.length, end)
    }

    private fun parseListItem(
        lines: List<PackLine>,
        startIndex: Int,
        parentPath: List<String>,
    ): ParsedItem {
        val start = lines[startIndex]
        var end = startIndex + 1
        while (end < lines.size && lines[end].indent > start.indent) {
            end += 1
        }
        val fields = linkedMapOf<String, String>()
        val metadata = linkedMapOf<String, String>()
        fun addField(key: String, value: String) {
            if (key.isProfileMetadataKey()) {
                metadata[key] = value
            } else {
                fields[key] = value
            }
        }

        val first = start.text.removePrefix("- ").trim()
        val firstKeyValue = first.parseKeyValue()
        if (firstKeyValue == null) {
            cleanYamlValue(first)?.let { fields["value"] = it }
        } else {
            cleanYamlValue(firstKeyValue.second)?.let { addField(firstKeyValue.first.cleanKey(), it) }
        }

        for (i in startIndex + 1 until end) {
            val line = lines[i]
            val keyValue = line.text.parseKeyValue() ?: continue
            val key = keyValue.first.cleanKey()
            val value = cleanYamlValue(keyValue.second) ?: continue
            addField(key, value)
        }

        val nonEmptyFields = fields.filterValues { it.isNotBlank() }
        if (nonEmptyFields.isEmpty()) {
            return ParsedItem(nextIndex = end, fact = null)
        }
        val fact = factFromFields(parentPath, nonEmptyFields, metadata)
        return ParsedItem(nextIndex = end, fact = fact)
    }

    private fun factFromScalar(
        path: List<String>,
        key: String,
        value: String,
        metadata: Map<String, String>,
    ): BillyImportedProfileFact {
        val displayPath = if (key == "value" && path.size > 1) path.dropLast(1) else path
        val label = displayPath.humanPath()
        return importedFact(
            fact = "$label: $value",
            path = displayPath.joinToString("."),
            metadata = metadata,
        )
    }

    private fun factFromFields(
        parentPath: List<String>,
        fields: Map<String, String>,
        metadata: Map<String, String>,
    ): BillyImportedProfileFact {
        val path = parentPath.joinToString(".")
        val label = parentPath.humanPath()
        val orderedFields = fields.entries.sortedWith(
            compareBy<Map.Entry<String, String>> { FIELD_PRIORITY.indexOf(it.key).let { index -> if (index < 0) 99 else index } }
                .thenBy { it.key },
        )
        val summary = if (fields.size == 1 && fields.containsKey("value")) {
            fields.getValue("value")
        } else {
            orderedFields.joinToString("; ") { "${it.key.humanKey()} ${it.value}" }
        }
        return importedFact(
            fact = "$label: $summary",
            path = path,
            metadata = metadata,
        )
    }

    private fun importedFact(
        fact: String,
        path: String,
        metadata: Map<String, String>,
    ): BillyImportedProfileFact {
        val category = path.substringBefore('.').ifBlank { "general" }
        val metadataTopics = metadata["use_for"]?.splitListValue().orEmpty()
        val topics = (BillyProfileContextIndex.topicsForPath(path) + metadataTopics)
            .map { it.cleanTopic() }
            .filter { it.isNotBlank() }
            .distinct()
        return BillyImportedProfileFact(
            fact = fact.memoryClean().take(MAX_FACT_LENGTH),
            category = category,
            path = path,
            topics = topics,
            confidence = metadata["confidence"].orEmpty().take(20),
            sourceHint = metadata["source_hint"].orEmpty().take(80),
            lastConfirmed = metadata["last_confirmed"].orEmpty().take(32),
            sensitive = metadata["sensitive"].equals("true", ignoreCase = true),
            keywords = BillyProfileContextIndex.keywordsFor("$path $fact").take(24),
        )
    }

    private fun siblingMetadata(
        lines: List<PackLine>,
        startIndex: Int,
        parentIndent: Int,
    ): Map<String, String> {
        val metadata = linkedMapOf<String, String>()
        var index = startIndex + 1
        while (index < lines.size && lines[index].indent > parentIndent) {
            val line = lines[index]
            val keyValue = line.text.parseKeyValue()
            if (keyValue != null) {
                val key = keyValue.first.cleanKey()
                if (key.isProfileMetadataKey()) {
                    cleanYamlValue(keyValue.second)?.let { metadata[key] = it }
                }
            }
            index += 1
        }
        return metadata
    }

    private const val BEGIN_MARKER = "BEGIN_BILLY_PROFILE_PACK"
    private const val END_MARKER = "END_BILLY_PROFILE_PACK"
    private const val MAX_IMPORTED_FACTS = 1_000
    private const val MAX_FACT_LENGTH = 240
    private val FIELD_PRIORITY = listOf(
        "name",
        "value",
        "label",
        "relationship",
        "address_or_area",
        "status",
        "project",
        "aliases",
        "visual_description",
        "preferred_interpretation",
        "when_to_ask",
        "source",
    )
}

private data class PackLine(
    val index: Int,
    val indent: Int,
    val text: String,
)

private data class PathSegment(
    val indent: Int,
    val name: String,
)

private data class ParsedItem(
    val nextIndex: Int,
    val fact: BillyImportedProfileFact?,
)

private fun String.parseKeyValue(): Pair<String, String>? {
    val separator = indexOf(':')
    if (separator <= 0) {
        return null
    }
    return substring(0, separator).trim() to substring(separator + 1).trim()
}

private fun String.cleanKey(): String {
    return trim().trim('"', '\'').replace('-', '_')
}

private fun cleanYamlValue(raw: String): String? {
    val stripped = raw.substringBefore(" #").trim().trim('"', '\'')
    if (stripped.isBlank()) {
        return null
    }
    val lower = stripped.lowercase()
    if (
        lower == "null" ||
        lower == "unknown" ||
        lower == "[]" ||
        lower == "{}" ||
        lower == "n/a" ||
        lower == "none"
    ) {
        return null
    }
    if (lower == "false") {
        return null
    }
    return stripped
        .removeSurrounding("[", "]")
        .splitListValue()
        .joinToString(", ")
        .ifBlank { null }
}

private fun String.splitListValue(): List<String> {
    return split(',')
        .map { it.trim().trim('"', '\'') }
        .filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) && !it.equals("unknown", ignoreCase = true) }
}

private fun String.isProfileMetadataKey(): Boolean {
    return this in setOf("confidence", "source_hint", "last_confirmed", "sensitive", "use_for")
}

private fun String.cleanTopic(): String {
    return lowercase()
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
}

private fun List<String>.humanPath(): String {
    return filter { it.isNotBlank() }
        .joinToString(" ") { it.humanKey() }
        .ifBlank { "profile" }
}

private fun String.humanKey(): String {
    return replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
}
