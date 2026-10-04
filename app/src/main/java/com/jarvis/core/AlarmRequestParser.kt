package com.jarvis.core

data class AlarmRequest(val hour: Int, val minute: Int, val label: String)

object AlarmRequestParser {
    private val intentPattern = Regex("""^\s*(?:please\s+)?(?:set|create)\s+(?:an?\s+)?alarm\b""", RegexOption.IGNORE_CASE)
    private val timePattern = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)\b""", RegexOption.IGNORE_CASE)

    fun isAlarmIntent(text: String): Boolean = intentPattern.containsMatchIn(text)

    fun parse(text: String): AlarmRequest? {
        if (!isAlarmIntent(text)) return null
        val match = timePattern.find(text) ?: return null
        val rawHour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
        if (rawHour !in 1..12 || minute !in 0..59) return null
        val isPm = match.groupValues[3].startsWith("p", ignoreCase = true)
        val hour = (rawHour % 12) + if (isPm) 12 else 0
        val label = text.substring(match.range.last + 1)
            .trim()
            .removePrefix("to ")
            .removePrefix("for ")
            .trimEnd('.', '!', '?')
            .ifBlank { "JARVIS alarm" }
        return AlarmRequest(hour, minute, label)
    }
}
