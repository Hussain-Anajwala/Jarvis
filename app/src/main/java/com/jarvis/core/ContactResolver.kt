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

/** Result of resolving a contact's email address on the device; the address never goes to a model. */
sealed interface EmailResolution {
    data class Found(val displayName: String, val address: String) : EmailResolution
    data object NotFound : EmailResolution
    data object MultipleMatches : EmailResolution
    data object QueryFailed : EmailResolution
}

object EmailContactMatcher {
    /** Exact display-name matches win; otherwise a unique "contains" match; several candidates stop. */
    fun choose(name: String, rows: List<Pair<String, String>>): EmailResolution {
        val wanted = name.trim()
        val valid = rows.map { (n, a) -> n.trim() to a.trim() }.filter { (n, a) -> n.isNotBlank() && a.contains("@") }
        val exact = valid.filter { it.first.equals(wanted, ignoreCase = true) }
        val candidates = exact.ifEmpty { valid.filter { it.first.contains(wanted, ignoreCase = true) } }
            .distinctBy { it.first.lowercase() to it.second.lowercase() }
        return when (candidates.size) {
            0 -> EmailResolution.NotFound
            1 -> EmailResolution.Found(candidates.single().first, candidates.single().second)
            else -> EmailResolution.MultipleMatches
        }
    }
}

fun resolveContactEmail(contentResolver: ContentResolver, name: String): EmailResolution {
    val cursor = contentResolver.query(
        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Email.ADDRESS),
        "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} LIKE ?",
        arrayOf("%${name.trim()}%"),
        null
    ) ?: return EmailResolution.QueryFailed
    val rows = cursor.use {
        buildList {
            while (it.moveToNext()) add(it.getString(0).orEmpty() to it.getString(1).orEmpty())
        }
    }
    return EmailContactMatcher.choose(name, rows)
}
