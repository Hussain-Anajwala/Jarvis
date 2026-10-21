package com.jarvis.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** When a reminder should fire, as far as the words say. */
sealed interface WhenParse {
    data class Exact(val at: ZonedDateTime) : WhenParse
    /** "at 6" with no AM/PM and no morning/evening: ask. */
    data class NeedsAmPm(val day: LocalDate?, val hour: Int, val minute: Int) : WhenParse
    /** "tomorrow" with no time: ask for the time. */
    data class DayOnly(val day: LocalDate) : WhenParse
    data object None : WhenParse
}

object ReminderTime {
    private val numbers = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "forty five" to 45
    )
    private val relative = Regex(
        """\b(?:in|after)\s+(\d{1,3}|an?|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty five|forty)\s*(minutes?|mins?|hours?|hrs?)\b|\b(\d{1,3})\s*(minute|min|ghante|ghanta|hour)s?\s+(?:baad|bad|mein)\b""",
        RegexOption.IGNORE_CASE
    )
    private val halfHour = Regex("""\b(?:in|after)\s+half\s+an?\s+hour\b""", RegexOption.IGNORE_CASE)
    private val clock = Regex("""\b(?:at\s+)?(\d{1,2})(?::|\.)(\d{2})\s*(a\.?\s?m\.?|p\.?\s?m\.?)?|\b(?:at\s+)?(\d{1,2})\s*(a\.?\s?m\.?|p\.?\s?m\.?)|\bat\s+(\d{1,2})\b(?!\s*(?:minutes?|mins?|hours?|days?|%))|\b(noon|midday|midnight)\b""", RegexOption.IGNORE_CASE)
    private val morning = Regex("""\b(morning|subah|sunrise)\b""", RegexOption.IGNORE_CASE)
    private val evening = Regex("""\b(evening|tonight|night|afternoon|shaam|raat|dopahar)\b""", RegexOption.IGNORE_CASE)
    /** "9 baje" → "at 9", "6:30 baje" → "at 6:30" (Hinglish o'clock), so the English clock rules apply. */
    private val baje = Regex("""\b(\d{1,2}(?::\d{2})?)\s*baje\b""", RegexOption.IGNORE_CASE)
    fun normalize(text: String): String = baje.replace(text) { "at ${it.groupValues[1]}" }

    /** Day words found in [text], relative to [today]. */
    fun day(text: String, today: LocalDate): LocalDate? {
        val t = text.lowercase(Locale.ROOT)
        if (Regex("""\bday after tomorrow\b|\bparson\b""").containsMatchIn(t)) return today.plusDays(2)
        if (Regex("""\b(tomorrow|tmrw|tomorow|kal)\b""").containsMatchIn(t)) return today.plusDays(1)
        if (Regex("""\b(today|tonight|aaj)\b""").containsMatchIn(t)) return today
        Regex("""\b(?:in|after)\s+(\d{1,2}|a|one|two|three|four|five|six|seven)\s+days?\b""").find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: numbers[m.groupValues[1]] ?: return@let null
            return today.plusDays(n.toLong())
        }
        DayOfWeek.values().forEach { d ->
            val name = d.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase(Locale.ROOT)
            if (Regex("""\bnext\s+$name\b""").containsMatchIn(t)) {
                // "next Sunday" said on Friday is the coming Sunday (2 days), as most people mean it.
                return today.with(TemporalAdjusters.next(d))
            }
            if (Regex("""\b(?:on\s+|this\s+)?$name\b""").containsMatchIn(t)) return today.with(TemporalAdjusters.next(d))
        }
        return null
    }

    fun parse(raw: String, now: ZonedDateTime): WhenParse {
        val text = normalize(raw)
        if (halfHour.containsMatchIn(text)) return WhenParse.Exact(now.plusMinutes(30).truncatedTo(ChronoUnit.MINUTES))
        relative.find(text)?.let { m ->
            val amount = (m.groupValues[1].ifBlank { m.groupValues[3] }).lowercase(Locale.ROOT)
            val n = amount.toIntOrNull() ?: numbers[amount] ?: return@let
            val unit = (m.groupValues[2].ifBlank { m.groupValues[4] }).lowercase(Locale.ROOT)
            val minutes = if (unit.startsWith("h") || unit.startsWith("ghant")) n * 60L else n.toLong()
            if (minutes in 1..(7 * 24 * 60)) return WhenParse.Exact(now.plusMinutes(minutes).truncatedTo(ChronoUnit.MINUTES))
        }
        val day = day(text, now.toLocalDate())
        val m = clock.find(text)
        if (m == null) return day?.let { WhenParse.DayOnly(it) } ?: WhenParse.None
        val g = m.groupValues
        val (hour, minute, suffix) = when {
            g[1].isNotBlank() -> Triple(g[1].toInt(), g[2].toInt(), g[3])
            g[4].isNotBlank() -> Triple(g[4].toInt(), 0, g[5])
            g[6].isNotBlank() -> Triple(g[6].toInt(), 0, "")
            else -> Triple(if (g[7].lowercase(Locale.ROOT) == "midnight") 0 else 12, 0, "24h")
        }
        if (minute !in 0..59 || hour !in 0..23) return WhenParse.None
        val pm = suffix.startsWith("p", ignoreCase = true)
        val am = suffix.startsWith("a", ignoreCase = true)
        val h24 = when {
            suffix == "24h" -> hour
            am || pm -> if (hour !in 1..12) return WhenParse.None else (hour % 12) + if (pm) 12 else 0
            hour == 0 || hour > 12 -> hour
            morning.containsMatchIn(text) -> hour % 12
            evening.containsMatchIn(text) -> (hour % 12) + 12
            else -> return WhenParse.NeedsAmPm(day, hour, minute)
        }
        return WhenParse.Exact(at(day, h24, minute, now))
    }

    /** The given day at [hour]:[minute]; with no day, the next time that clock time comes round. */
    fun at(day: LocalDate?, hour: Int, minute: Int, now: ZonedDateTime): ZonedDateTime {
        var at = now.with(day ?: now.toLocalDate()).with(LocalTime.of(hour, minute)).truncatedTo(ChronoUnit.MINUTES)
        if (day == null && !at.isAfter(now)) at = at.plusDays(1)
        return at
    }

    /** Text with the time and day words removed, to leave what the reminder is about. */
    fun strip(raw: String): String = normalize(raw)
        .replace(halfHour, " ").replace(relative, " ").replace(clock, " ")
        .replace(Regex("""\b(kal|aaj|parson|subah|shaam|raat|dopahar)\b""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""\b(day after tomorrow|tomorrow|tmrw|today|tonight|this morning|in the morning|in the evening|morning|evening|afternoon|night|(?:on\s+|next\s+|this\s+)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|(?:in|after)\s+\d+\s+days?)\b""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""\s+"""), " ").trim().trim(',', '.', '!')
        .replace(Regex("""^(?:at|on|in|for|to|about|that)\s+|\s+(?:at|on|in|for)$""", RegexOption.IGNORE_CASE), "").trim()

    fun describe(at: ZonedDateTime, now: ZonedDateTime): String {
        val time = String.format(Locale.US, "%d:%02d %s", if (at.hour % 12 == 0) 12 else at.hour % 12, at.minute, if (at.hour < 12) "AM" else "PM")
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
        val day = when (days) {
            0L -> "today"
            1L -> "tomorrow"
            in 2L..6L -> at.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            else -> "${at.dayOfMonth} ${at.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
        }
        return "$time $day"
    }
}

/** Reminder and goal commands that are handled on the phone. */
sealed interface ReminderCommand {
    data class Create(val text: String) : ReminderCommand
    data object List : ReminderCommand
    data class Cancel(val latest: Boolean, val query: String) : ReminderCommand
    data class PauseGoal(val query: String) : ReminderCommand
    data class ResumeGoal(val query: String) : ReminderCommand
}

object ReminderCommands {
    private val create = Regex("""^\s*(?:please\s+|hey\s+jarvis,?\s+|can you\s+|could you\s+)*remind\s+me\b""", RegexOption.IGNORE_CASE)
    /** "… ki yaad dilana", "yaad dila do": a Hinglish reminder request anywhere in the sentence. */
    private val hinglishCreate = Regex("""\byaad\s+dila(?:na|o|iye|ana|\s+do|\s+dena)?\b""", RegexOption.IGNORE_CASE)
    private val hinglishFiller = Regex("""\b(mujhe|mujhko|zara|please)\b|\b(?:ki|ka|ke)?\s*yaad\s+dila(?:na|o|iye|ana|\s+do|\s+dena)?\b""", RegexOption.IGNORE_CASE)
    private val list = Regex("""\b(list|show|what are|what's|which|tell me|read)\b.{0,30}\breminders?\b|\bwhat reminders\b|\b(pending|upcoming|my)\s+reminders\s*\??$|\breminders?\b.{0,20}\b(dikhao|dikha\s+do|ka\s+time|kab)\b""", RegexOption.IGNORE_CASE)
    private val cancel = Regex("""\b(cancel|delete|remove|stop|clear|forget)\b.{0,40}\breminders?\b|\breminders?\b.{0,20}\b(cancel|delete|hatao|hata\s+do)\b""", RegexOption.IGNORE_CASE)
    private val latest = Regex("""\b(just|last|latest|recent|i\s+(?:just\s+)?(?:set|created|made|added)|you\s+(?:just\s+)?(?:set|created))\b""", RegexOption.IGNORE_CASE)
    private val pause = Regex("""\b(pause|hold|suspend|stop for now)\b.{0,30}\b(plan|goal|schedule)\b|\b(plan|goal)\b.{0,15}\bpause\b""", RegexOption.IGNORE_CASE)
    private val resume = Regex("""\b(resume|restart|continue|unpause|start again)\b.{0,30}\b(plan|goal|schedule)\b|\b(plan|goal)\b.{0,15}\bresume\b""", RegexOption.IGNORE_CASE)
    private val filler = Regex("""\b(cancel|delete|remove|stop|clear|forget|pause|hold|suspend|resume|restart|continue|unpause|the|my|this|that|a|reminders?|plan|goal|schedule|i|just|set|created|made|added|last|latest|recent|please|you|for|now|again|about|to)\b""", RegexOption.IGNORE_CASE)

    fun parse(text: String): ReminderCommand? = when {
        create.containsMatchIn(text) || hinglishCreate.containsMatchIn(text) -> ReminderCommand.Create(text)
        cancel.containsMatchIn(text) -> ReminderCommand.Cancel(latest.containsMatchIn(text), query(text))
        pause.containsMatchIn(text) -> ReminderCommand.PauseGoal(query(text))
        resume.containsMatchIn(text) -> ReminderCommand.ResumeGoal(query(text))
        list.containsMatchIn(text) -> ReminderCommand.List
        else -> null
    }

    private fun query(text: String) = text.replace(filler, " ").replace(Regex("""[^\p{L}\p{N} ]"""), " ").replace(Regex("""\s+"""), " ").trim()

    /** What the reminder is about: "remind me to stretch in 30 minutes" → "stretch". */
    fun title(text: String): String? =
        ReminderTime.strip(text.replace(create, " ").replace(hinglishFiller, " ")).replace(Regex("""^(?:to|about|that|of)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:ki|ka|ke|ko)$""", RegexOption.IGNORE_CASE), "")
            .trim().takeIf { it.length >= 2 }
}

enum class ReminderSlot { TITLE, TIME, AMPM }

sealed interface ReminderStep {
    data class Ask(val slot: ReminderSlot, val question: String, val draft: ReminderDraft) : ReminderStep
    data class Ready(val title: String, val at: ZonedDateTime, val origin: String) : ReminderStep
    data object Cancelled : ReminderStep
}

data class ReminderDraft(val title: String?, val time: WhenParse, val origin: String)

/** Asks only for what is missing: what to remind about, when, and AM or PM. Never guesses a time. */
object ReminderDialogue {
    private val cancel = Regex("""^\s*(cancel|stop|never ?mind|forget it|no thanks|don'?t)\b""", RegexOption.IGNORE_CASE)

    fun start(text: String, now: ZonedDateTime): ReminderStep =
        next(ReminderDraft(ReminderCommands.title(text), ReminderTime.parse(text, now), text), now)

    fun answer(draft: ReminderDraft, slot: ReminderSlot, answer: String, now: ZonedDateTime): ReminderStep {
        if (cancel.containsMatchIn(answer)) return ReminderStep.Cancelled
        val updated = when (slot) {
            ReminderSlot.TITLE -> draft.copy(title = answer.trim().trim('.', '!').replace(Regex("""^(?:to|about)\s+""", RegexOption.IGNORE_CASE), "").takeIf { it.length >= 2 })
            ReminderSlot.AMPM -> {
                val need = draft.time as? WhenParse.NeedsAmPm ?: return next(draft, now)
                val pm = Regex("""\b(pm|p\.m\.|evening|night|afternoon|shaam|raat)\b""", RegexOption.IGNORE_CASE).containsMatchIn(answer)
                val am = Regex("""\b(am|a\.m\.|morning|subah)\b""", RegexOption.IGNORE_CASE).containsMatchIn(answer)
                if (pm == am) draft else draft.copy(time = WhenParse.Exact(ReminderTime.at(need.day, (need.hour % 12) + if (pm) 12 else 0, need.minute, now)))
            }
            ReminderSlot.TIME -> {
                val day = (draft.time as? WhenParse.DayOnly)?.day
                when (val parsed = ReminderTime.parse(answer, now)) {
                    is WhenParse.Exact -> if (day != null && ReminderTime.day(answer, now.toLocalDate()) == null && !Regex("""\b(in|after)\b""").containsMatchIn(answer))
                        draft.copy(time = WhenParse.Exact(ReminderTime.at(day, parsed.at.hour, parsed.at.minute, now))) else draft.copy(time = parsed)
                    is WhenParse.NeedsAmPm -> draft.copy(time = parsed.copy(day = parsed.day ?: day))
                    is WhenParse.DayOnly -> draft.copy(time = parsed)
                    WhenParse.None -> draft
                }
            }
        }
        if (updated == draft) {
            return ReminderStep.Ask(slot, when (slot) {
                ReminderSlot.TITLE -> "What should I remind you about?"
                ReminderSlot.TIME -> "When? For example \"in 30 minutes\" or \"tomorrow at 9 AM\"."
                ReminderSlot.AMPM -> "Morning or evening — AM or PM?"
            }, draft)
        }
        return next(updated, now)
    }

    fun next(draft: ReminderDraft, now: ZonedDateTime): ReminderStep = when {
        draft.title == null -> ReminderStep.Ask(ReminderSlot.TITLE, "What should I remind you about?", draft)
        draft.time is WhenParse.NeedsAmPm -> ReminderStep.Ask(ReminderSlot.AMPM,
            "Is that ${(draft.time.hour % 12).let { if (it == 0) 12 else it }}${if (draft.time.minute > 0) ":%02d".format(draft.time.minute) else ""} in the morning or the evening?", draft)
        draft.time is WhenParse.Exact && !draft.time.at.isAfter(now) -> ReminderStep.Ask(ReminderSlot.TIME, "That time has already passed. When should I remind you?", draft.copy(time = WhenParse.None))
        draft.time is WhenParse.Exact -> ReminderStep.Ready(draft.title, draft.time.at, draft.origin)
        draft.time is WhenParse.DayOnly -> ReminderStep.Ask(ReminderSlot.TIME, "What time should I remind you?", draft)
        else -> ReminderStep.Ask(ReminderSlot.TIME, "When should I remind you to ${draft.title}? For example \"in 30 minutes\" or \"tomorrow at 9 AM\".", draft)
    }
}

// ---- Goals ----

data class GoalDraft(
    val subject: String,
    val due: LocalDate?,
    val topics: List<String>?,
    val time: LocalTime?,
    val origin: String,
    /** A clock time without AM/PM, waiting for the answer. */
    val pendingHour: Pair<Int, Int>? = null
)

enum class GoalSlot { DUE, TOPICS, TIME, AMPM, APPROVE }

sealed interface GoalStepResult {
    data class Ask(val slot: GoalSlot, val question: String, val draft: GoalDraft) : GoalStepResult
    data class Ready(val draft: GoalDraft, val sessions: List<TaskEngine.GoalStep>, val title: String) : GoalStepResult
    data class Explain(val message: String) : GoalStepResult
    data object Cancelled : GoalStepResult
}

/**
 * "Help me prepare for my exam next Sunday": asks for the date, topics, and a daily time, proposes a bounded plan
 * (one session a day until the day before, at most [MAX_SESSIONS]), and schedules nothing until the user says yes.
 */
object GoalDialogue {
    const val MAX_SESSIONS = 14
    private val start = Regex(
        """^\s*(?:please\s+|hey\s+jarvis,?\s+)?(?:help\s+me\s+|i\s+(?:want|need)\s+to\s+|plan\s+(?:my\s+)?)?(?:prepare|prep|study|revise|get\s+ready)\s+for\s+(?:my\s+|the\s+|an?\s+)?(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val yes = Regex("""^\s*(yes|yeah|yep|sure|ok(ay)?|go ahead|do it|schedule it|sounds good|haan|ha|theek hai)\b""", RegexOption.IGNORE_CASE)
    private val no = Regex("""^\s*(no|nope|cancel|stop|never ?mind|don'?t)\b""", RegexOption.IGNORE_CASE)

    fun begin(text: String, now: ZonedDateTime): GoalStepResult? {
        val m = start.find(text) ?: return null
        val rest = m.groupValues[1]
        val due = ReminderTime.day(rest, now.toLocalDate())
        val subject = ReminderTime.strip(rest).ifBlank { "exam" }
        return next(GoalDraft(subject, due, null, null, text), now)
    }

    fun answer(draft: GoalDraft, slot: GoalSlot, answer: String, now: ZonedDateTime): GoalStepResult {
        if (slot != GoalSlot.APPROVE && no.containsMatchIn(answer)) return GoalStepResult.Cancelled
        val updated = when (slot) {
            GoalSlot.DUE -> ReminderTime.day(answer, now.toLocalDate())?.let { draft.copy(due = it) } ?: draft
            GoalSlot.TOPICS -> {
                val general = Regex("""\b(general|everything|all|no specific|none|whatever)\b""", RegexOption.IGNORE_CASE).containsMatchIn(answer)
                val topics = if (general) listOf("revision") else answer.split(Regex(""",|\band\b|;""")).map { it.trim().trim('.') }.filter { it.length >= 2 }.take(10)
                if (topics.isEmpty()) draft else draft.copy(topics = topics)
            }
            GoalSlot.TIME, GoalSlot.AMPM -> withTime(draft, answer, now) ?: draft
            GoalSlot.APPROVE -> when {
                yes.containsMatchIn(answer) -> return plan(draft, now)
                no.containsMatchIn(answer) -> return GoalStepResult.Cancelled
                else -> withTime(draft, answer, now) ?: return GoalStepResult.Ask(GoalSlot.APPROVE, "Say yes to schedule it, no to drop it, or a different time like \"at 8 PM\".", draft)
            }
        }
        if (updated == draft) return next(draft, now, retry = true)
        return next(updated, now)
    }

    private fun withTime(draft: GoalDraft, answer: String, now: ZonedDateTime): GoalDraft? {
        draft.pendingHour?.let { (h, min) ->
            val pm = Regex("""\b(pm|evening|night|afternoon)\b""", RegexOption.IGNORE_CASE).containsMatchIn(answer)
            val am = Regex("""\b(am|morning)\b""", RegexOption.IGNORE_CASE).containsMatchIn(answer)
            if (pm != am) return draft.copy(time = LocalTime.of((h % 12) + if (pm) 12 else 0, min), pendingHour = null)
        }
        return when (val p = ReminderTime.parse(answer, now)) {
            is WhenParse.Exact -> draft.copy(time = p.at.toLocalTime(), pendingHour = null)
            is WhenParse.NeedsAmPm -> draft.copy(pendingHour = p.hour to p.minute)
            else -> null
        }
    }

    fun next(draft: GoalDraft, now: ZonedDateTime, retry: Boolean = false): GoalStepResult {
        val today = now.toLocalDate()
        return when {
            draft.due == null -> GoalStepResult.Ask(GoalSlot.DUE, if (retry) "I need the date, for example \"next Sunday\" or \"in 10 days\"." else "When is your ${draft.subject}?", draft)
            !draft.due.isAfter(today.plusDays(1)) -> GoalStepResult.Explain(
                "Your ${draft.subject} is too soon for a study plan. I can set a single reminder instead — say \"remind me to revise at 7 PM\".")
            draft.topics == null -> GoalStepResult.Ask(GoalSlot.TOPICS, "Which topics or subjects should the sessions cover? List them, or say \"general revision\".", draft)
            draft.pendingHour != null -> GoalStepResult.Ask(GoalSlot.AMPM, "Is ${draft.pendingHour.first} in the morning or the evening?", draft)
            draft.time == null -> GoalStepResult.Ask(GoalSlot.TIME, "What time should I remind you to study each day?", draft)
            else -> {
                val sessions = sessions(draft, now)
                GoalStepResult.Ask(GoalSlot.APPROVE, proposal(draft, sessions, now), draft)
            }
        }
    }

    fun sessions(draft: GoalDraft, now: ZonedDateTime): List<TaskEngine.GoalStep> {
        val due = draft.due ?: return emptyList()
        val time = draft.time ?: return emptyList()
        val topics = draft.topics.orEmpty().ifEmpty { listOf("revision") }
        var first = now.toLocalDate().plusDays(1)
        val last = due.minusDays(1)
        if (ChronoUnit.DAYS.between(first, last) + 1 > MAX_SESSIONS) first = last.minusDays(MAX_SESSIONS - 1L)
        val days = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
        return days.mapIndexed { i, day ->
            val topic = topics[i % topics.size]
            TaskEngine.GoalStep(now.with(day).with(time).truncatedTo(ChronoUnit.MINUTES).toInstant().toEpochMilli(),
                "Study for your ${draft.subject}: $topic", mapOf("topic" to topic, "session" to "${i + 1}/${days.size}"))
        }
    }

    private fun proposal(draft: GoalDraft, sessions: List<TaskEngine.GoalStep>, now: ZonedDateTime): String {
        val t = draft.time!!
        val time = String.format(Locale.US, "%d:%02d %s", if (t.hour % 12 == 0) 12 else t.hour % 12, t.minute, if (t.hour < 12) "AM" else "PM")
        val dueText = draft.due!!.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + draft.due.dayOfMonth + " " +
            draft.due.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        return "Plan for your ${draft.subject} on $dueText: ${sessions.size} daily study reminder(s) at $time from tomorrow, " +
            "covering ${draft.topics!!.joinToString()}. Shall I schedule it? Say yes, no, or another time."
    }

    private fun plan(draft: GoalDraft, now: ZonedDateTime): GoalStepResult {
        val sessions = sessions(draft, now).filter { it.at > now.toInstant().toEpochMilli() }
        if (sessions.isEmpty()) return GoalStepResult.Explain("There are no days left before your ${draft.subject} to plan sessions on.")
        return GoalStepResult.Ready(draft, sessions, "Prepare for ${draft.subject}")
    }
}

/** What JARVIS says about reminders: only what the engine actually did. */
object TaskReplies {
    fun created(result: CreateResult, title: String, at: ZonedDateTime, now: ZonedDateTime): String = when (result) {
        is CreateResult.Failed -> "I couldn't schedule that reminder: ${result.reason}."
        is CreateResult.Scheduled -> "I'll remind you to ${yourWords(title)} at ${ReminderTime.describe(at, now)}." +
            (if (!result.exact) " Android hasn't allowed exact alarms, so it may be a few minutes late." else "") +
            (if (result.notificationsOff) " Notifications for JARVIS are off, so you won't see it — turn them on in Settings." else "")
    }

    /** "review my project" → "review your project" when JARVIS says it back. */
    fun yourWords(text: String): String = text
        .replace(Regex("""\bmyself\b""", RegexOption.IGNORE_CASE), "yourself")
        .replace(Regex("""\bmine\b""", RegexOption.IGNORE_CASE), "yours")
        .replace(Regex("""\bmy\b""", RegexOption.IGNORE_CASE), "your")

    fun describe(task: com.jarvis.data.AgentTask, now: ZonedDateTime): String {
        val at = task.scheduledAt?.let { ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), now.zone) }
        return task.objective + (at?.let { " at " + ReminderTime.describe(it, now) } ?: "") +
            (if (task.status == TaskStates.PAUSED) " (paused)" else "")
    }

    fun list(tasks: kotlin.collections.List<com.jarvis.data.AgentTask>, goals: kotlin.collections.List<com.jarvis.data.Goal>, now: ZonedDateTime): String {
        val reminders = tasks.filter { it.kind == TaskKinds.REMINDER && it.status in TaskStates.ACTIVE }.sortedBy { it.scheduledAt }
        val activeGoals = goals.filter { it.status == "active" || it.status == "paused" }
        val head = when (reminders.size) {
            0 -> "You have no pending reminders."
            1 -> "You have 1 reminder: ${describe(reminders.single(), now)}."
            else -> "You have ${reminders.size} reminders: " + reminders.joinToString("; ") { describe(it, now) } + "."
        }
        return head + if (activeGoals.isEmpty()) "" else " Plans: " + activeGoals.joinToString("; ") { "${it.title} (${it.status})" } + "."
    }

    /** The newest reminder for "the one I just created"; otherwise reminders whose words match [query]. */
    fun match(active: kotlin.collections.List<com.jarvis.data.AgentTask>, query: String, latest: Boolean): kotlin.collections.List<com.jarvis.data.AgentTask> {
        if (latest || query.isBlank()) return if (latest || active.size == 1) listOfNotNull(active.maxByOrNull { it.createdAt }) else active
        val words = query.lowercase(Locale.ROOT).split(' ').filter { it.length > 2 }
        return active.filter { task -> words.any { task.objective.lowercase(Locale.ROOT).contains(it) } }
    }
}
