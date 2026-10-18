package com.jarvis.core

import java.util.Locale

/**
 * Rewrites an answer for the ear only (the written answer is unchanged): dates, ordinals, temperatures, percentages,
 * units, and a few unambiguous abbreviations are spelled out so any voice reads them naturally.
 *
 * Protected and never touched: web addresses, email addresses, phone numbers, clock times, and code-like IDs
 * (anything mixing letters with digits or underscores), because rewriting them would change their meaning.
 * English rules apply when the locale (or the phone's locale) is English; other languages get symbol clean-up only.
 */
object SpeechText {
    private val months = mapOf(
        "jan" to "January", "feb" to "February", "mar" to "March", "apr" to "April", "may" to "May", "jun" to "June",
        "jul" to "July", "aug" to "August", "sep" to "September", "sept" to "September", "oct" to "October",
        "nov" to "November", "dec" to "December"
    )
    private val weekdays = mapOf(
        "mon" to "Monday", "tue" to "Tuesday", "tues" to "Tuesday", "wed" to "Wednesday", "thu" to "Thursday",
        "thur" to "Thursday", "thurs" to "Thursday", "fri" to "Friday", "sat" to "Saturday", "sun" to "Sunday"
    )
    private const val MONTH = """(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"""

    /** Spans that must be read exactly as written. */
    private val protectedSpan = Regex(
        listOf(
            """(?:https?://|www\.)\S+""",                               // web addresses
            """[\w.+'-]+@[\w-]+(?:\.[\w-]+)+""",                         // email addresses
            """\+?\d[\d\s().-]{7,}\d""",                                 // phone numbers
            """\b\d{1,2}:\d{2}(?:\s?[ap]\.?m\.?)?""",                    // clock times
            """\b(?=[A-Za-z_]*\d)(?=\d*[A-Za-z_])[A-Za-z0-9_]{4,}\b"""   // IDs such as AB12CD or build_42
        ).joinToString("|"),
        RegexOption.IGNORE_CASE
    )

    fun forSpeech(text: String, locale: Locale = Locale.getDefault()): String {
        val english = locale.language == "en"
        val out = StringBuilder()
        var last = 0
        for (match in protectedSpan.findAll(text)) {
            // "18th" or "10km" are an ordinal or a measurement, not IDs, even though they mix digits and letters.
            if (Regex("""^\d+(st|nd|rd|th|km|mm|mins?|hrs?|secs?)$""", RegexOption.IGNORE_CASE).matches(match.value)) continue
            out.append(rewrite(text.substring(last, match.range.first), english))
            out.append(match.value)
            last = match.range.last + 1
        }
        out.append(rewrite(text.substring(last), english))
        return out.toString().replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    private fun rewrite(segment: String, english: Boolean): String {
        if (segment.isBlank()) return segment
        var s = segment
        // Ranges between numbers or measurements: "27°C–34°C", "20–22 October" → "… to …".
        s = s.replace(Regex("""(\d[°%\p{L}]*)\s*[–—]\s*(-?\d)"""), "$1 to $2")
        s = s.replace("→", " to ").replace("·", ",").replace("•", "")
        if (!english) return s
        s = s.replace(Regex("""(-?\d+(?:\.\d+)?)\s?°\s?C\b""")) { m -> "${m.groupValues[1]} ${plural(m.groupValues[1], "degree")} Celsius" }
        s = s.replace(Regex("""(-?\d+(?:\.\d+)?)\s?°\s?F\b""")) { m -> "${m.groupValues[1]} ${plural(m.groupValues[1], "degree")} Fahrenheit" }
        s = s.replace(Regex("""(-?\d+(?:\.\d+)?)\s?°""")) { m -> "${m.groupValues[1]} ${plural(m.groupValues[1], "degree")}" }
        s = s.replace(Regex("""(\d+(?:\.\d+)?)\s?%""")) { m -> "${m.groupValues[1]} percent" }
        s = s.replace(Regex("""(\d+(?:\.\d+)?)\s?km/h\b""", RegexOption.IGNORE_CASE)) { m -> "${m.groupValues[1]} kilometres per hour" }
        s = s.replace(Regex("""(\d+(?:\.\d+)?)\s?km\b""", RegexOption.IGNORE_CASE)) { m -> "${m.groupValues[1]} ${plural(m.groupValues[1], "kilometre")}" }
        s = s.replace(Regex("""(\d+(?:\.\d+)?)\s?mm\b""")) { m -> "${m.groupValues[1]} ${plural(m.groupValues[1], "millimetre")}" }
        s = s.replace(Regex("""(\d+)\s?mins?\b\.?""", RegexOption.IGNORE_CASE)) { m -> "${m.groupValues[1]} ${plural(m.groupValues[1], "minute")}" }
        s = s.replace(Regex("""(\d+)\s?hrs?\b\.?""", RegexOption.IGNORE_CASE)) { m -> "${m.groupValues[1]} ${plural(m.groupValues[1], "hour")}" }
        s = s.replace(Regex("""(\d+)\s?secs?\b\.?""", RegexOption.IGNORE_CASE)) { m -> "${m.groupValues[1]} ${plural(m.groupValues[1], "second")}" }
        s = s.replace(Regex("""₹\s?(\d[\d,]*(?:\.\d+)?)""")) { m -> "${m.groupValues[1]} rupees" }
        // Dates: "18th Oct.", "18 Oct", "Sun 18 Oct", "Oct 18".
        s = s.replace(Regex("""\b(mon|tues?|wed|thu(?:rs?)?|fri|sat|sun)\.?,?\s+(?=\d{1,2}(?:st|nd|rd|th)?\s+$MONTH\b)""", RegexOption.IGNORE_CASE)) { m ->
            "${weekdays.getValue(m.groupValues[1].lowercase(Locale.ROOT))}, the "
        }
        s = s.replace(Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+$MONTH\b\.?""", RegexOption.IGNORE_CASE)) { m ->
            val day = m.groupValues[1].toInt()
            if (day !in 1..31) m.value else "${ordinal(day)} of ${monthName(m.groupValues[2])}"
        }
        s = s.replace(Regex("""\b$MONTH\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b(?!:)""", RegexOption.IGNORE_CASE)) { m ->
            val day = m.groupValues[2].toInt()
            if (day !in 1..31) m.value else "${monthName(m.groupValues[1])} ${ordinal(day)}"
        }
        // Unambiguous abbreviations only.
        s = s.replace(Regex("""\bapprox\.(?=\s)""", RegexOption.IGNORE_CASE), "approximately")
        s = s.replace(Regex("""\be\.g\.""", RegexOption.IGNORE_CASE), "for example")
        s = s.replace(Regex("""\bi\.e\.""", RegexOption.IGNORE_CASE), "that is")
        s = s.replace(Regex("""\betc\."""), "et cetera")
        s = s.replace(Regex("""\bvs\.?(?=\s)""", RegexOption.IGNORE_CASE), "versus")
        s = s.replace(Regex("""\bNo\.\s?(?=\d)"""), "number ")
        s = s.replace(Regex("""\s&\s"""), " and ")
        return s
    }

    private fun monthName(raw: String): String = months[raw.lowercase(Locale.ROOT).take(4).let { if (it == "sept") it else it.take(3) }] ?: raw

    private fun plural(number: String, unit: String) = if (number == "1" || number == "-1") unit else unit + "s"

    /** 1 → "1st", 22 → "22nd": voices read these as "first", "twenty-second". */
    fun ordinal(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
}
