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
    /** A date that does not exist or a day word JARVIS can't read ("31st of November", "on Funday"): ask, never use today. */
    data class BadDay(val phrase: String) : WhenParse
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

    private const val MONTHS = "january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec"
    /** "21st of December", "21 Dec", "December 21st" ("May" first only with an ordinal: "May 5th", not "may 5 pm"). */
    private val dayMonth = Regex("""\b(?:on\s+)?(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($MONTHS)\b""", RegexOption.IGNORE_CASE)
    private val monthDay = Regex("""\b(?:on\s+)?(january|february|march|april|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec|may(?=\s+\d{1,2}(?:st|nd|rd|th)))\s+(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)?\b(?!\s*(?::|\.\d|a\.?m|p\.?m))""", RegexOption.IGNORE_CASE)
    /** "on Funday": a day-like word after "on" that is not a weekday. */
    private val oddDay = Regex("""\bon\s+([a-z]+day)\b""", RegexOption.IGNORE_CASE)
    private val weekdays = DayOfWeek.values().map { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase(Locale.ROOT) }.toSet() + setOf("today", "yesterday")

    sealed interface DateWords {
        data class Valid(val date: LocalDate) : DateWords
        data class Invalid(val phrase: String) : DateWords
    }

    /** A calendar date or an unreadable day word in [text]: this year's date, or next year's once it has passed. */
    fun date(text: String, today: LocalDate): DateWords? {
        val m = dayMonth.find(text) ?: monthDay.find(text)
        if (m != null) {
            val (dayText, monthText) = if (m.groupValues[1].first().isDigit()) m.groupValues[1] to m.groupValues[2] else m.groupValues[2] to m.groupValues[1]
            val month = java.time.Month.values().first { it.name.lowercase(Locale.ROOT).startsWith(monthText.lowercase(Locale.ROOT).take(3)) }
            val day = dayText.toInt()
            if (day !in 1..month.maxLength()) return DateWords.Invalid(m.value.trim())
            var date = runCatching { LocalDate.of(today.year, month, day) }.getOrNull()
            if (date == null || date.isBefore(today)) date = runCatching { LocalDate.of(today.year + 1, month, day) }.getOrNull()
            return date?.let { DateWords.Valid(it) } ?: DateWords.Invalid(m.value.trim())
        }
        oddDay.find(text)?.let { odd -> if (odd.groupValues[1].lowercase(Locale.ROOT) !in weekdays) return DateWords.Invalid(odd.value.trim()) }
        return null
    }

    /** Day words found in [text], relative to [today]. */
    fun day(text: String, today: LocalDate): LocalDate? {
        val t = text.lowercase(Locale.ROOT)
        if (Regex("""\bday after tomorrow\b|\b(?:parson|parso|parsoon|parsson)\b""").containsMatchIn(t)) return today.plusDays(2)
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
        val explicit = date(text, now.toLocalDate())
        if (explicit is DateWords.Invalid) return WhenParse.BadDay(explicit.phrase)
        if (halfHour.containsMatchIn(text)) return WhenParse.Exact(now.plusMinutes(30).truncatedTo(ChronoUnit.MINUTES))
        relative.find(text)?.let { m ->
            val amount = (m.groupValues[1].ifBlank { m.groupValues[3] }).lowercase(Locale.ROOT)
            val n = amount.toIntOrNull() ?: numbers[amount] ?: return@let
            val unit = (m.groupValues[2].ifBlank { m.groupValues[4] }).lowercase(Locale.ROOT)
            val minutes = if (unit.startsWith("h") || unit.startsWith("ghant")) n * 60L else n.toLong()
            if (minutes in 1..(7 * 24 * 60)) return WhenParse.Exact(now.plusMinutes(minutes).truncatedTo(ChronoUnit.MINUTES))
        }
        val day = (explicit as? DateWords.Valid)?.date ?: day(text, now.toLocalDate())
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
            // "tonight at 12" / "raat 12 baje" is midnight; "afternoon at 12" is noon.
            hour == 12 && evening.containsMatchIn(text) -> if (Regex("""\b(afternoon|dopahar)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) 12 else 0
            evening.containsMatchIn(text) -> (hour % 12) + 12
            else -> return WhenParse.NeedsAmPm(day, hour, minute)
        }
        // Midnight on a named day is the end of that day ("today at midnight" is tonight, not 14 hours ago).
        if (h24 == 0 && minute == 0 && day != null) return WhenParse.Exact(at(day.plusDays(1), 0, 0, now))
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
        .replace(dayMonth, " ").replace(monthDay, " ").replace(oddDay, " ")
        .replace(halfHour, " ").replace(relative, " ").replace(clock, " ")
        .replace(Regex("""\b(kal|aaj|parson|parso|parsoon|parsson|subah|shaam|raat|dopahar)\b""", RegexOption.IGNORE_CASE), " ")
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
    /** [scope]: "all", "today" or "tomorrow". */
    data class List(val scope: String = "all") : ReminderCommand
    data class Cancel(val latest: Boolean, val query: String) : ReminderCommand
    data class PauseGoal(val query: String) : ReminderCommand
    data class ResumeGoal(val query: String) : ReminderCommand
}

object ReminderCommands {
    private val create = Regex(
        """^\s*(?:please\s+|hey\s+jarvis,?\s+|jarvis,?\s+|can you\s+|could you\s+)*(?:remind\s+me\b|(?:create|set|make|add|schedule|put)\s+(?:up\s+)?(?:a\s+|an\s+|me\s+a\s+|one\s+|new\s+)*reminder\b)""",
        RegexOption.IGNORE_CASE
    )
    /** "gym ka reminder laga do", "reminder set kar do". */
    private val hinglishReminder = Regex("""\breminder\s+(?:laga|set\s+kar|bana|daal)\s*(?:do|dena|o|na|diya)?\b""", RegexOption.IGNORE_CASE)
    /** "… and label it DevOps project", "called submit assignment": the user's own name for the reminder. */
    private val label = Regex(
        """(?:\s*,?\s*(?:and\s+)?(?:label|name|title|call)\s+it\s+|\b(?:called|named|titled|labell?ed|saying)\s+)["“']?(.+?)["”']?(?=\s+(?:for|at|on|in|tomorrow|today|tonight)\b|[.!]?\s*$)""",
        RegexOption.IGNORE_CASE
    )
    /** "… ki yaad dilana", "yaad dila do": a Hinglish reminder request anywhere in the sentence. */
    private val hinglishCreate = Regex("""\byaad\s+dila(?:na|o|iye|ana|\s+do|\s+dena)?\b""", RegexOption.IGNORE_CASE)
    private val hinglishFiller = Regex("""\b(mujhe|mujhko|zara|please)\b|\b(?:ki|ka|ke)?\s*yaad\s+dila(?:na|o|iye|ana|\s+do|\s+dena)?\b""", RegexOption.IGNORE_CASE)
    /** A question about reminders, in English or Hinglish ("batao", "dikhao", "kab hai"); it only ever reads. */
    private val list = Regex(
        """\b(list|show|what are|what's|whats|which|tell me|read|what|any|check)\b.{0,30}\breminders?\b|\breminders?\b.{0,30}\b(do i have|have i (?:got|set)|are (?:there|set|pending))\b|^\s*(?:my\s+)?(?:pending|upcoming|today'?s|tomorrow'?s|all)?\s*reminders\s*\??$|""" +
            """\breminders?\b.{0,30}\b(batao|bata\s+do|bataiye|bataye|dikhao|dikha\s+do|dikhaiye|dikhaye|kab|kya|kitne|kaun\s+se|ka\s+time)\b|\b(batao|dikhao|kitne|kaun\s+se)\b.{0,20}\breminders?\b""",
        RegexOption.IGNORE_CASE
    )
    private val today = Regex("""\b(today|today'?s|todays|tonight|aaj)\b""", RegexOption.IGNORE_CASE)
    private val tomorrow = Regex("""\b(tomorrow|tomorrow'?s|kal)\b""", RegexOption.IGNORE_CASE)
    private val cancel = Regex("""\b(cancel|delete|remove|stop|clear|forget)\b.{0,40}\breminders?\b|\breminders?\b.{0,20}\b(cancel|delete|remove|hatao|hata\s+do|hata\s+dena|mita\s+do)\b""", RegexOption.IGNORE_CASE)
    private val latest = Regex("""\b(just|last|latest|recent|akhri|aakhri|akhiri|aakhiri|antim|pichla|pichhla|i\s+(?:just\s+)?(?:set|created|made|added)|you\s+(?:just\s+)?(?:set|created))\b""", RegexOption.IGNORE_CASE)
    private val pause = Regex("""\b(pause|hold|suspend|stop for now)\b.{0,30}\b(plan|goal|schedule)\b|\b(plan|goal)\b.{0,15}\bpause\b""", RegexOption.IGNORE_CASE)
    private val resume = Regex("""\b(resume|restart|continue|unpause|start again)\b.{0,30}\b(plan|goal|schedule)\b|\b(plan|goal)\b.{0,15}\bresume\b""", RegexOption.IGNORE_CASE)
    private val filler = Regex("""\b(cancel|delete|remove|stop|clear|forget|pause|hold|suspend|resume|restart|continue|unpause|the|my|this|that|a|reminders?|plan|goal|schedule|i|just|set|created|made|added|last|latest|recent|please|you|for|now|again|about|to|mera|mere|meri|wala|wali|hata|hatao|do|karo|kar|dena|akhri|aakhri)\b""", RegexOption.IGNORE_CASE)

    fun parse(text: String): ReminderCommand? = when {
        create.containsMatchIn(text) || hinglishCreate.containsMatchIn(text) || hinglishReminder.containsMatchIn(text) -> ReminderCommand.Create(text)
        cancel.containsMatchIn(text) -> ReminderCommand.Cancel(latest.containsMatchIn(text), query(text))
        pause.containsMatchIn(text) -> ReminderCommand.PauseGoal(query(text))
        resume.containsMatchIn(text) -> ReminderCommand.ResumeGoal(query(text))
        list.containsMatchIn(text) -> ReminderCommand.List(when {
            today.containsMatchIn(text) -> "today"
            tomorrow.containsMatchIn(text) -> "tomorrow"
            else -> "all"
        })
        else -> null
    }

    private fun query(text: String) = text.replace(filler, " ").replace(Regex("""[^\p{L}\p{N} ]"""), " ").replace(Regex("""\s+"""), " ").trim()

    /** What the reminder is about: "remind me to stretch in 30 minutes" → "stretch". */
    fun title(text: String): String? {
        label.find(text)?.let { m -> clean(ReminderTime.strip(m.groupValues[1]))?.let { return it } }
        return clean(ReminderTime.strip(text.replace(create, " ").replace(hinglishReminder, " ").replace(hinglishFiller, " ")))
    }

    /** Null when only linking words are left ("Create a reminder for 8:30 PM" → "for"): the title is then asked for. */
    private fun clean(raw: String): String? = raw.replace(Regex("""^(?:to|about|that|of)\s+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+(?:ki|ka|ke|ko)$""", RegexOption.IGNORE_CASE), "")
        .trim().trim(',', '.', '!')
        .takeIf { it.length >= 2 && !Regex("""^(?:for|at|on|in|to|about|that|of|and|me|a|an|the|it|ki|ka|ke|ko)$""", RegexOption.IGNORE_CASE).matches(it) }
}

enum class ReminderSlot { TITLE, TIME, AMPM, DAY }

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
                    is WhenParse.BadDay, WhenParse.None -> draft
                }
            }
            ReminderSlot.DAY -> {
                // The answer takes the place of the unreadable day in the original request, keeping its time.
                val bad = draft.time as? WhenParse.BadDay ?: return next(draft, now)
                val today = now.toLocalDate()
                if (ReminderTime.day(answer, today) == null && ReminderTime.date(answer, today) !is ReminderTime.DateWords.Valid) draft
                else when (val parsed = ReminderTime.parse(draft.origin.replace(bad.phrase, " " + answer.trim().trim('.', '!') + " "), now)) {
                    is WhenParse.BadDay, WhenParse.None -> draft
                    else -> draft.copy(time = parsed)
                }
            }
        }
        if (updated == draft) {
            return ReminderStep.Ask(slot, when (slot) {
                ReminderSlot.TITLE -> "What should I remind you about?"
                ReminderSlot.TIME -> "When? For example \"in 30 minutes\" or \"tomorrow at 9 AM\"."
                ReminderSlot.AMPM -> "Morning or evening — AM or PM?"
                ReminderSlot.DAY -> "Which day? For example \"tomorrow\" or \"30 November\"."
            }, draft)
        }
        return next(updated, now)
    }

    fun next(draft: ReminderDraft, now: ZonedDateTime): ReminderStep = when {
        draft.title == null -> ReminderStep.Ask(ReminderSlot.TITLE, "What should I remind you about?", draft)
        draft.time is WhenParse.BadDay -> ReminderStep.Ask(ReminderSlot.DAY, "I can't find a date for \"${draft.time.phrase}\". Which day did you mean?", draft)
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
    fun created(result: CreateResult, title: String, at: ZonedDateTime, now: ZonedDateTime, lang: ReplyLang = ReplyLang.EN): String = when {
        lang != ReplyLang.EN -> createdLocal(result, title, at, now, lang)
        else -> createdEn(result, title, at, now)
    }

    private fun createdLocal(result: CreateResult, title: String, at: ZonedDateTime, now: ZonedDateTime, lang: ReplyLang): String {
        val hi = lang == ReplyLang.HINDI
        val whenText = LocalReplies.whenText(at, now, lang)
        return when (result) {
            is CreateResult.Failed -> if (hi) "यह रिमाइंडर नहीं लग पाया: ${result.reason}।" else "Yeh reminder nahi lag paaya: ${result.reason}."
            is CreateResult.Scheduled -> (if (hi) "ठीक है, $whenText पर आपको \"$title\" याद दिला दूँगा।" else "Theek hai, $whenText pe aapko \"$title\" yaad dila dunga.") +
                (if (!result.exact) (if (hi) " Android ने exact alarm की अनुमति नहीं दी है, इसलिए कुछ मिनट देर हो सकती है।" else " Android ne exact alarm ki permission nahi di hai, isliye kuch minute late ho sakta hai.") else "") +
                (if (result.notificationsOff) (if (hi) " JARVIS की notifications बंद हैं, इसलिए आपको दिखेगा नहीं — Settings में चालू कीजिए।" else " JARVIS ki notifications band hain, isliye aapko dikhega nahi — Settings mein on kijiye.") else "")
        }
    }

    private fun createdEn(result: CreateResult, title: String, at: ZonedDateTime, now: ZonedDateTime): String = when (result) {
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

    /**
     * Pending reminders from both stores — standalone and goal reminders (agent_tasks) and meeting reminders (reminders,
     * linked to a plan item) — limited to [scope] ("all", "today", "tomorrow"). Reads only; nothing is changed.
     */
    fun list(
        tasks: kotlin.collections.List<com.jarvis.data.AgentTask>,
        goals: kotlin.collections.List<com.jarvis.data.Goal>,
        now: ZonedDateTime,
        meetingReminders: kotlin.collections.List<com.jarvis.data.Reminder> = emptyList(),
        scope: String = "all",
        lang: ReplyLang = ReplyLang.EN
    ): String {
        val wanted: LocalDate? = when (scope) { "today" -> now.toLocalDate(); "tomorrow" -> now.toLocalDate().plusDays(1); else -> null }
        fun inScope(at: Long?): Boolean = wanted == null ||
            (at != null && java.time.Instant.ofEpochMilli(at).atZone(now.zone).toLocalDate() == wanted)
        val entries = tasks.filter { it.kind == TaskKinds.REMINDER && it.status in TaskStates.ACTIVE && inScope(it.scheduledAt) }
            .map { (it.scheduledAt ?: Long.MAX_VALUE) to describe(it, now) } +
            meetingReminders.filter { it.status == "scheduled" && inScope(it.triggerTime) }
                .map { r -> (r.triggerTime ?: Long.MAX_VALUE) to (r.title + (r.triggerTime?.let { " at " + ReminderTime.describe(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), now.zone), now) } ?: "") + " (meeting plan)") }
        val sorted = entries.sortedBy { it.first }.map { it.second }
        if (lang != ReplyLang.EN) return listLocal(tasks, meetingReminders, now, wanted, scope, goals, lang)
        val label = when (scope) { "today" -> " for today"; "tomorrow" -> " for tomorrow"; else -> "" }
        val head = when (sorted.size) {
            0 -> if (wanted == null) "You have no pending reminders." else "You have no reminders$label."
            1 -> "You have 1 reminder$label: ${sorted.single()}."
            else -> "You have ${sorted.size} reminders$label: " + sorted.joinToString("; ") + "."
        }
        val activeGoals = goals.filter { it.status == "active" || it.status == "paused" }
        return head + if (activeGoals.isEmpty() || wanted != null) "" else " Plans: " + activeGoals.joinToString("; ") { "${it.title} (${it.status})" } + "."
    }

    private fun listLocal(
        tasks: kotlin.collections.List<com.jarvis.data.AgentTask>,
        meetingReminders: kotlin.collections.List<com.jarvis.data.Reminder>,
        now: ZonedDateTime,
        wanted: LocalDate?,
        scope: String,
        goals: kotlin.collections.List<com.jarvis.data.Goal>,
        lang: ReplyLang
    ): String {
        val hi = lang == ReplyLang.HINDI
        fun inScope(at: Long?) = wanted == null || (at != null && java.time.Instant.ofEpochMilli(at).atZone(now.zone).toLocalDate() == wanted)
        fun at(ms: Long) = LocalReplies.whenText(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), now.zone), now, lang)
        val entries = tasks.filter { it.kind == TaskKinds.REMINDER && it.status in TaskStates.ACTIVE && inScope(it.scheduledAt) }
            .map { t -> (t.scheduledAt ?: Long.MAX_VALUE) to (t.objective + (t.scheduledAt?.let { " — " + at(it) } ?: "") +
                (if (t.status == TaskStates.PAUSED) (if (hi) " (रुका हुआ)" else " (paused)") else "")) } +
            meetingReminders.filter { it.status == "scheduled" && inScope(it.triggerTime) }
                .map { r -> (r.triggerTime ?: Long.MAX_VALUE) to (r.title + (r.triggerTime?.let { " — " + at(it) } ?: "") + (if (hi) " (मीटिंग)" else " (meeting)")) }
        val sorted = entries.sortedBy { it.first }.map { it.second }
        val label = when (scope) {
            "today" -> if (hi) " आज के लिए" else " aaj ke liye"
            "tomorrow" -> if (hi) " कल के लिए" else " kal ke liye"
            else -> ""
        }
        val head = when {
            sorted.isEmpty() -> if (hi) "आपका$label कोई रिमाइंडर नहीं है।" else "Aapka$label koi reminder nahi hai."
            hi -> "आपके$label ${sorted.size} रिमाइंडर हैं: " + sorted.joinToString("; ") + "।"
            else -> "Aapke$label ${sorted.size} reminder hain: " + sorted.joinToString("; ") + "."
        }
        val activeGoals = goals.filter { it.status == "active" || it.status == "paused" }
        return head + if (activeGoals.isEmpty() || wanted != null) "" else (if (hi) " योजनाएँ: " else " Plans: ") + activeGoals.joinToString("; ") { "${it.title} (${it.status})" } + "."
    }

    /** The newest reminder for "the one I just created"; otherwise reminders whose words match [query]. */
    fun match(active: kotlin.collections.List<com.jarvis.data.AgentTask>, query: String, latest: Boolean): kotlin.collections.List<com.jarvis.data.AgentTask> {
        if (latest || query.isBlank()) return if (latest || active.size == 1) listOfNotNull(active.maxByOrNull { it.createdAt }) else active
        val words = query.lowercase(Locale.ROOT).split(' ').filter { it.length > 2 }
        return active.filter { task -> words.any { task.objective.lowercase(Locale.ROOT).contains(it) } }
    }
}
