package com.jarvis.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class MeetingRequestDetails(
    val hourOfDay: Int,
    val minute: Int,
    val location: String,
    val title: String?,
    val date: LocalDate
)

object MeetingRequestParser {
    private val meetingIntentPattern = Regex(
        """\b(meeting|appointment|visit)\b""",
        RegexOption.IGNORE_CASE
    )
    private val timePattern = Regex(
        """\b(?:(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)(?=\s|$|[,.!?])|([01]?\d|2[0-3]):([0-5]\d)\b)""",
        RegexOption.IGNORE_CASE
    )
    private val locationPattern = Regex("""\b(?:at|in|near)\s+([^,.!?]+)""", RegexOption.IGNORE_CASE)
    private val dayPattern = Regex(
        """\b(next\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""",
        RegexOption.IGNORE_CASE
    )
    private val approximateTimePattern = Regex(
        """\b(?:around|about|at)\s+(\d{1,2})\b""",
        RegexOption.IGNORE_CASE
    )
    private val calendarTitlePattern = Regex(
        """\b(?:put|add|schedule|book|create)\s+(?:a|an|the)?\s*(.+?)\s+on\s+(?:my\s+)?calendar\b""",
        RegexOption.IGNORE_CASE
    )
    private val nearbyVenuePattern = Regex(
        """\b([A-Za-z0-9][A-Za-z0-9'&-]*(?:\s+[A-Za-z0-9][A-Za-z0-9'&-]*){0,3}\s+near\s+[^,.!?]+)""",
        RegexOption.IGNORE_CASE
    )

    fun isMeetingIntent(text: String): Boolean = meetingIntentPattern.containsMatchIn(text)

    /** What the request says, field by field; a missing field is null (asked for, never filled in). */
    data class Draft(val title: String?, val hour: Int?, val minute: Int?, val date: LocalDate?, val location: String?)

    fun draft(text: String, today: LocalDate = LocalDate.now()): Draft {
        val time = timePattern.find(text)?.let(::parseTime)
        return Draft(explicitTitle(text), time?.first, time?.second, parseDate(text, today), parseLocation(text))
    }

    fun explicitTitle(text: String): String? {
        calendarTitlePattern.find(text)?.groupValues?.get(1)?.let(::cleanTitle)?.let { return it }

        val eventWord = Regex("""\b(meeting|appointment|visit)\b""", RegexOption.IGNORE_CASE)
            .find(text)
        if (eventWord != null) {
            text.substring(0, eventWord.range.first)
                .let(::cleanTitle)
                ?.let { return it }
        }

        val timeStart = timePattern.find(text)?.range?.first
            ?: approximateTimePattern.find(text)?.range?.first
            ?: dayPattern.find(text)?.range?.first
            ?: locationPattern.find(text)?.range?.first
        return timeStart?.let { text.substring(0, it).let(::cleanTitle) }
    }

    fun missingDetails(text: String): List<String> = buildList {
        if (timePattern.find(text)?.let(::parseTime) == null) add("time")
        if (parseLocation(text).isNullOrBlank()) add("location")
    }

    fun clarificationQuestion(text: String, missing: List<String>): String {
        val approximateHour = approximateTimePattern.find(text)?.groupValues?.get(1)
        if ("time" in missing && approximateHour != null) {
            return "What time do you mean—$approximateHour AM or $approximateHour PM?"
        }
        return when {
            "time" in missing && "location" in missing -> "What time and where is the meeting?"
            "time" in missing -> "What time is the meeting?"
            else -> "Where is the meeting?"
        }
    }

    fun parse(text: String): MeetingRequestDetails? {
        if (!isMeetingIntent(text)) return null
        val time = timePattern.find(text)?.let(::parseTime) ?: return null
        val location = parseLocation(text) ?: return null
        return MeetingRequestDetails(
            hourOfDay = time.first,
            minute = time.second,
            location = location,
            title = explicitTitle(text),
            date = parseDate(text)
        )
    }

    private fun parseTime(match: MatchResult): Pair<Int, Int>? {
        val is24HourTime = match.groupValues[4].isNotBlank()
        val rawHour = (if (is24HourTime) match.groupValues[4] else match.groupValues[1]).toIntOrNull()
            ?: return null
        val minute = (if (is24HourTime) match.groupValues[5] else match.groupValues[2])
            .ifBlank { "0" }
            .toIntOrNull()
            ?: return null
        if (is24HourTime) return rawHour to minute
        if (rawHour !in 1..12 || minute !in 0..59) return null
        val isPm = match.groupValues[3].startsWith("p", ignoreCase = true)
        return ((rawHour % 12) + if (isPm) 12 else 0) to minute
    }

    private fun parseLocation(text: String): String? {
        val timeMatch = timePattern.find(text)
        val withoutTime = timeMatch?.let { text.removeRange(it.range) } ?: text
        nearbyVenuePattern.find(withoutTime)?.groupValues?.get(1)?.let { nearbyVenue ->
            return nearbyVenue.trim().trimEnd(' ', ',', '.', ';', ':')
        }
        return locationPattern.findAll(withoutTime)
            .mapNotNull { match ->
                match.groupValues[1]
                    .replace(Regex("""\s+\b(?:today|tomorrow|tonight|at)\b.*$""", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .replace(Regex("""^(?:at|in|near)\s+""", RegexOption.IGNORE_CASE), "")
                    .trimEnd(' ', ',', '.', ';', ':')
                    .takeIf { it.isNotBlank() }
            }
            .lastOrNull()
    }

    private fun parseDate(text: String, today: LocalDate = LocalDate.now()): LocalDate {
        return when {
            Regex("""\btoday\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> today
            Regex("""\btomorrow\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> today.plusDays(1)
            else -> dayPattern.find(text)?.let { match ->
                val target = when (match.groupValues[2].lowercase()) {
                    "monday" -> DayOfWeek.MONDAY
                    "tuesday" -> DayOfWeek.TUESDAY
                    "wednesday" -> DayOfWeek.WEDNESDAY
                    "thursday" -> DayOfWeek.THURSDAY
                    "friday" -> DayOfWeek.FRIDAY
                    "saturday" -> DayOfWeek.SATURDAY
                    else -> DayOfWeek.SUNDAY
                }
                val adjuster = if (match.groupValues[1].isBlank()) {
                    TemporalAdjusters.nextOrSame(target)
                } else {
                    TemporalAdjusters.next(target)
                }
                today.with(adjuster)
            } ?: today.plusDays(1)
        }
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
