package com.jarvis.core

/**
 * Hindi (Devanagari) → the Roman Hinglish the on-device parsers understand, applied before every on-device parser so
 * typed and spoken Hindi go through the same rules: "आज शाम 8:30 बजे मुझे प्रोजेक्ट याद दिलाना" →
 * "aaj shaam 8:30 baje mujhe project yaad dilana". Only listed words change; anything else (a name, a task in Hindi)
 * is kept as written. Devanagari digits become ASCII; Hindi number words become digits only before a time or duration
 * word (बजे, मिनट, घंटे), so "दो" in "दिला दो" stays "do". साढ़े / सवा / पौने give :30 / :15 / (h−1):45.
 */
object LanguageNormalizer {
    private val words = mapOf(
        "आज" to "aaj", "कल" to "kal", "परसों" to "parson", "परसो" to "parson", "सुबह" to "subah", "सवेरे" to "subah",
        "शाम" to "shaam", "रात" to "raat", "दोपहर" to "dopahar", "बजे" to "baje", "बजकर" to "baje",
        "मुझे" to "mujhe", "मेरे" to "mere", "मेरा" to "mera", "मेरी" to "meri", "याद" to "yaad", "दिलाना" to "dilana",
        "दिलाओ" to "dilao", "दिला" to "dila", "दिलाइए" to "dilaiye", "रिमाइंडर" to "reminder", "रिमाइंडर्स" to "reminders",
        "बताओ" to "batao", "बताइए" to "batao", "दिखाओ" to "dikhao", "दिखा" to "dikha", "दिखाइए" to "dikhao",
        "सारे" to "saare", "सभी" to "saare", "के" to "ke", "का" to "ka", "की" to "ki", "को" to "ko", "है" to "hai", "हैं" to "hai",
        "हटाओ" to "hatao", "हटा" to "hata", "अख़िरी" to "akhri", "आख़िरी" to "akhri", "अखिरी" to "akhri", "आखिरी" to "akhri",
        "आखरी" to "akhri", "टाइमर" to "timer", "बंद" to "band", "करो" to "karo", "कर" to "kar", "करें" to "karo",
        "कैंसल" to "cancel", "रद्द" to "cancel", "हाँ" to "haan", "हां" to "haan", "नहीं" to "nahi", "नही" to "nahi", "जी" to "ji",
        "ठीक" to "theek", "प्रोजेक्ट" to "project", "जिम" to "gym", "मिनट" to "minute", "घंटे" to "ghante", "घंटा" to "ghanta",
        "लगाओ" to "lagao", "लगा" to "laga", "रोको" to "roko", "रोक" to "rok", "दोबारा" to "dobara", "शुरू" to "shuru",
        "प्लान" to "plan", "योजना" to "plan", "जो" to "jo", "भी" to "bhi", "वाला" to "wala", "वाली" to "wali", "कब" to "kab",
        "मीटिंग" to "meeting", "बैठक" to "meeting", "अपॉइंटमेंट" to "appointment", "में" to "mein", "पर" to "par",
        "लाइब्रेरी" to "library", "कॉलेज" to "college", "ऑफिस" to "office", "दफ़्तर" to "office", "स्कूल" to "school",
        "टीम" to "team", "क्लास" to "class", "दवाई" to "dawai", "दवा" to "dawai", "लगाओ" to "lagao"
    )
    private val numbers = mapOf(
        "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पाँच" to 5, "पांच" to 5, "छह" to 6, "छः" to 6, "छे" to 6, "सात" to 7,
        "आठ" to 8, "नौ" to 9, "दस" to 10, "ग्यारह" to 11, "बारह" to 12, "पंद्रह" to 15, "बीस" to 20, "पच्चीस" to 25,
        "तीस" to 30, "चालीस" to 40, "पैंतालीस" to 45
    )
    private val fractions = setOf("साढ़े", "साढे", "सवा", "पौने")
    private val unitWords = setOf("बजे", "बजकर", "मिनट", "घंटे", "घंटा", "सेकंड")
    private val devanagari = Regex("""[ऀ-ॿ]""")

    fun hasDevanagari(text: String) = devanagari.containsMatchIn(text)

    fun normalize(text: String): String {
        if (!hasDevanagari(text)) return text
        val digits = text.map { c -> if (c in '०'..'९') '0' + (c - '०') else c }.joinToString("").replace('।', '.')
        // Tokens keep their trailing punctuation separate so "बताओ।" still matches.
        val tokens = Regex("""[ऀ-ॿ]+|[.,?!]|[^\s.,?!ऀ-ॿ]+""").findAll(digits).map { it.value }
            .filter { it.isNotBlank() }.toList()
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val after = tokens.getOrNull(i + 1)
            val n = after?.let { numbers[it] ?: it.toIntOrNull() }
            when {
                // साढ़े आठ बजे → 8:30 baje; सवा आठ → 8:15; पौने आठ → 7:45
                t in fractions && n != null && n in 1..12 -> {
                    out += when (t) { "सवा" -> "$n:15"; "पौने" -> "${if (n == 1) 12 else n - 1}:45"; else -> "$n:30" }
                    i += 2
                    continue
                }
                t in numbers && after in unitWords -> out += numbers.getValue(t).toString()
                t == "दो" -> out += "do"
                t in words -> out += words.getValue(t)
                else -> out += t
            }
            i++
        }
        // Rejoin; punctuation tokens attach to the word before them.
        return out.fold(StringBuilder()) { sb, w ->
            if (sb.isNotEmpty() && !(w.length == 1 && w[0] in ".,?!")) sb.append(' ')
            sb.append(w)
        }.toString()
    }
}
