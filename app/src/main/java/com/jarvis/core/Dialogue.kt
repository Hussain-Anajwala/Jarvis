package com.jarvis.core

import java.util.Locale

/** How JARVIS listens for the answer to a question it just asked in a voice conversation. */
enum class FollowUpRoute {
    /** Not a voice conversation, or too many questions in a row: stay idle. */
    NONE,
    /** Microphone permission is off: stay idle (the question is on screen to answer by typing). */
    NO_PERMISSION,
    /** JARVIS is on screen: the normal recogniser listens. */
    ON_SCREEN,
    /** JARVIS is in the background but the wake-word service holds the microphone: it listens. */
    WAKE_SERVICE,
    /** Neither: ask the user to tap a notification to answer. */
    NOTIFICATION
}

object FollowUpPolicy {
    const val MAX_FOLLOW_UPS = 4

    fun route(voiceConversation: Boolean, followUpsSoFar: Int, micGranted: Boolean, visible: Boolean, wakeServiceCanListen: Boolean): FollowUpRoute = when {
        !voiceConversation || followUpsSoFar >= MAX_FOLLOW_UPS -> FollowUpRoute.NONE
        !micGranted -> FollowUpRoute.NO_PERMISSION
        visible -> FollowUpRoute.ON_SCREEN
        wakeServiceCanListen -> FollowUpRoute.WAKE_SERVICE
        else -> FollowUpRoute.NOTIFICATION
    }
}

/** How the user answered "which one?" by voice or text. */
sealed interface ChoicePick {
    data class Option(val index: Int) : ChoicePick
    data object Cancel : ChoicePick
    /** Not an answer to the question (treat it as a new request). */
    data object NotAnAnswer : ChoicePick
}

/**
 * Understands answers to a numbered list: "the second one", "2", "number two", "last", or words from one option
 * ("the mobile", "Asha Rao", "ending 4321"). Only an unambiguous answer selects; anything else is not an answer.
 */
object ChoiceAnswers {
    private val ordinals = listOf(
        listOf("first", "1st", "1"), listOf("second", "2nd", "two", "2"), listOf("third", "3rd", "three", "3"),
        listOf("fourth", "4th", "four", "4"), listOf("fifth", "5th", "five", "5"), listOf("sixth", "6th", "six", "6"),
        listOf("seventh", "7th", "seven", "7"), listOf("eighth", "8th", "eight", "8"), listOf("ninth", "9th", "nine", "9")
    )
    private val cancelWords = Regex("""^\s*(cancel|stop|never ?mind|forget it|none( of them)?|no( thanks)?|nothing)\b""", RegexOption.IGNORE_CASE)
    private val filler = setOf("the", "one", "number", "option", "person", "contact", "please", "that", "this", "pick", "choose", "select", "use", "call", "text", "message", "it", "is", "at", "on", "with", "a", "an")

    fun pick(answer: String, optionLabels: List<String>): ChoicePick {
        val text = answer.trim()
        if (text.isEmpty()) return ChoicePick.NotAnAnswer
        if (cancelWords.containsMatchIn(text)) return ChoicePick.Cancel
        val words = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return ChoicePick.NotAnAnswer
        if (words.size <= 4 && ("last" in words || "latter" in words)) return ChoicePick.Option(optionLabels.lastIndex)
        // Ordinal answers are short ("second", "the 2nd one", "number two"); a long sentence is a new request.
        if (words.size <= 4) {
            val hits = ordinals.withIndex().filter { (index, forms) -> index < optionLabels.size && words.any { it in forms } }
            if (hits.size == 1) return ChoicePick.Option(hits.single().index)
            // "one" alone means the first option only as "one" / "number one" / "option one" ("the second one" is handled above).
            if (hits.isEmpty() && "one" in words && words.all { it in setOf("one", "number", "option", "the") } && optionLabels.isNotEmpty()) {
                return ChoicePick.Option(0)
            }
        }
        // Words that identify exactly one option, e.g. "the work number" or "ending 4321".
        val meaningful = words.filter { it !in filler && it != "ending" && it != "number" }
        if (meaningful.isEmpty() || meaningful.size > 5) return ChoicePick.NotAnAnswer
        val normalized = optionLabels.map { label -> label.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]"), " ") }
        val matches = normalized.withIndex().filter { (_, label) ->
            val labelWords = label.split(Regex("\\s+")).toSet()
            val labelDigits = label.filter(Char::isDigit)
            meaningful.all { word -> word in labelWords || (word.all(Char::isDigit) && word.length >= 3 && labelDigits.endsWith(word)) }
        }
        return if (matches.size == 1) ChoicePick.Option(matches.single().index) else ChoicePick.NotAnAnswer
    }
}
