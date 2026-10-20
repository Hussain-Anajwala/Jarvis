package com.jarvis.core

import java.util.Locale

/**
 * Keeps meeting details honest. A venue is used only if the user actually said it (in this request, a recent turn, or
 * a saved memory sent with the request); otherwise JARVIS asks, and "online" / "no location" creates the meeting
 * without one. A model must never fill the venue in on its own.
 */
object MeetingDetails {
    const val DEFAULT_TITLE = "Meeting"
    const val PLACE_QUESTION = "Where is the meeting? Tell me the place, or say \"no location\" if it's online or you'd rather not add one."

    private val noPlace = Regex(
        """\b(no (location|venue|place|address)|without (a )?(location|venue|place)|online|virtual(ly)?|video call|zoom|google meet|teams|on (a )?call|over the phone|remote(ly)?|skip (it|the location))\b""",
        RegexOption.IGNORE_CASE
    )
    private val placeholders = setOf("tbd", "tba", "unknown", "unspecified", "none", "n/a", "na", "null", "location", "venue", "place", "somewhere", "here")
    private val filler = setOf("the", "a", "an", "at", "in", "on", "near", "my", "our", "to", "of")

    fun saysNoPlace(text: String): Boolean = noPlace.containsMatchIn(text)

    private val cancel = Regex("""^\s*(cancel|stop|never ?mind|forget (it|about it)|don'?t (create|add|make) (it|the meeting)|no thanks)\b""", RegexOption.IGNORE_CASE)
    /** "Set a timer…", "call Asha", "what's the weather": a new request, never a venue. */
    private val command = Regex(
        """^\s*(please\s+)?(set|call|text|message|send|play|open|remind|create|schedule|add|turn|switch|start|show|read|tell|email|write|navigate|what|what's|who|when|why|how|is|are|can|could|will)\b""",
        RegexOption.IGNORE_CASE
    )

    fun looksLikeRequest(answer: String): Boolean = command.containsMatchIn(answer)

    /** The user called off the meeting while JARVIS was asking where it is. */
    fun cancels(answer: String): Boolean = cancel.containsMatchIn(answer)

    /** True if every meaningful word of [location] appears in what the user (or their saved memories) said. */
    fun grounded(location: String, sources: List<String>): Boolean {
        val words = words(location)
        if (words.isEmpty() || location.trim().lowercase(Locale.ROOT) in placeholders) return false
        val said = sources.flatMap(::words).toSet()
        return words.all { it in said }
    }

    /** A short answer to [PLACE_QUESTION] ("at VIT college", "Dadar station") as a venue; null if it isn't one. */
    fun placeFromAnswer(answer: String): String? {
        if (answer.trim().endsWith("?")) return null // a question back, not a place
        if (cancels(answer) || command.containsMatchIn(answer)) return null
        val cleaned = answer.trim().trimEnd('.', '!', '?')
            .replace(Regex("""^(it'?s\s+|it is\s+|the meeting is\s+)?(at|in|near)\s+""", RegexOption.IGNORE_CASE), "")
            .trim()
        val count = cleaned.split(Regex("\\s+")).size
        if (cleaned.isEmpty() || count > 8 || cleaned.lowercase(Locale.ROOT) in placeholders) return null
        return cleaned
    }

    private fun words(text: String): List<String> =
        ContactNames.tokens(text).filter { it.length > 1 && it !in filler }
}
