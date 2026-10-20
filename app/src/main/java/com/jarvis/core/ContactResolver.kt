package com.jarvis.core

import android.content.ContentResolver
import android.content.res.Resources
import android.provider.ContactsContract
import java.text.Normalizer
import java.util.Locale

/** One callable person-and-number. [numberLabel] ("Mobile", "Work") helps tell several numbers apart. */
data class ResolvedContact(
    val displayName: String,
    val normalizedPhoneNumber: String,
    val numberLabel: String? = null
)

sealed interface ContactResolution {
    data class Found(val contact: ResolvedContact) : ContactResolution
    /**
     * Several people or numbers fit; the user picks one. Never resolved automatically. [suggestions] means none matched
     * the name exactly and these are near spellings ("Maa" for "Ma"), offered as "did you mean".
     */
    data class Ambiguous(val options: List<ResolvedContact>, val suggestions: Boolean = false) : ContactResolution
    data object NotFound : ContactResolution
    data object QueryFailed : ContactResolution
}

/** A phone row read from Android Contacts. */
data class ContactRow(val contactId: Long, val displayName: String, val number: String, val label: String? = null)

/**
 * Name normalisation shared by every contact lookup: Unicode NFKC, lower case, accents removed from Latin letters,
 * emoji / punctuation / symbols treated as separators, runs of whitespace collapsed. Letters and digits of any script
 * are kept (with their combining marks, so Devanagari or Tamil names still compare correctly).
 *
 * "Ma ❤️" → "ma", "Dr.  Asha   Rao" → "dr asha rao", "D'Souza" → "d souza", "José" → "jose".
 */
object ContactNames {
    fun normalize(name: String): String {
        val text = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val out = StringBuilder()
        var afterLetter = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val type = Character.getType(cp)
            val isMark = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
            when {
                Character.isLetterOrDigit(cp) -> { out.appendCodePoint(cp); afterLetter = true }
                // Variation selectors (U+FE0E/F), the keycap mark and ZWJ belong to emoji, not to names.
                isMark && afterLetter && cp != 0xFE0F && cp != 0xFE0E && cp != 0x20E3 -> out.appendCodePoint(cp)
                else -> { out.append(' '); afterLetter = false }
            }
            i += Character.charCount(cp)
        }
        return foldLatinAccents(out.toString()).trim().replace(WHITESPACE, " ")
    }

    /** "José" and "Jose" match; marks on non-Latin letters are part of the spelling and stay. */
    private fun foldLatinAccents(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder()
        var base: Int? = null
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            val isMark = Character.getType(cp) == Character.NON_SPACING_MARK.toInt()
            val baseIsLatin = base?.let { Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN } == true
            if (!(isMark && baseIsLatin)) out.appendCodePoint(cp)
            if (!isMark) base = cp
            i += Character.charCount(cp)
        }
        return Normalizer.normalize(out.toString(), Normalizer.Form.NFC)
    }

    fun tokens(name: String): List<String> = normalize(name).split(' ').filter(String::isNotEmpty)

    /** The saved name without emoji or decoration, keeping its capitalisation ("Ma ❤️" → "Ma"), for greetings. */
    fun displayName(name: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            val type = Character.getType(cp)
            val keep = Character.isLetterOrDigit(cp) || Character.isWhitespace(cp) || cp == '.'.code || cp == '\''.code || cp == '-'.code ||
                ((type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()) && cp != 0xFE0F && cp != 0xFE0E)
            out.appendCodePoint(if (keep) cp else ' '.code)
            i += Character.charCount(cp)
        }
        return out.toString().trim().replace(WHITESPACE, " ").ifBlank { name.trim() }
    }

    private val WHITESPACE = Regex("\\s+")
}

/**
 * Picks the contact a spoken or typed name refers to, from actual Android contacts with phone numbers.
 *
 * 1. Exact: the whole normalised name equals the request (spaces ignored, so "Rahul Sharma" = "RahulSharma").
 * 2. Whole words: every word of the request is a whole word of the name ("Rahul" → "Rahul Sharma"), never a part of a
 *    word ("Ma" does not match "Mahesh").
 * 3. Near spelling: every word of the request is close to a word of the name: the same after doubled letters and
 *    common transliteration differences ("Ma" / "Maa", "Yousuf" / "Yusuf", "Farhan" / "Pharhan"), or, for 4+ letters,
 *    one edit apart ("Anjwala" / "Anajwala"). These are only ever offered as "Did you mean?" choices, never chosen
 *    automatically.
 *
 * One person with one number resolves; several people or several numbers become a choice list.
 */
object ContactMatcher {
    fun match(query: String, rows: List<ContactRow>): ContactResolution {
        val wanted = ContactNames.tokens(query)
        if (wanted.isEmpty()) return ContactResolution.NotFound
        val usable = rows.mapNotNull { row ->
            val number = PhoneNumbers.normalize(row.number)
            if (number.isBlank() || row.displayName.isBlank()) null else row.copy(number = number)
        }
        val people = usable.groupBy { it.contactId }
        fun nameOf(group: List<ContactRow>) = group.first().displayName
        val squashed = wanted.joinToString("")

        val exact = people.filterValues { group ->
            val tokens = ContactNames.tokens(nameOf(group))
            tokens == wanted || tokens.joinToString("") == squashed
        }
        val words = if (exact.isNotEmpty()) exact else people.filterValues { group ->
            ContactNames.tokens(nameOf(group)).containsAll(wanted)
        }
        if (words.isNotEmpty()) return resolve(words.values.flatten(), autoPick = true)

        if (wanted.all { it.length >= 2 }) {
            val near = people.filterValues { group ->
                val tokens = ContactNames.tokens(nameOf(group))
                wanted.all { word -> tokens.any { token -> sounds(token, word) } }
            }
            if (near.isNotEmpty()) return resolve(near.values.flatten(), autoPick = false)
        }
        return ContactResolution.NotFound
    }

    /** Two spellings a speech recogniser could plausibly produce for the same spoken name word. */
    internal fun sounds(token: String, word: String): Boolean {
        if (collapseRepeats(token) == collapseRepeats(word)) return true
        val a = phonetic(token)
        val b = phonetic(word)
        return a == b || (word.length >= 4 && (editDistanceAtMostOne(token, word) || editDistanceAtMostOne(a, b)))
    }

    /**
     * A rough sound key for Latin-script Indian and English names: "Yousuf" and "Yusuf", "Mohammad" and "Muhamad",
     * "Pharhan" and "Farhan", "Sheetal" and "Shital" share a key. Other scripts are left as they are.
     */
    internal fun phonetic(word: String): String {
        if (word.any { it !in 'a'..'z' }) return collapseRepeats(word)
        val key = word
            .replace("ph", "f").replace("ou", "u").replace("oo", "u").replace("ee", "i").replace("ea", "i")
            .replace("w", "v").replace("q", "k").replace("ck", "k").replace("z", "j")
            .replace(Regex("([bcdgjkpst])h"), "$1")
            .replace(Regex("(?<=.)y"), "i")
            .replace(Regex("(?<=[^aeiou])o(?=[^aeiou])"), "u")
        return collapseRepeats(key)
    }

    private fun resolve(rows: List<ContactRow>, autoPick: Boolean): ContactResolution {
        // The same person saved twice (e.g. phone and Google account) with the same number is one option.
        val options = rows
            .distinctBy { ContactNames.normalize(it.displayName) to PhoneNumbers.key(it.number) }
            .sortedWith(compareBy({ ContactNames.normalize(it.displayName) }, { it.label.orEmpty() }))
            .map { ResolvedContact(it.displayName.trim(), it.number, it.label?.takeIf(String::isNotBlank)) }
        return when {
            options.isEmpty() -> ContactResolution.NotFound
            options.size == 1 && autoPick -> ContactResolution.Found(options.single())
            else -> ContactResolution.Ambiguous(options, suggestions = !autoPick)
        }
    }

    /** "maa" → "ma", "adittya" → "aditya": spellings of one spoken name often differ only by a doubled letter. */
    internal fun collapseRepeats(word: String): String {
        val out = StringBuilder()
        for (c in word) if (out.isEmpty() || out.last() != c) out.append(c)
        return out.toString()
    }

    internal fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}

object PhoneNumbers {
    /** Digits with a leading + kept for international numbers; "" when there are no digits. */
    fun normalize(number: String): String {
        val trimmed = number.trim()
        val digits = trimmed.filter(Char::isDigit)
        if (digits.isBlank()) return ""
        return when {
            trimmed.startsWith("+") || trimmed.startsWith("＋") -> "+$digits"
            digits.startsWith("00") -> "+${digits.drop(2)}"
            else -> digits
        }
    }

    /** The last 10 digits: "+91 98765 43210" and "098765 43210" are the same number. */
    fun key(number: String): String = number.filter(Char::isDigit).takeLast(10)
}

class ContactResolver(private val contentResolver: ContentResolver, private val resources: Resources? = null) {
    fun resolve(name: String): ContactResolution {
        val rows = readPhoneRows() ?: return ContactResolution.QueryFailed
        return ContactMatcher.match(name, rows)
    }

    /** All contacts with a phone number. Matching happens in Kotlin because SQL LIKE cannot ignore emoji or punctuation. */
    private fun readPhoneRows(): List<ContactRow>? {
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL
            ),
            null, null, null
        ) ?: return null
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val type = it.getInt(3)
                    val custom = it.getString(4)
                    val label = resources?.let { res ->
                        ContactsContract.CommonDataKinds.Phone.getTypeLabel(res, type, custom)?.toString()
                    } ?: custom
                    add(ContactRow(it.getLong(0), it.getString(1).orEmpty(), it.getString(2).orEmpty(), label))
                }
            }
        }
    }
}

/** Result of resolving a contact's email address on the device; the address never goes to a model. */
sealed interface EmailResolution {
    data class Found(val displayName: String, val address: String) : EmailResolution
    data class Ambiguous(val options: List<Found>) : EmailResolution
    data object NotFound : EmailResolution
    data object QueryFailed : EmailResolution
}

object EmailContactMatcher {
    /** Same rules as phone contacts: exact normalised name, then whole words; several candidates become a choice. */
    fun choose(name: String, rows: List<Pair<String, String>>): EmailResolution {
        val wanted = ContactNames.tokens(name)
        if (wanted.isEmpty()) return EmailResolution.NotFound
        val valid = rows.map { (n, a) -> n.trim() to a.trim() }.filter { (n, a) -> n.isNotBlank() && a.contains("@") }
        val exact = valid.filter { ContactNames.tokens(it.first) == wanted || ContactNames.tokens(it.first).joinToString("") == wanted.joinToString("") }
        val candidates = exact.ifEmpty { valid.filter { ContactNames.tokens(it.first).containsAll(wanted) } }
            .distinctBy { ContactNames.normalize(it.first) to it.second.lowercase(Locale.ROOT) }
        return when (candidates.size) {
            0 -> EmailResolution.NotFound
            1 -> EmailResolution.Found(candidates.single().first, candidates.single().second)
            else -> EmailResolution.Ambiguous(candidates.map { EmailResolution.Found(it.first, it.second) })
        }
    }
}

fun resolveContactEmail(contentResolver: ContentResolver, name: String): EmailResolution {
    val cursor = contentResolver.query(
        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Email.ADDRESS),
        null, null, null
    ) ?: return EmailResolution.QueryFailed
    val rows = cursor.use {
        buildList {
            while (it.moveToNext()) add(it.getString(0).orEmpty() to it.getString(1).orEmpty())
        }
    }
    return EmailContactMatcher.choose(name, rows)
}
