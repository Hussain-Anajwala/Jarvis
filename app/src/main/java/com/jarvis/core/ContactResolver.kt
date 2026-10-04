package com.jarvis.core

import android.content.ContentResolver
import android.provider.ContactsContract

data class ResolvedContact(
    val displayName: String,
    val normalizedPhoneNumber: String
)

sealed interface ContactResolution {
    data class Found(val contact: ResolvedContact) : ContactResolution
    data object NotFound : ContactResolution
    data object MultipleMatches : ContactResolution
    data object QueryFailed : ContactResolution
}

class ContactResolver(private val contentResolver: ContentResolver) {
    fun resolve(name: String): ContactResolution {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ?"
        val selectionArgs = arrayOf("%$name%")
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC"
        ) ?: return ContactResolution.QueryFailed

        val matches = cursor.use {
            val displayNameIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            buildList {
                while (it.moveToNext()) {
                    val displayName = it.getString(displayNameIndex)?.trim().orEmpty()
                    val number = it.getString(numberIndex)?.let(::normalizePhoneNumber).orEmpty()
                    if (displayName.isNotBlank() && number.isNotBlank()) {
                        add(ResolvedContact(displayName, number))
                    }
                }
            }
        }

        val exactMatches = matches.filter { it.displayName.equals(name.trim(), ignoreCase = true) }
        val candidates = exactMatches.ifEmpty {
            matches.filter { it.displayName.contains(name.trim(), ignoreCase = true) }
        }.distinctBy { it.displayName to it.normalizedPhoneNumber }

        return when (candidates.size) {
            0 -> ContactResolution.NotFound
            1 -> ContactResolution.Found(candidates.single())
            else -> ContactResolution.MultipleMatches
        }
    }

    private fun normalizePhoneNumber(number: String): String {
        val trimmed = number.trim()
        val digits = trimmed.filter(Char::isDigit)
        if (digits.isBlank()) return ""
        return when {
            trimmed.startsWith("+") || trimmed.startsWith("＋") -> "+$digits"
            digits.startsWith("00") -> "+${digits.drop(2)}"
            else -> digits
        }
    }
}
