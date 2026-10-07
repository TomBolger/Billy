package com.tombo.billyassistant.companion.agent.tools

/** Gemini describes its own UI capabilities; Billy can extract and send its pictures. */
object GeminiImageReply {
    private val displayFailure = Regex(
        """\b(?:can['’]t|cannot|couldn['’]t|unable to|not able to|don['’]t have the ability to)\s+(?:\w+\s+){0,5}(?:show|display|render|load|send|put|bring up)\b|\b(?:show|display|render|load|send)\b.{0,60}\b(?:isn['’]t possible|is not possible|not supported)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val picture = Regex("""\b(?:photo|photos|picture|pictures|image|images|it|them)\b""", RegexOption.IGNORE_CASE)

    fun forWatch(text: String, imageReady: Boolean): String {
        if (!imageReady) return text
        val sentences = text.split(Regex("""(?<=[.!?])\s+|\n+"""))
        val retained = sentences.filterNot { displayFailure.containsMatchIn(it) && picture.containsMatchIn(it) }
        if (retained.size == sentences.size) return text
        // Only remove a contradictory display claim after download and encoding succeeded.
        // Do not infer that the requested subject or date has been verified.
        return retained.joinToString("\n").trim().ifBlank { "Photo from your Gemini account." }
    }
}
