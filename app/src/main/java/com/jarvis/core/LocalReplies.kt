package com.jarvis.core

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** The language JARVIS answers in: the user's own (English, Roman-script Hinglish, or Devanagari Hindi). */
enum class ReplyLang { EN, HINGLISH, HINDI }

/**
 * Replies the phone composes itself (no model, no translation service). Every function takes the reply language;
 * stored data — titles, places, times, tool arguments — is passed through exactly as stored, and only the sentence
 * around it is in that language.
 */
object LocalReplies {
    /** Words that only occur in Roman-script Hindi; one is enough ("Haan", "Mere saare reminders batao"). */
    private val hinglishWords = setOf(
        "mujhe", "mujhko", "mera", "mere", "meri", "batao", "bataiye", "bata", "dikhao", "dikha", "dikhaiye", "karo", "kardo", "karna", "karein",
        "lagao", "laga", "lagado", "hatao", "hata", "yaad", "dilana", "dila", "dilao", "kal", "aaj", "parso", "parson", "subah", "shaam", "raat",
        "dopahar", "baje", "hai", "hain", "nahi", "nahin", "haan", "han", "haanji", "ji", "rok", "roko", "ruk", "ruko", "dobara", "shuru", "wala",
        "wali", "akhri", "aakhri", "saare", "mein", "kya", "kab", "kitne", "band", "chahiye", "ka", "ki", "ke", "ko", "aur", "abhi", "bhi", "jo"
    )

    /** The language to answer [utterance] in, read from the user's own words (before any normalisation). */
    fun languageOf(utterance: String): ReplyLang {
        val letters = utterance.filter { it.isLetter() }
        if (letters.isNotEmpty() && letters.count { it in 'ऀ'..'ॿ' } * 10 >= letters.length * 3) return ReplyLang.HINDI
        val words = utterance.lowercase().split(Regex("""[^a-z]+""")).filter { it.isNotBlank() }
        return if (words.any { it in hinglishWords }) ReplyLang.HINGLISH else ReplyLang.EN
    }

    /**
     * As [languageOf], but a short reply with no sign of a language ("gym", "yes", "8 PM" answering a question) keeps
     * the conversation's [previous] language instead of switching to English.
     */
    fun languageOf(utterance: String, previous: ReplyLang): ReplyLang {
        val detected = languageOf(utterance)
        val words = utterance.split(Regex("""\s+""")).count { it.any(Char::isLetterOrDigit) }
        return if (detected == ReplyLang.EN && words <= 3) previous else detected
    }

    /** "7:00 PM tomorrow" / "kal 7:00 PM" / "कल 7:00 PM": the time as digits, the day in the reply language. */
    fun whenText(at: ZonedDateTime, now: ZonedDateTime, lang: ReplyLang): String {
        if (lang == ReplyLang.EN) return ReminderTime.describe(at, now)
        val time = ClockText.time(at.hour, at.minute)
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
        val weekdays = listOf("सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार", "रविवार")
        val day = when {
            days == 0L -> if (lang == ReplyLang.HINDI) "आज" else "aaj"
            days == 1L -> if (lang == ReplyLang.HINDI) "कल" else "kal"
            days == 2L -> if (lang == ReplyLang.HINDI) "परसों" else "parso"
            days in 3L..6L -> if (lang == ReplyLang.HINDI) weekdays[at.dayOfWeek.value - 1] else at.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
            else -> "${at.dayOfMonth} ${at.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)}"
        }
        return "$day $time"
    }

    fun nothingPending(lang: ReplyLang = ReplyLang.EN): String = when (lang) {
        ReplyLang.EN -> "Nothing is waiting for a yes or no right now."
        ReplyLang.HINGLISH -> "Abhi kisi cheez ke liye haan ya na ka intezaar nahi hai."
        ReplyLang.HINDI -> "अभी किसी चीज़ के लिए हाँ या ना का इंतज़ार नहीं है।"
    }

    fun reminderCancelled(objective: String, verified: Boolean, lang: ReplyLang = ReplyLang.EN): String = when (lang) {
        ReplyLang.EN -> if (verified) "Cancelled the reminder to $objective." else "I cancelled the reminder to $objective, but couldn't confirm its alarm was removed."
        ReplyLang.HINGLISH -> "Theek hai, \"$objective\" wala reminder cancel kar diya." + if (verified) "" else " Lekin uska alarm hata ya nahi, yeh confirm nahi ho paaya."
        ReplyLang.HINDI -> "ठीक है, \"$objective\" वाला रिमाइंडर हटा दिया गया।" + if (verified) "" else " लेकिन उसका अलार्म हटा या नहीं, यह पक्का नहीं हो पाया।"
    }

    fun askReminderTitle(lang: ReplyLang = ReplyLang.EN): String = when (lang) {
        ReplyLang.EN -> "What should I remind you about?"
        ReplyLang.HINGLISH -> "Aapko kis cheez ka reminder chahiye?"
        ReplyLang.HINDI -> "किस बात का रिमाइंडर लगाऊँ?"
    }

    /** The reminder dialogue's question for [step] in the reply language (English is the dialogue's own text). */
    fun reminderQuestion(step: ReminderStep.Ask, lang: ReplyLang): String {
        if (lang == ReplyLang.EN) return step.question
        val hi = lang == ReplyLang.HINDI
        return when (step.slot) {
            ReminderSlot.TITLE -> askReminderTitle(lang)
            ReminderSlot.AMPM -> {
                val t = step.draft.time as? WhenParse.NeedsAmPm
                val h = t?.let { (it.hour % 12).let { x -> if (x == 0) 12 else x }.toString() + if (it.minute > 0) ":%02d".format(it.minute) else "" } ?: ""
                if (hi) "$h बजे सुबह या शाम?" else "$h baje subah ya shaam?"
            }
            ReminderSlot.DAY -> {
                val phrase = (step.draft.time as? WhenParse.BadDay)?.phrase ?: ""
                if (hi) "\"$phrase\" कोई तारीख़ नहीं है। कौन सा दिन?" else "\"$phrase\" koi date nahi hai. Kaun sa din?"
            }
            ReminderSlot.TIME -> if (step.question.startsWith("That time has already passed")) {
                if (hi) "वह समय निकल चुका है। कब याद दिलाऊँ?" else "Woh time nikal chuka hai. Kab yaad dilaun?"
            } else if (hi) "कब याद दिलाऊँ? जैसे \"30 मिनट बाद\" या \"कल सुबह 9 बजे\"।" else "Kab yaad dilaun? Jaise \"30 minute baad\" ya \"kal subah 9 baje\"."
        }
    }

    fun noReminderCreated(lang: ReplyLang): String = when (lang) {
        ReplyLang.EN -> "Okay, no reminder."
        ReplyLang.HINGLISH -> "Theek hai, koi reminder nahi lagaya."
        ReplyLang.HINDI -> "ठीक है, कोई रिमाइंडर नहीं लगाया।"
    }

    fun forgot(content: String, lang: ReplyLang): String = when (lang) {
        ReplyLang.EN -> "Forgot: \"$content\"."
        ReplyLang.HINGLISH -> "Theek hai, yeh memory hata di: \"$content\"."
        ReplyLang.HINDI -> "ठीक है, यह याद हटा दी गई: \"$content\"।"
    }

    fun declined(meeting: Boolean, lang: ReplyLang): String = when (lang) {
        ReplyLang.EN -> if (meeting) "Okay, I didn't add the meeting." else "Okay, I didn't delete that memory."
        ReplyLang.HINGLISH -> if (meeting) "Theek hai, meeting add nahi ki." else "Theek hai, memory delete nahi ki."
        ReplyLang.HINDI -> if (meeting) "ठीक है, मीटिंग नहीं जोड़ी।" else "ठीक है, याद नहीं हटाई।"
    }

    fun meetingTitleQuestion(lang: ReplyLang): String = when (lang) {
        ReplyLang.EN -> MeetingDetails.TITLE_QUESTION
        ReplyLang.HINGLISH -> "Meeting kis baare mein hai? Chhota sa title boliye, ya \"no title\" boliye toh naam \"Meeting\" rahega."
        ReplyLang.HINDI -> "मीटिंग किस बारे में है? छोटा सा शीर्षक बोलिए, या \"no title\" बोलिए तो नाम \"Meeting\" रहेगा।"
    }

    fun placeQuestion(lang: ReplyLang): String = when (lang) {
        ReplyLang.EN -> MeetingDetails.PLACE_QUESTION
        ReplyLang.HINGLISH -> "Meeting kahan hai? Jagah bataiye, ya online ho toh \"no location\" boliye."
        ReplyLang.HINDI -> "मीटिंग कहाँ है? जगह बताइए, या ऑनलाइन हो तो \"no location\" बोलिए।"
    }

    fun specificPlaceQuestion(place: String, lang: ReplyLang): String = when (lang) {
        ReplyLang.EN -> MeetingDetails.specificPlaceQuestion(place)
        ReplyLang.HINGLISH -> "Kaun sa $place? Uska naam ya area bataiye (jaise \"VJTI, Matunga\"), ya \"no location\" boliye."
        ReplyLang.HINDI -> "कौन सा $place? उसका नाम या इलाका बताइए (जैसे \"VJTI, Matunga\"), या \"no location\" बोलिए।"
    }
}
