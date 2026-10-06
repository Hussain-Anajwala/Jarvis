package com.jarvis.core

data class MeetingRequestDetails(
    val hourOfDay: Int,
    val minute: Int,
    val location: String
)

object MeetingRequestParser {
    private val meetingIntentPattern = Regex("""\b(meeting|appointment)\b""", RegexOption.IGNORE_CASE)
    private val timePattern = Regex(
        """\b(?:(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)(?=\s|$|[,.!?])|([01]?\d|2[0-3]):([0-5]\d)\b)""",
        RegexOption.IGNORE_CASE
    )
    private val locationPattern = Regex("""\b(?:at|in|near)\s+([^,.!?]+)""", RegexOption.IGNORE_CASE)

    fun isMeetingIntent(text: String): Boolean = meetingIntentPattern.containsMatchIn(text)

    fun missingDetails(text: String): List<String> = buildList {
        if (timePattern.find(text)?.let(::parseTime) == null) add("time")
        if (parseLocation(text).isNullOrBlank()) add("location")
    }

    fun parse(text: String): MeetingRequestDetails? {
        if (!isMeetingIntent(text)) return null
        val time = timePattern.find(text)?.let(::parseTime) ?: return null
        val location = parseLocation(text) ?: return null
        return MeetingRequestDetails(time.first, time.second, location)
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
}
