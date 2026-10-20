package com.jarvis.core

import com.jarvis.data.Memory
import java.util.Locale

/**
 * Credentials and identifiers that must never be stored as memories, sent to a model, or written to a trace:
 * passwords, PINs, OTPs, CVVs, card numbers (Luhn-checked), Aadhaar and PAN numbers, and API keys/tokens.
 */
object SensitiveData {
    private val labelled = Regex(
        """\b(password|passcode|pass\s?word|pin|otp|one[- ]time (pass)?code|cvv|cvc|security code|api[ _-]?key|secret key|access token|auth token|upi pin|net ?banking)\b""",
        RegexOption.IGNORE_CASE
    )
    private val cardLike = Regex("""\b(?:\d[ -]?){13,19}\b""")
    private val aadhaar = Regex("""\b\d{4}\s?\d{4}\s?\d{4}\b""")
    private val pan = Regex("""\b[A-Z]{5}\d{4}[A-Z]\b""")
    private val keyLike = Regex("""\b(sk|gsk|AIza|ghp|xox[bap])[-_A-Za-z0-9]{16,}\b""")

    fun isSecret(text: String): Boolean =
        labelled.containsMatchIn(text) || pan.containsMatchIn(text) || keyLike.containsMatchIn(text) ||
            aadhaar.containsMatchIn(text) || cardLike.findAll(text).any { luhn(it.value.filter(Char::isDigit)) }

    internal fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }

    const val REFUSAL = "I don't store passwords, PINs, OTPs, card numbers, or ID numbers. Keep those in a password manager."
}

/** What a request about the user's memories should do. */
sealed interface MemoryQuery {
    /** An explicit request to review everything ("show all my memories"). */
    data object ReviewAll : MemoryQuery
    /** A specific topic ("my favourite song", "Asha"): only matching memories. */
    data class Topic(val query: String) : MemoryQuery
    /** Ambiguous ("tell me about me"): ask what they want instead of listing everything. */
    data object Clarify : MemoryQuery
}

object MemoryQueryPolicy {
    private val reviewAll = Regex(
        """\b(show|list|review|read( out)?|display|see|give me)\b.{0,20}\b(all|every(thing)?|whole list of)\b.{0,25}\b(memor(y|ies)|saved|remember(ed)?|stored)\b|\bmemory tab\b|\ball (my |the )?memories\b|\bevery ?thing (you('ve| have)? )?(saved|stored|remember(ed)?)\b""",
        RegexOption.IGNORE_CASE
    )
    /** Topic words that say nothing specific: "me", "myself", "my life". */
    private val vague = setOf("me", "myself", "my", "i", "about", "you", "anything", "everything", "stuff", "things", "life", "remember", "know", "memories", "memory", "saved")

    private val aboutMe = Regex(
        """^\s*(?:hey jarvis,?\s+)?(?:tell me|what do you know|what do you remember|what have you (?:saved|stored|remembered)|what did i tell you)\s+about\s+(.+?)\s*[?.!]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * A question about saved memories that JARVIS answers on the phone, without a model: a model that is sent no matching
     * memories would otherwise say "I don't have anything saved", which is false. Returns the topic ("" for "me").
     */
    fun localQuestion(utterance: String): String? {
        if (reviewAll.containsMatchIn(utterance)) return ""
        val match = aboutMe.find(utterance) ?: return null
        val topic = match.groupValues[1].trim()
        if (MemoryRetriever.tokens(topic).all { it in vague }) return ""
        // "Tell me about black holes" is a general question; only "what do you know/remember about X" is about memories.
        return topic.takeUnless { utterance.trim().startsWith("tell me", ignoreCase = true) || utterance.contains("hey jarvis, tell me", ignoreCase = true) }
    }

    fun decide(utterance: String, query: String?): MemoryQuery {
        if (reviewAll.containsMatchIn(utterance)) return MemoryQuery.ReviewAll
        val topic = query?.trim().orEmpty()
        val specific = MemoryRetriever.tokens(topic).filter { it !in vague }
        return if (specific.isEmpty()) MemoryQuery.Clarify else MemoryQuery.Topic(topic)
    }

    /** Says what kinds of things are saved (never their content) and how to ask. */
    fun clarification(memories: List<Memory>): String {
        if (memories.isEmpty()) return "I haven't saved anything about you yet. Say \"remember that …\" to add something."
        val kinds = memories.groupingBy { it.type }.eachCount().entries
            .sortedByDescending { it.value }
            .joinToString(", ") { (type, n) -> "$n ${label(type, n)}" }
        return "I have ${memories.size} saved ${if (memories.size == 1) "item" else "items"} ($kinds). What would you like to know? " +
            "For example, ask about your favourite song or a person by name. To review everything, say \"show all my memories\" or open the Memory tab."
    }

    private fun label(type: String, n: Int): String = when (type) {
        "favorite" -> if (n == 1) "favourite" else "favourites"
        "relationship" -> if (n == 1) "person" else "people"
        "preference" -> if (n == 1) "preference" else "preferences"
        else -> if (n == 1) "fact" else "facts"
    }.lowercase(Locale.ROOT)
}
