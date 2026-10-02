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
    private val IMAGE_URL = Regex("""https://(?:lh\d|encrypted-tbn\d)\.(?:googleusercontent|gstatic)\.com/[A-Za-z0-9_\-./=?&%:]+""")

    fun parse(body: String): Parsed {
        var text = ""
        val images = LinkedHashSet<String>()
        var errorCode: Int? = null
        envelopes(body).forEach { envelope ->
            var code = nested(envelope, 5, 2, 0, 1, 0)
            if (code is JSONArray) code = nested(code, 0)
            (code as? Number)?.toInt()?.let { errorCode = it }
            val innerText = envelope.optString(2).takeIf { envelope.optString(0) == "wrb.fr" && it.startsWith("[") } ?: return@forEach
            val inner = runCatching { JSONArray(innerText) }.getOrNull() ?: return@forEach
            val candidate = (nested(inner, 4, 0) as? JSONArray) ?: return@forEach
            (nested(candidate, 1, 0) as? String)?.takeIf { it.isNotBlank() }?.let { text = it }
            richField(candidate, 1)?.let { webImages ->
                for (i in 0 until webImages.length()) {
                    (nested(webImages.optJSONArray(i), 0, 0, 0) as? String)?.let { images += it }
                }
            }
            IMAGE_URL.findAll(innerText).forEach { images += it.value.replace("\\u003d", "=").replace("\\u0026", "&") }
        }
        val clean = text
            .replace(ARTIFACTS, "")
            .replace(CITES, "")
            .trim()
        return Parsed(clean, images.toList().take(6), errorCode)
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
