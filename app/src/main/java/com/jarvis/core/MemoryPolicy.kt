package com.jarvis.core

import com.jarvis.data.Memory
import java.util.Locale

/**
 * Credentials and identifiers that must never be stored as memories, sent to a model, written to a trace, or read
 * aloud by a cloud voice: passwords, PINs, OTPs and access codes (a label followed by a value), card numbers
 * (Luhn-checked), Aadhaar and PAN numbers, and API keys/tokens. A label alone ("what is an OTP?", "pin this note",
 * "reset my password") is not a secret; ordinary numbers (times, dates, years, flight numbers) are not either.
 */
object SensitiveData {
    private const val LABELS =
        """password|passcode|pass\s?word|pin|upi\s?pin|atm\s?pin|otp|one[- ]time\s(?:pass)?code|cvv|cvc|security\s+code|verification\s+code|access\s+code|door\s+code|lock\s+code|api[ _-]?key|secret\s+key|access\s+token|auth\s+token|net\s?banking\s+password"""
    /** "PIN is 4821", "password: hunter2", "OTP 552901", "api key sk_…": the label, then the value (group 2). */
    private val labelledValue = Regex(
        """\b($LABELS)\b(?:\s+(?:is|was|=|:)|\s*[:=-])?\s*["'“]?([^\s"'”,.;!?]*\d[^\s"'”,.;!?]*|(?<=is\s|:\s|=\s|:|=)(?!(?:wrong|incorrect|correct|right|expired|invalid|not|required|needed|missing|too|very|the|a|an|my|your|same|different|changed|reset|blocked|locked|set|saved|weak|strong)\b)[^\s"'”,.;!?]{3,})""",
        RegexOption.IGNORE_CASE
    )
    /** "552901 is your OTP", "4821 is my PIN". */
    private val valueThenLabel = Regex("""\b(\d{4,8})\s+is\s+(?:your|my|the)\s+(?:$LABELS)\b""", RegexOption.IGNORE_CASE)
    private val cardLike = Regex("""\b(?:\d[ -]?){13,19}\b""")
    private val aadhaar = Regex("""\b\d{4}\s?\d{4}\s?\d{4}\b""")
    private val pan = Regex("""\b[A-Z]{5}\d{4}[A-Z]\b""")
    private val keyLike = Regex("""\b(sk|gsk|AIza|ghp|xox[bap])[-_A-Za-z0-9]{16,}\b""")

    fun isSecret(text: String): Boolean = findings(text).isNotEmpty()

    /** What kinds of secret [text] contains (for audit records; never the values). */
    fun findings(text: String): Set<String> = buildSet {
        if (labelledValue.containsMatchIn(text) || valueThenLabel.containsMatchIn(text)) add("labelled credential")
        if (pan.containsMatchIn(text)) add("PAN")
        if (keyLike.containsMatchIn(text)) add("API key")
        if (cardLike.findAll(text).any { luhn(it.value.filter(Char::isDigit)) }) add("card number")
        if (aadhaar.containsMatchIn(text)) add("ID number")
    }

    /**
     * Replaces only the secret values, keeping the rest of the sentence: "Your OTP is 552901" → "Your OTP is
     * [redacted]". Returns the text and how many values were replaced. Ordinary numbers are left alone.
     */
    fun redact(text: String): Pair<String, Int> {
        var count = 0
        var out = labelledValue.replace(text) { m ->
            count++
            val value = m.groups[2]!!
            m.value.substring(0, value.range.first - m.range.first) + "[redacted]" + m.value.substring(value.range.last + 1 - m.range.first)
        }
        out = valueThenLabel.replace(out) { m -> count++; "[redacted]" + m.value.substring(m.groupValues[1].length) }
        out = keyLike.replace(out) { count++; "[key]" }
        out = cardLike.replace(out) { m ->
            if (luhn(m.value.filter(Char::isDigit))) { count++; "[card]" + m.value.takeLastWhile { it == ' ' || it == '-' } } else m.value
        }
        out = aadhaar.replace(out) { count++; "[id]" }
        out = pan.replace(out) { count++; "[id]" }
        return out to count
    }

    /** Text safe to keep in Activity, traces, and evaluation results: secret values replaced, everything else kept. */
    fun forStorage(text: String): String = redact(text).first

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
