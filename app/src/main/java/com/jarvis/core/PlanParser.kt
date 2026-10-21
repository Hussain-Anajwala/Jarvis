package com.jarvis.core

import java.time.ZonedDateTime

/**
 * Turns a multi-step request ("remind me to drink water at 7 am and start a 25 minute timer, then remind me to review
 * notes at 9") into typed steps, deterministically and on the phone — no model writes a plan. It applies only when
 * **every** clause is a supported action; anything else is not a plan (so "remind me to buy bread and milk" stays one
 * reminder). A reminder clause missing its time is reported so JARVIS can ask for it.
 */
object PlanParser {
    private val split = Regex("""\s*(?:,\s*(?:and\s+)?(?:then\s+)?|;\s*|\s+and\s+then\s+|\s+then\s+|\s+and\s+(?=(?:also\s+)?(?:remind|set|start|cancel|clear)\b))""", RegexOption.IGNORE_CASE)
    private val timer = Regex("""^\s*(?:also\s+)?(?:set|start)\s+(?:a\s+|an\s+)?(\d{1,3})[\s-]*(second|sec|minute|min|hour)s?\s+timer\b|^\s*(?:also\s+)?(?:set|start)\s+(?:a\s+)?timer\s+for\s+(\d{1,3})\s*(second|sec|minute|min|hour)s?\b""", RegexOption.IGNORE_CASE)
    private val cancelAll = Regex("""^\s*(?:also\s+)?(?:cancel|clear|delete|remove)\s+(?:all\s+)?(?:of\s+)?(?:my\s+|the\s+)?(?:pending\s+)?reminders\b""", RegexOption.IGNORE_CASE)
    private val remind = Regex("""^\s*(?:also\s+)?remind\s+me\b""", RegexOption.IGNORE_CASE)

    sealed interface Clause {
        data class Step(val step: ProposedStep) : Clause
        /** A reminder whose time (or AM/PM) is missing: [question] asks for it. */
        data class Missing(val text: String, val question: String) : Clause
    }

    data class Parsed(val clauses: List<Clause>) {
        val steps get() = clauses.filterIsInstance<Clause.Step>().map { it.step }
        val missing get() = clauses.filterIsInstance<Clause.Missing>().firstOrNull()
    }

    /** Null unless the text has at least two clauses and every one is a supported action. */
    fun parse(text: String, now: ZonedDateTime): Parsed? {
        val parts = text.trim().trimEnd('.', '!').split(split).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2 || parts.size > PlanExecutor.MAX_STEPS) return null
        val clauses = parts.map { clause(it, now) ?: return null }
        return Parsed(clauses)
    }

    fun clause(text: String, now: ZonedDateTime): Clause? {
        timer.find(text)?.let { m ->
            val n = (m.groupValues[1].ifBlank { m.groupValues[3] }).toInt()
            val unit = m.groupValues[2].ifBlank { m.groupValues[4] }.lowercase()
            val seconds = when { unit.startsWith("h") -> n * 3600; unit.startsWith("m") -> n * 60; else -> n }
            return Clause.Step(ProposedStep("timer.start", mapOf("seconds" to seconds.toString())))
        }
        if (cancelAll.containsMatchIn(text)) return Clause.Step(ProposedStep("reminders.cancel_all", emptyMap()))
        if (!remind.containsMatchIn(text)) return null
        return when (val step = ReminderDialogue.start(text.replace(Regex("""^\s*also\s+""", RegexOption.IGNORE_CASE), ""), now)) {
            is ReminderStep.Ready -> Clause.Step(ProposedStep("reminder.schedule",
                mapOf("title" to step.title, "at" to step.at.toInstant().toEpochMilli().toString(), "zone" to step.at.zone.id)))
            is ReminderStep.Ask -> Clause.Missing(text, step.question)
            ReminderStep.Cancelled -> null
        }
    }
}
