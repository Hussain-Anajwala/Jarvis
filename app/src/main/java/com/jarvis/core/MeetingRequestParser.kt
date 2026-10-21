package com.jarvis.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

data class MeetingRequestDetails(
    val hourOfDay: Int,
    val minute: Int,
    val location: String,
    val title: String?,
    val date: LocalDate
)

/**
 * Meeting requests in English and Roman Hinglish (Devanagari is normalised first): what it is about, when, and where.
 * Time and day use the reminder rules ([ReminderTime]: "10 AM", "subah 10 baje", "kal", "parso", "Friday", dates), so a
 * missing AM/PM is asked, never guessed, and a time with no day means the next time that clock time comes round. The
 * title is the user's own words — the purpose before the event word ("DevOps project evaluation meeting") or a purpose
 * phrase after it ("Meeting with Prof. Rao about the viva") — without scheduling words; with no purpose it is null and
 * JARVIS asks.
 */
object MeetingRequestParser {
    private val eventWord = Regex("""\b(meeting|appointment|visit|interview)\b""", RegexOption.IGNORE_CASE)
    /** Each "at X" / "in X" on its own ("at 10 AM at college" → "10 AM", "college"). */
    private val locationPattern = Regex("""\b(?:at|in|near)\s+((?:(?!\s+(?:at|in|near)\s)[^,.!?])+)""", RegexOption.IGNORE_CASE)
    /** "VJTI mein", "library me": the Hinglish postposition after a place. */
    private val hinglishPlace = Regex("""\b([A-Za-z][\w.'&-]*(?:\s+[A-Za-z][\w.'&-]*){0,2})\s+(?:mein|me|par|pe)\b""", RegexOption.IGNORE_CASE)
    private val calendarTitlePattern = Regex(
        """\b(?:put|add|schedule|book|create)\s+(?:a|an|the)?\s*(.+?)\s+on\s+(?:my\s+)?calendar\b""",
        RegexOption.IGNORE_CASE
    )
    private val nearbyVenuePattern = Regex(
        """\b([A-Za-z0-9][A-Za-z0-9'&-]*(?:\s+[A-Za-z0-9][A-Za-z0-9'&-]*){0,3}\s+near\s+[^,.!?]+)""",
        RegexOption.IGNORE_CASE
    )
    /** A purpose phrase after the event word. */
    private val purpose = Regex(
        """\b(with|about|regarding|to discuss|to plan|to review)\s+(.+?)(?=\s+(?:today|tomorrow|tonight|day after tomorrow|on\s+(?:the\s+)?\w+|at\s|in\s|kal|aaj|parso|parson|next\s)|\s*[,!?]|\s*\.$|$)""",
        RegexOption.IGNORE_CASE
    )
    private val leadFiller = Regex(
        """^(?:(?:please|hey jarvis|jarvis|ok|okay)\s*,?\s+)*(?:i\s+(?:have|have got|want|need)(?:\s+to\s+(?:schedule|book|add|set up))?|i've got|there(?:'s| is)|schedule|set up|book|add|create|put)?\s*(?:a|an|the|my)?\s*""",
        RegexOption.IGNORE_CASE
    )
    /** Scheduling and filler words that never belong in a title. */
    private val schedulingWord = Regex(
        """^(?:today|tomorrow|tonight|day|after|kal|aaj|parso|parson|subah|shaam|raat|dopahar|morning|evening|afternoon|night|at|on|this|next|baje|ko|hai|hain|ek|meri|mera|mere|a|an|the|my|i|have|(?:mon|tues|wednes|thurs|fri|satur|sun)day|\d{1,2}(?::\d{2})?|am|pm|a\.m\.|p\.m\.)$""",
        RegexOption.IGNORE_CASE
    )
    private val purposeTail = Regex("""\s+(?:to discuss|to plan|to review|about|regarding|with|for)\b.*$""", RegexOption.IGNORE_CASE)

    fun isMeetingIntent(text: String): Boolean = eventWord.containsMatchIn(text) || calendarTitlePattern.containsMatchIn(text)

    /** What the request says, field by field; a missing field is null (asked for, never filled in). */
    data class Draft(val title: String?, val hour: Int?, val minute: Int?, val date: LocalDate?, val location: String?)

    fun draft(text: String, today: LocalDate): Draft = draft(text, today.atTime(LocalTime.now()).atZone(java.time.ZoneId.systemDefault()))

    fun draft(text: String, now: ZonedDateTime = ZonedDateTime.now()): Draft {
        val normalized = ReminderTime.normalize(text)
        val time = ReminderTime.parse(normalized, now) as? WhenParse.Exact
        // A weekday for a meeting is the coming one, today included ("Friday" said on a Friday), as before.
        val weekday = weekdayDate(normalized, now.toLocalDate())
        val day = weekday ?: (ReminderTime.date(normalized, now.toLocalDate()) as? ReminderTime.DateWords.Valid)?.date
            ?: ReminderTime.day(normalized, now.toLocalDate())
        return Draft(explicitTitle(text), time?.at?.hour, time?.at?.minute, weekday ?: time?.at?.toLocalDate() ?: day, parseLocation(normalized))
    }

    private val weekdayPattern = Regex("""\b(next\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""", RegexOption.IGNORE_CASE)

    private fun weekdayDate(text: String, today: LocalDate): LocalDate? = weekdayPattern.find(text)?.let { m ->
        val target = java.time.DayOfWeek.valueOf(m.groupValues[2].uppercase(java.util.Locale.ROOT))
        today.with(if (m.groupValues[1].isBlank()) java.time.temporal.TemporalAdjusters.nextOrSame(target) else java.time.temporal.TemporalAdjusters.next(target))
    }

    private val ampmTime = Regex("""\b\d{1,2}(?::\d{2})?\s*(?:a\.?m\.?|p\.?m\.?)(?=\s|$|[,.!?])""", RegexOption.IGNORE_CASE)
    private val approximateTime = Regex("""\b(?:around|about|at)\s+(\d{1,2})\b(?!\s*(?::|a\.?m|p\.?m))""", RegexOption.IGNORE_CASE)
    private val dayWord = Regex("""\b(?:today|tomorrow|tonight|kal|aaj|parso|parson|(?:next\s+)?(?:mon|tues|wednes|thurs|fri|satur|sun)day)\b""", RegexOption.IGNORE_CASE)

    fun explicitTitle(raw: String): String? {
        val text = ReminderTime.normalize(raw)
        calendarTitlePattern.find(text)?.groupValues?.get(1)?.let(::cleanTitle)?.let { return it }
        // No event word ("Project Review, 10 AM at college", answering "what time and where?"): the words before the
        // first time, day, or place.
        val event = eventWord.find(text) ?: return listOfNotNull(
            ampmTime.find(text)?.range?.first, approximateTime.find(text)?.range?.first, dayWord.find(text)?.range?.first,
            locationPattern.find(text)?.range?.first
        ).minOrNull()?.let { cut ->
            text.substring(0, cut).split(Regex("""\s+""")).map { it.trim(',', '.', '!', '?') }.filter { it.isNotBlank() }
                .filterNot { schedulingWord.matches(it) }.takeIf { it.isNotEmpty() }?.joinToString(" ")?.let(::cleanTitle)
        }
        // The purpose before the event word: "DevOps project evaluation meeting", "Doctor appointment".
        val head = text.substring(0, event.range.first)
        val place = hinglishPlace.find(head)
        val before = (place?.let { head.removeRange(it.range) } ?: head).replace(leadFiller, "")
        val words = before.split(Regex("""\s+""")).map { it.trim(',', '.', '!', '?') }.filter { it.isNotBlank() }
            .filterNot { schedulingWord.matches(it) }
        if (words.isNotEmpty()) return cleanTitle(words.joinToString(" ") + " " + event.value)
        // Or a purpose phrase after it: "meeting with Prof. Rao about the viva tomorrow at 4 PM".
        purpose.find(text, event.range.last + 1)?.let { m ->
            val rest = m.groupValues[2].trim().trimEnd('.', ',')
            if (rest.isNotBlank() && !rest.first().isDigit()) return cleanTitle("${event.value} ${m.groupValues[1]} $rest")
        }
        return null
    }

    fun missingDetails(text: String): List<String> = buildList {
        val d = draft(text)
        if (d.hour == null) add("time")
        if (d.location.isNullOrBlank()) add("location")
    }

    fun clarificationQuestion(text: String, missing: List<String>): String {
        val normalized = ReminderTime.normalize(text)
        val hour = (ReminderTime.parse(normalized, ZonedDateTime.now()) as? WhenParse.NeedsAmPm)?.hour
            ?: approximateTime.find(normalized)?.groupValues?.get(1)?.toIntOrNull()
        if ("time" in missing && hour != null) {
            return "What time do you mean—$hour AM or $hour PM?"
        }
        return when {
            "time" in missing && "location" in missing -> "What time and where is the meeting?"
            "time" in missing -> "What time is the meeting?"
            else -> "Where is the meeting?"
        }
    }

    fun parse(text: String): MeetingRequestDetails? {
        if (!isMeetingIntent(text)) return null
        val d = draft(text)
        return MeetingRequestDetails(
            hourOfDay = d.hour ?: return null,
            minute = d.minute ?: return null,
            location = d.location?.takeIf { it.isNotBlank() } ?: return null,
            title = d.title,
            date = d.date ?: return null
        )
    }

    private fun parseLocation(withTimes: String): String? {
        // Times are never part of a place ("clinic near Dadar 6 PM").
        val text = ampmTime.replace(withTimes, " ")
        nearbyVenuePattern.find(text)?.groupValues?.get(1)?.let { nearbyVenue ->
            return nearbyVenue.trim().trimEnd(' ', ',', '.', ';', ':')
        }
        // English "at/in X" first; the Hinglish "X mein" only when there is none ("remind me" is not a place).
        // Only the words after the last scheduling or event word: "meeting hai office mein" → "office".
        return englishLocation(text) ?: hinglishPlace.find(text)?.groupValues?.get(1)?.let { phrase ->
            phrase.split(Regex("""\s+""")).takeLastWhile { !schedulingWord.matches(it) && !eventWord.matches(it) }
                .takeIf { it.isNotEmpty() }?.joinToString(" ")
        }
    }

    private fun englishLocation(text: String): String? {
        return locationPattern.findAll(text)
            .mapNotNull { match ->
                match.groupValues[1]
                    .replace(Regex("""\s+\b(?:today|tomorrow|tonight|at)\b.*$""", RegexOption.IGNORE_CASE), "")
                    .replace(purposeTail, "")
                    .trim()
                    .replace(Regex("""^(?:at|in|near)\s+""", RegexOption.IGNORE_CASE), "")
                    .trimEnd(' ', ',', '.', ';', ':')
                    // "at 10 AM", "at 5": a time, not a place.
                    .takeIf { it.isNotBlank() && !it.first().isDigit() && !Regex("""^(?:noon|midnight)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            }
            .lastOrNull()
    }

    private fun cleanTitle(value: String): String? {
        val title = value
            .replace(
                Regex(
                    """^(?:please\s+)?(?:i\s+(?:have|want|need)\s+|(?:put|add|schedule|book|create)\s+)(?:a|an|the)?\s*""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .trim()
            .trim(',', '.', '!', '?', '"', '\'')
            .trim()
            .takeIf(String::isNotBlank)
            ?: return null
        if (title.equals("a", ignoreCase = true) || title.equals("the", ignoreCase = true)) return null
        return title.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
