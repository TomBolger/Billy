package com.tombo.billyassistant.companion.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/** Looks up people in the phone's own contacts (no Google sign-in needed). */
object PhoneContacts {
    data class Match(val name: String, val number: String, val label: String)

    fun canRead(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Numbers for contacts whose name matches [query], best matches first. */
    fun find(context: Context, query: String): List<Match> {
        val wanted = query.trim()
        if (wanted.isEmpty() || !canRead(context)) return emptyList()
        if (wanted.count { it.isDigit() } >= 3 && wanted.all { it.isDigit() || it in "+-() ." }) {
            return listOf(Match(wanted, wanted, "number"))
        }
        val results = mutableListOf<Match>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%${wanted.replace("%", "")}%"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext() && results.size < 20) {
                val name = cursor.getString(0).orEmpty()
                val number = cursor.getString(1).orEmpty()
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                    context.resources, cursor.getInt(2), cursor.getString(3),
                ).toString().lowercase()
                if (number.isNotBlank()) results += Match(name, number, label)
            }
        }
        val lower = wanted.lowercase()
        return results
            .distinctBy { it.number.filter(Char::isDigit).takeLast(10) }
            .sortedWith(
                compareByDescending<Match> { it.name.lowercase() == lower }
                    .thenByDescending { it.name.lowercase().startsWith(lower) }
                    .thenByDescending { it.label == "mobile" }
                    .thenBy { it.name.length },
            )
    }
}
