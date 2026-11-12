package com.tombo.billyassistant.companion

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/** Small text logs that help diagnose odd answers. They stay on the phone unless the user saves them. */
object DebugFiles {
    fun lastTurn(context: Context) = File(context.applicationContext.filesDir, "billy-last-turn.txt")

    fun startTurn(context: Context, question: String) {
        runCatching { lastTurn(context).writeText("=== question ===\n$question\n\n") }
    }

    fun note(context: Context, text: String) {
        runCatching {
            val file = lastTurn(context)
            if (file.length() < 300_000) file.appendText(text.trimEnd() + "\n\n")
        }
    }

    /** Saves the given logs into one text file in Downloads. Returns its name, or null. */
    fun exportToDownloads(context: Context, prefix: String, parts: List<Pair<String, File>>): String? = runCatching {
        if (Build.VERSION.SDK_INT < 29) return null
        val present = parts.filter { it.second.exists() }
        if (present.isEmpty()) return null
        val name = "$prefix-${System.currentTimeMillis() / 1000}.txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out ->
            present.forEach { (title, file) ->
                out.write("##### $title #####\n".toByteArray())
                file.inputStream().use { it.copyTo(out) }
                out.write("\n\n".toByteArray())
            }
        }
        name
    }.getOrNull()
}
