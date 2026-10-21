package com.jarvis.core

import com.jarvis.data.Memory

/**
 * An action JARVIS has shown and is waiting to hear "yes" for: one meeting to write, or one memory to delete. The yes
 * applies only to this exact proposal, only within [Confirmations.TTL_MS], and only once; anything else drops it.
 */
sealed interface PendingConfirmation {
    val createdAt: Long

    data class Meeting(val step: PlanStep, override val createdAt: Long) : PendingConfirmation
    data class ForgetMemory(val memory: Memory, override val createdAt: Long) : PendingConfirmation
}

object Confirmations {
    const val TTL_MS = 5 * 60_000L

    enum class Answer { APPROVE, REJECT, UNCLEAR, EXPIRED, NOT_AN_ANSWER }

    /** "yes", "haan, add it", "ok delete it": a yes with nothing that could change what was shown. */
    private val plainYes = Regex(
        """^\s*(?:yes|yeah|yep|yup|sure|ok(?:ay)?|haan|haa|ha|ji|theek hai|thik hai|go ahead|do it|confirm(?:ed)?)\b[\s,.!]*""" +
            """(?:(?:please|ji|haan|yes|ok)\s*)*(?:(?:add|create|save|schedule|delete|remove|forget|do|go ahead with)\s+(?:it|that|this))?[\s,.!]*(?:please|kar do|karo)?[\s.!]*$""",
        RegexOption.IGNORE_CASE
    )

    fun answer(pending: PendingConfirmation, text: String, now: Long): Answer {
        val kind = ApprovalAnswer.parse(text)
        if (kind == ApprovalAnswer.Kind.UNCLEAR) return Answer.NOT_AN_ANSWER
        if (now - pending.createdAt !in 0..TTL_MS) return Answer.EXPIRED
        return when {
            kind == ApprovalAnswer.Kind.NO -> Answer.REJECT
            plainYes.matches(text.trim()) -> Answer.APPROVE
            // "yes but at 5 pm": not a yes to what was shown.
            else -> Answer.UNCLEAR
        }
    }

    /** What the user saw: a stored memory must still match it exactly to be deleted. */
    fun fingerprint(memory: Memory): String = listOf(memory.id, memory.type, memory.memoryKey, memory.contentText, memory.updatedAt.toString()).joinToString("\u0000")

    fun meetingProposal(step: PlanStep): String {
        val p = step.parameters
        val title = p["title"]?.takeIf(String::isNotBlank) ?: MeetingDetails.DEFAULT_TITLE
        val day = p["date"]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        val dayText = day?.let {
            it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + it.dayOfMonth + " " +
                it.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
        } ?: "the date you said"
        val time = p["hour"]?.toIntOrNull()?.let { h -> ClockText.time(h, p["minute"]?.toIntOrNull() ?: 0) } ?: "the time you said"
        val place = p["location"]?.takeIf(String::isNotBlank)?.let { " at $it" } ?: " (no location)"
        return "Add \"$title\" on $dayText at $time$place to your calendar, with a reminder before it? Say yes to add it, or no."
    }

    fun forgetProposal(memory: Memory): String = "Delete this memory: \"${memory.contentText}\"? Say yes to delete it, or no to keep it."
}
