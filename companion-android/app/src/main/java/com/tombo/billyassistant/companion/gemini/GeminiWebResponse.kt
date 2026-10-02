package com.tombo.billyassistant.companion.gemini

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the gemini.google.com StreamGenerate response.
 *
 * The body is ")]}'" followed by length-prefixed JSON frames. Each frame is an
 * array of envelopes; an envelope ["wrb.fr", null, "<json>"] carries the reply,
 * whose [4][0] is the first candidate: [1][0] = text, [12] = rich content
 * (field 1 = web images). Later frames repeat the text cumulatively, so the
 * last non-empty text wins. Layout follows the open-source Gemini-API project
 * (github.com/HanaokaYuzu/Gemini-API); if Google changes it, parse() returns an
 * error and Billy falls back to its normal tools.
 */
object GeminiWebResponse {
    data class Parsed(val text: String, val imageUrls: List<String>, val errorCode: Int?)

    private val ARTIFACTS = Regex("""https?://googleusercontent\.com/(?:\w+/)+\d+\n*""")
    private val CITES = Regex("""\s*\[cite(?::[^\]]*)?]""")
    private val URL = Regex("""https://[^\s"'<>()\[\]\\]+""")

    fun parse(body: String): Parsed {
        var text = ""
        val images = LinkedHashSet<String>()
        val web = LinkedHashSet<String>()
        var errorCode: Int? = null
        envelopes(body).forEach { envelope ->
            var code = nested(envelope, 5, 2, 0, 1, 0)
            if (code is JSONArray) code = nested(code, 0)
            (code as? Number)?.toInt()?.let { errorCode = it }
            val innerText = envelope.optString(2).takeIf { envelope.optString(0) == "wrb.fr" && it.startsWith("[") } ?: return@forEach
            val inner = runCatching { JSONArray(innerText) }.getOrNull() ?: return@forEach
            // Photos from the user's library, generated images, and anything else
            // pictured can sit in several places; collect every image URL.
            collectStrings(inner).forEach { value -> URL.findAll(value).forEach { found -> images += found.value } }
            val candidate = (nested(inner, 4, 0) as? JSONArray) ?: return@forEach
            (nested(candidate, 1, 0) as? String)?.takeIf { it.isNotBlank() }?.let { text = it }
            richField(candidate, 1)?.let { webImages ->
                for (i in 0 until webImages.length()) {
                    (nested(webImages.optJSONArray(i), 0, 0, 0) as? String)?.let { web += it }
                }
            }
        }
        val clean = text
            .replace(ARTIFACTS, "")
            .replace(CITES, "")
            .trim()
        val ranked = (images.filter(::isPicture).sortedBy(::rank) + web).distinct()
        return Parsed(clean, ranked.take(6), errorCode)
    }

    private fun collectStrings(node: Any?, out: MutableList<String> = mutableListOf()): List<String> {
        when (node) {
            is String -> if (node.contains("https://")) out += node
            is JSONArray -> for (i in 0 until node.length()) collectStrings(node.opt(i), out)
            is JSONObject -> node.keys().forEach { collectStrings(node.opt(it), out) }
        }
        return out
    }

    /** Picture hosts Gemini uses; account avatars and site icons are skipped. */
    private fun isPicture(url: String): Boolean {
        val host = url.removePrefix("https://").substringBefore('/').lowercase()
        val path = url.removePrefix("https://").substringAfter('/', "")
        val imageHost = host.matches(Regex("""lh\d+\.googleusercontent\.com""")) ||
            host.endsWith(".usercontent.google.com") ||
            host.matches(Regex("""encrypted-tbn\d\.gstatic\.com""")) ||
            host.endsWith(".ggpht.com")
        if (!imageHost) return false
        if (path.startsWith("a/") || path.startsWith("a-/") || path.startsWith("ogw/")) return false
        return !url.contains("favicon", ignoreCase = true) && !url.endsWith(".svg", ignoreCase = true)
    }

    /** The user's own photos first, then generated images, then web pictures. */
    private fun rank(url: String): Int = when {
        url.contains(".usercontent.google.com/") || url.contains("googleusercontent.com/pw/") -> 0
        url.contains("googleusercontent.com/gg") || url.contains("/gg-dl/") -> 1
        url.contains("encrypted-tbn") -> 3
        else -> 2
    }

    /** All envelope arrays in the body, whatever the frame lengths say. */
    private fun envelopes(body: String): List<JSONArray> {
        val out = mutableListOf<JSONArray>()
        body.removePrefix(")]}'").lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("[")) return@forEach
            val frame = runCatching { JSONArray(trimmed) }.getOrNull() ?: return@forEach
            for (i in 0 until frame.length()) {
                frame.optJSONArray(i)?.let { out += it }
            }
        }
        return out
    }

    private fun nested(root: JSONArray?, vararg path: Int): Any? {
        var current: Any? = root
        for (index in path) {
            val array = current as? JSONArray ?: return null
            if (index !in 0 until array.length() || array.isNull(index)) return null
            current = array.get(index)
        }
        return current
    }

    /** Field [index] of a candidate's [12] block: positional, or in a trailing sparse dict keyed index+1. */
    private fun richField(candidate: JSONArray, index: Int): JSONArray? {
        val block = candidate.optJSONArray(12) ?: return null
        block.optJSONArray(index)?.takeIf { it.length() > 0 }?.let { return it }
        for (i in block.length() - 1 downTo 0) {
            val sparse = block.opt(i) as? JSONObject ?: continue
            return sparse.optJSONArray((index + 1).toString())
        }
        return null
    }
}
