package com.jarvis.core

import com.jarvis.data.Memory

/**
 * An action JARVIS has shown and is waiting to hear "yes" for: one meeting to write, or one memory to delete. The yes
 * applies only to this exact proposal, only within [Confirmations.TTL_MS] of when it was shown, and only once. A reply
 * that isn't a clear answer (a misheard "haan", silence) keeps it waiting with its original deadline; only a clear new
 * request drops it.
 */
sealed interface PendingConfirmation {
    val createdAt: Long

    data class Meeting(val step: PlanStep, override val createdAt: Long) : PendingConfirmation
    data class ForgetMemory(val memory: Memory, override val createdAt: Long) : PendingConfirmation
}

object Confirmations {
    const val TTL_MS = 5 * 60_000L

    enum class Answer { APPROVE, REJECT, UNCLEAR, EXPIRED, NOT_AN_ANSWER }

    /** "yes", "haan, add it", "ok delete it", "yeah sure": a yes with nothing that could change what was shown. */
    private val plainYes = Regex(
        """^\s*(?:yes+|yeah|yea|yep|yup|ya|yah|sure|ok(?:ay)?|haan(?:ji)?|haa|han(?:ji)?|ha|ji(?: haan)?|theek hai|thik hai|go ahead|do it|confirm(?:ed)?)\b[\s,.!]*""" +
            """(?:(?:please|ji|haan|han|yes|ok|sure|yeah)\s*[,.!]*\s*)*(?:(?:add|create|save|schedule|delete|remove|forget|do|go ahead with)\s+(?:it|that|this))?[\s,.!]*(?:please|kar do|karo)?[\s.!]*$""",
        RegexOption.IGNORE_CASE
    )
    private val question = Regex("""^\s*(?:what|what's|whats|when|where|who|why|how|which|is|are|can|could|will|kya|kab|kahan|kaun|kaise|kitne)\b""", RegexOption.IGNORE_CASE)

    /** A recognisable command (reminder, timer, clock, plan, goal, phone action): never an answer to the proposal. */
    fun isCommand(text: String): Boolean =
        ReminderCommands.parse(text) != null || ClockCommandParser.parse(text) != null || ContextualControl.parse(text) != null ||
            PhoneCommandParser.parse(text) != PhoneCommandParseResult.NotPhoneCommand ||
            PlanParser.parse(text, java.time.ZonedDateTime.now()) != null || GoalDialogue.begin(text, java.time.ZonedDateTime.now()) != null ||
            MeetingRequestParser.isMeetingIntent(text)

    /** Clearly something new: a command, a question, or a sentence of four or more words that isn't a yes or no. */
    fun looksLikeRequest(text: String): Boolean =
        isCommand(text) || question.containsMatchIn(text) || text.trim().split(Regex("""\s+""")).count { it.any(Char::isLetterOrDigit) } >= 4

    /**
     * How [text] answers [pending] at [now]. Not a clear answer and not a new request (a misheard "Han?", "hmm", an
     * empty transcript) → UNCLEAR: the same proposal keeps waiting, its deadline unchanged. "No" always rejects (a stale
     * no is harmless). A yes after the deadline → EXPIRED (asked again, never run).
     */
    fun answer(pending: PendingConfirmation, text: String, now: Long): Answer {
        val kind = ApprovalAnswer.parse(text)
        return when {
            kind == ApprovalAnswer.Kind.UNCLEAR -> if (looksLikeRequest(text)) Answer.NOT_AN_ANSWER else Answer.UNCLEAR
            // "Cancel the timer" starts with a no-word but is a different request.
            isCommand(text) -> Answer.NOT_AN_ANSWER
            kind == ApprovalAnswer.Kind.NO -> Answer.REJECT
            now - pending.createdAt !in 0..TTL_MS -> Answer.EXPIRED
            plainYes.matches(text.trim()) -> Answer.APPROVE
            // "yes but at 5 pm": not a yes to what was shown.
            else -> Answer.UNCLEAR
        }
    }

    /** Asked again after an unclear reply: the same proposal, so the user never has to repeat the request. */
    fun retryPrompt(pending: PendingConfirmation, lang: ReplyLang = ReplyLang.EN): String {
        val lead = when (lang) {
            ReplyLang.EN -> "I didn't catch a yes or no. "
            ReplyLang.HINGLISH -> "Haan ya na samajh nahi aaya. "
            ReplyLang.HINDI -> "हाँ या ना समझ नहीं आया। "
        }
        return when (pending) {
            is PendingConfirmation.ForgetMemory -> if (lang == ReplyLang.EN) lead + "Should I delete \"${pending.memory.contentText}\"? Say yes or no." else lead + forgetProposal(pending.memory, lang)
            is PendingConfirmation.Meeting -> lead + meetingProposal(pending.step, lang)
        }
    }

    /**
     * "No, make it 5 PM" / "make it Friday" while a meeting waits: the same meeting with that time or day, to be shown and
     * confirmed again. Null when the reply names no time or day (it is then a plain no). AM/PM is never guessed.
     */
    sealed interface Correction {
        data class Changed(val step: PlanStep) : Correction
        data object NeedsAmPm : Correction
    }

    fun meetingCorrection(step: PlanStep, text: String, now: java.time.ZonedDateTime): Correction? {
        val today = now.toLocalDate()
        val namedDay = (ReminderTime.date(text, today) as? ReminderTime.DateWords.Valid)?.date ?: ReminderTime.day(text, today)
        val current = step.parameters["date"]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        return when (val time = ReminderTime.parse(text, now)) {
            is WhenParse.Exact -> {
                val date = namedDay ?: current ?: time.at.toLocalDate()
                Correction.Changed(step.copy(parameters = step.parameters + mapOf("date" to date.toString(), "hour" to "${time.at.hour}", "minute" to "${time.at.minute}")))
            }
            is WhenParse.NeedsAmPm -> Correction.NeedsAmPm
            is WhenParse.DayOnly -> Correction.Changed(step.copy(parameters = step.parameters + ("date" to time.day.toString())))
            else -> null
        }
    }

    /** What the user saw: a stored memory must still match it exactly to be deleted. */
    fun fingerprint(memory: Memory): String = listOf(memory.id, memory.type, memory.memoryKey, memory.contentText, memory.updatedAt.toString()).joinToString("\u0000")

    fun meetingProposal(step: PlanStep, lang: ReplyLang = ReplyLang.EN): String {
        val p = step.parameters
        val title = p["title"]?.takeIf(String::isNotBlank) ?: MeetingDetails.DEFAULT_TITLE
        val day = p["date"]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        val dayText = day?.let {
            it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + it.dayOfMonth + " " +
                it.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
        } ?: "the date you said"
        val time = p["hour"]?.toIntOrNull()?.let { h -> ClockText.time(h, p["minute"]?.toIntOrNull() ?: 0) } ?: "the time you said"
        val place = p["location"]?.takeIf(String::isNotBlank)
        return when (lang) {
            ReplyLang.EN -> "Add \"$title\" on $dayText at $time${place?.let { " at $it" } ?: " (no location)"} to your calendar, with a reminder before it? Say yes to add it, or no."
            ReplyLang.HINGLISH -> "Kya main \"$title\" ko $dayText, $time${place?.let { ", $it" } ?: " (koi jagah nahi)"} pe aapke calendar mein add kar doon, aur usse pehle reminder laga doon? Haan ya na boliye."
            ReplyLang.HINDI -> "क्या मैं \"$title\" को $dayText, $time${place?.let { ", $it" } ?: " (कोई जगह नहीं)"} पर आपके कैलेंडर में जोड़ दूँ, और उससे पहले रिमाइंडर लगा दूँ? हाँ या ना बोलिए।"
        }
    }

    fun forgetProposal(memory: Memory, lang: ReplyLang = ReplyLang.EN): String = when (lang) {
        ReplyLang.EN -> "Delete this memory: \"${memory.contentText}\"? Say yes to delete it, or no to keep it."
        ReplyLang.HINGLISH -> "Kya main yeh memory delete kar doon: \"${memory.contentText}\"? Haan ya na boliye."
        ReplyLang.HINDI -> "क्या मैं यह याद हटा दूँ: \"${memory.contentText}\"? हाँ या ना बोलिए।"
    }
}
