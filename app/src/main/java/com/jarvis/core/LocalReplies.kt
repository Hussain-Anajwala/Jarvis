package com.jarvis.core

/** The language JARVIS answers in: the user's own (English, Roman-script Hinglish, or Devanagari Hindi). */
enum class ReplyLang { EN, HINGLISH, HINDI }

/**
 * Replies the phone composes itself (no model). Every function takes the reply language; stored data (titles,
 * timestamps, tool arguments) is passed through unchanged — only the surrounding sentence is in that language.
 */
object LocalReplies {
    /** The language to answer [utterance] in. */
    fun languageOf(utterance: String): ReplyLang = ReplyLang.EN

    fun nothingPending(lang: ReplyLang = ReplyLang.EN): String = "Nothing is waiting for a yes or no right now."

    fun reminderCancelled(objective: String, verified: Boolean, lang: ReplyLang = ReplyLang.EN): String =
        if (verified) "Cancelled the reminder to $objective." else "I cancelled the reminder to $objective, but couldn't confirm its alarm was removed."

    fun askReminderTitle(lang: ReplyLang = ReplyLang.EN): String = "What should I remind you about?"
}
