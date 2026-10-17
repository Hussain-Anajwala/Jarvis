package com.jarvis.core

sealed interface PhoneCommandParseResult {
    data object NotPhoneCommand : PhoneCommandParseResult
    data class Parsed(val step: PlanStep) : PhoneCommandParseResult
    data class Clarification(val message: String) : PhoneCommandParseResult
}

object PhoneCommandParser {
    /** Cancellation phrasing must never become a phone action (e.g. "call off my meeting"). */
    val cancellationPattern = Regex("""\b(cancel|delete|remove|call\s+off)\b""", RegexOption.IGNORE_CASE)
    private val favoriteSongWord = """favou?rite\s+(?:song|track)"""
    private val setFavoriteSong = Regex(
        """^\s*(?:please\s+)?(?:remember\s+(?:that\s+)?)?(?:my\s+$favoriteSongWord\s+is|set\s+my\s+$favoriteSongWord\s+(?:to|as))\s+(.+?)\s*[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val playFavoriteSong = Regex("""^\s*(?:please\s+)?play\s+my\s+$favoriteSongWord\s*[.!?]*$""", RegexOption.IGNORE_CASE)
    private val getFavoriteSong = Regex("""^\s*what(?:'s|\s+is)\s+my\s+$favoriteSongWord\s*[.!?]*$""", RegexOption.IGNORE_CASE)
    private val clearFavoriteSong = Regex("""^\s*(?:please\s+)?(?:forget|clear)\s+my\s+$favoriteSongWord\s*[.!?]*$""", RegexOption.IGNORE_CASE)
    private val timerPrefix = Regex("""^\s*(?:please\s+)?(?:set|start)\s+(?:a\s+)?timer\b""", RegexOption.IGNORE_CASE)
    private val duration = Regex("""\b(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\b""", RegexOption.IGNORE_CASE)
    private val volumePrefix = Regex("""^\s*(?:please\s+)?(?:set|turn)\s+(?:the\s+)?volume\b""", RegexOption.IGNORE_CASE)

    fun parse(text: String): PhoneCommandParseResult {
        val command = text.trim()
        if (command.isBlank()) return PhoneCommandParseResult.NotPhoneCommand
        if (cancellationPattern.containsMatchIn(command)) return PhoneCommandParseResult.NotPhoneCommand
        if (playFavoriteSong.matches(command)) return parsed("play_favorite_song")
        if (getFavoriteSong.matches(command)) return parsed("get_favorite_song")
        if (clearFavoriteSong.matches(command)) return parsed("clear_favorite_song")
        parseMessageQuery(command)?.let { return it }
        parseEmailQuery(command)?.let { return it }
        setFavoriteSong.matchEntire(command)?.let { match ->
            return queryResult("set_favorite_song", match.groupValues[1].trim('"', '“', '”', ' '), "What is your favorite song?")
        }
        parseMemoryCommand(command)?.let { return it }
        if (weatherQuery.matches(command)) return parsed("get_weather")
        if (briefingQuery.matches(command)) return parsed("daily_briefing")
        AlarmRequestParser.parse(command)?.let { alarm ->
            return parsed(
                "set_alarm",
                "hour" to alarm.hour.toString(),
                "minute" to alarm.minute.toString(),
                "label" to alarm.label
            )
        }
        if (AlarmRequestParser.isAlarmIntent(command)) {
            return PhoneCommandParseResult.Clarification("What time should I set the alarm for? Please include AM or PM.")
        }

        if (timerPrefix.containsMatchIn(command)) {
            val match = duration.find(command)
                ?: return PhoneCommandParseResult.Clarification("How long should I set the timer for?")
            val amount = match.groupValues[1].toLongOrNull()
                ?: return PhoneCommandParseResult.Clarification("Please give the timer duration as a positive number.")
            val unit = match.groupValues[2].lowercase()
            val multiplier = when {
                unit.startsWith("sec") -> 1L
                unit.startsWith("min") -> 60L
                else -> 3_600L
            }
            val seconds = amount * multiplier
            if (seconds !in 1..86_400) {
                return PhoneCommandParseResult.Clarification("Please choose a timer duration between 1 second and 24 hours.")
            }
            return parsed("set_timer", "duration_seconds" to seconds.toString())
        }

        val youtube = Regex(
            """^\s*(?:play|watch|search\s+youtube\s+for)\s+(.+?)(?:\s+on\s+youtube)?\s*[.!?]*$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(command)
        if (youtube != null && (command.contains("youtube", ignoreCase = true) ||
                command.startsWith("watch ", ignoreCase = true))) {
            if (youtube.groupValues[1].equals("youtube", ignoreCase = true)) {
                return PhoneCommandParseResult.Clarification("What would you like me to search for on YouTube?")
            }
            return queryResult("play_youtube", youtube.groupValues[1], "What would you like me to search for on YouTube?")
        }
        if (Regex("""^\s*(?:play|watch|search)\s+(?:on\s+)?youtube\s*[.!?]*$""", RegexOption.IGNORE_CASE)
                .matches(command)
        ) {
            return PhoneCommandParseResult.Clarification("What would you like me to search for on YouTube?")
        }

        val music = Regex("""^\s*play\s+(?:music|a\s+song)(?:\s+(.+))?\s*[.!?]*$""", RegexOption.IGNORE_CASE)
            .matchEntire(command)
        if (music != null) {
            return queryResult("play_music", music.groupValues[1], "What music should I search for?")
        }

        val mediaActions = listOf(
            Regex("""^\s*(?:pause|pause\s+music)\s*[.!?]*$""", RegexOption.IGNORE_CASE) to "pause",
            Regex("""^\s*(?:resume|continue|play)\s+(?:music|playback)\s*[.!?]*$""", RegexOption.IGNORE_CASE) to "play",
            Regex("""^\s*(?:next|skip)(?:\s+(?:song|track))?\s*[.!?]*$""", RegexOption.IGNORE_CASE) to "next",
            Regex("""^\s*(?:previous|back)(?:\s+(?:song|track))?\s*[.!?]*$""", RegexOption.IGNORE_CASE) to "previous"
        )
        mediaActions.firstOrNull { it.first.matches(command) }?.let {
            return parsed("media_control", "action" to it.second)
        }

        if (volumePrefix.containsMatchIn(command)) {
            val level = Regex("""\b(\d{1,3})\s*%?\b""").find(command)?.groupValues?.get(1)
                ?: return PhoneCommandParseResult.Clarification("What volume level should I set, from 0 to 100?")
            if (level.toIntOrNull() !in 0..100) {
                return PhoneCommandParseResult.Clarification("Choose a volume level from 0 to 100.")
            }
            return parsed("set_volume", "level" to level)
        }

        if (Regex(
                """^\s*(?:(?:turn|switch)\s+(?:(?:the\s+)?(?:flashlight|torch)|(?:on|off)\s+(?:the\s+)?flashlight)|(?:the\s+)?flashlight)\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(command)
        ) {
            val enabled = when {
                Regex("""\b(on|enable|turn on)\b""", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "true"
                Regex("""\b(off|disable|turn off)\b""", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "false"
                else -> return PhoneCommandParseResult.Clarification("Should I turn the flashlight on or off?")
            }
            return parsed("flashlight", "enabled" to enabled)
        }

        val call = Regex("""^\s*(?:please\s+)?call\s+(.+?)\s*[.!?]*$""", RegexOption.IGNORE_CASE)
            .matchEntire(command)
        if (call != null) {
            return queryResult("call_contact", call.groupValues[1], "Who should I call?")
        }
        if (Regex("""^\s*(?:please\s+)?call\s*[.!?]*$""", RegexOption.IGNORE_CASE).matches(command)) {
            return PhoneCommandParseResult.Clarification("Who should I call?")
        }

        if (Regex("""^\s*(?:navigate|directions|take\s+me)(?:\s+to)?\s*[.!?]*$""", RegexOption.IGNORE_CASE)
                .matches(command)
        ) {
            return PhoneCommandParseResult.Clarification("Where would you like to navigate?")
        }
        val navigate = Regex(
            """^\s*(?:please\s+)?(?:navigate|directions|take\s+me)\s+(?:to\s+)?(.+?)\s*[.!?]*$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(command)
        if (navigate != null) {
            return queryResult("navigate", navigate.groupValues[1], "Where would you like to navigate?")
        }

        return PhoneCommandParseResult.NotPhoneCommand
    }

    private val messageApps = mapOf(
        "whatsapp" to "WhatsApp", "telegram" to "Telegram", "instagram" to "Instagram",
        "signal" to "Signal", "sms" to "Messages", "text" to "Messages"
    )
    private const val appWords = """(?:(whatsapp|telegram|instagram|signal|sms|text)\s+)?"""
    private const val fromPart = """(?:\s+(?:from|on|in)\s+(.+?))?"""
    private val countMessages = listOf(
        Regex("""^\s*(?:are\s+there\s+|do\s+i\s+have\s+|have\s+i\s+got\s+|did\s+i\s+get\s+|got\s+)?any\s+(?:new\s+|unread\s+)?${appWords}messages?$fromPart\s*[?.!]*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*how\s+many\s+(?:new\s+|unread\s+)?${appWords}messages?(?:\s+do\s+i\s+have)?$fromPart\s*[?.!]*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:koi|kya\s+koi)\s+(?:naya|new)\s+${appWords}(?:message|msg)\s*(?:aaya|aya)?(?:\s+hai)?$fromPart\s*[?.!]*$""", RegexOption.IGNORE_CASE)
    )
    private val listMessages = Regex(
        """^\s*(?:show|list|read|tell)\s+(?:me\s+)?(?:my\s+)?(?:new\s+|unread\s+|latest\s+)?${appWords}messages?$fromPart\s*[?.!]*$""",
        RegexOption.IGNORE_CASE
    )
    private val summarizeMessages = Regex(
        """^\s*summari[sz]e\s+(?:my\s+)?(?:new\s+|unread\s+|latest\s+)?${appWords}messages?$fromPart\s*[?.!]*$""",
        RegexOption.IGNORE_CASE
    )

    private val unreadEmails = listOf(
        Regex("""^\s*(?:how\s+many|any)\s+(?:new\s+|unread\s+)?(?:e-?mails?|mails?)(?:\s+do\s+i\s+have)?(?:\s+in\s+(?:my\s+)?(?:inbox|gmail))?\s*[?.!]*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:check|open)\s+my\s+(?:e-?mails?|mails?|inbox|gmail)\s*[?.!]*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*do\s+i\s+have\s+(?:any\s+)?(?:new\s+|unread\s+)?(?:e-?mails?|mails?)\s*[?.!]*$""", RegexOption.IGNORE_CASE)
    )
    private val todayEmails = listOf(
        Regex("""^\s*(?:show|list|read|tell)\s+(?:me\s+)?(?:my\s+)?(?:today'?s\s+|new\s+|latest\s+)?(?:e-?mails?|mails?)(?:\s+(?:from\s+)?today)?\s*[?.!]*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*what\s+(?:e-?mails?|mails?)\s+(?:did\s+i\s+get|came\s+in|have\s+i\s+got)\s+today\s*[?.!]*$""", RegexOption.IGNORE_CASE)
    )
    private val summarizeEmails =
        Regex("""^\s*summari[sz]e\s+(?:my\s+)?(?:today'?s\s+|new\s+|unread\s+)?(?:e-?mails?|mails?|inbox)(?:\s+(?:from\s+)?today)?\s*[?.!]*$""", RegexOption.IGNORE_CASE)
    private val composeEmail = listOf(
        Regex("""^\s*(?:please\s+)?(?:write|draft|compose|send)\s+(?:an?\s+)?(?:e-?mail|mail)\s+to\s+(.+?)\s+((?:about|asking|saying|regarding|that|to|for)\s+.+?)\s*[.!]*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:please\s+)?e-?mail\s+(.+?)\s+((?:about|asking|saying|regarding|that|to|for)\s+.+?)\s*[.!]*$""", RegexOption.IGNORE_CASE)
    )

    private val weatherQuery = Regex(
        """^\s*(?:what'?s|what\s+is|how'?s|how\s+is|tell\s+me)?\s*(?:the\s+)?weather(?:\s+(?:like|today|now|outside|forecast))*\s*[?.!]*$|^\s*(?:will|is)\s+it\s+(?:going\s+to\s+)?rain(?:\s+today)?\s*[?.!]*$|^\s*(?:aaj\s+)?mausam\s+(?:kaisa\s+hai|kya\s+hai)\s*[?.!]*$""",
        RegexOption.IGNORE_CASE
    )
    private val briefingQuery = Regex(
        """^\s*(?:good\s+morning(?:\s+jarvis)?|(?:give\s+me\s+|read\s+|start\s+)?(?:my\s+|the\s+)?(?:daily\s+|morning\s+)?briefing|brief\s+me|what'?s\s+(?:on\s+)?my\s+day(?:\s+look\s+like)?|how\s+does\s+my\s+day\s+look)\s*[?.!]*$""",
        RegexOption.IGNORE_CASE
    )
    private val rememberCommand = Regex("""^\s*(?:please\s+)?(?:remember|note|keep\s+in\s+mind)\s+(?:that\s+)?(.+?)\s*[.!]*$""", RegexOption.IGNORE_CASE)
    private val nameStatement = Regex("""^\s*(?:my\s+name\s+is|call\s+me)\s+.+$""", RegexOption.IGNORE_CASE)
    private val forgetCommand = Regex("""^\s*(?:please\s+)?forget\s+(?:that\s+|about\s+)?(.+?)\s*[.!]*$""", RegexOption.IGNORE_CASE)
    private val listMemories = listOf(
        Regex("""^\s*what\s+do\s+you\s+(?:remember|know)(?:\s+about\s+(.+?))?\s*[?.!]*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:show|list)\s+(?:me\s+)?(?:my\s+|your\s+)?memor(?:y|ies)()\s*[?.!]*$""", RegexOption.IGNORE_CASE)
    )

    private fun parseMemoryCommand(command: String): PhoneCommandParseResult? {
        listMemories.firstNotNullOfOrNull { it.matchEntire(command) }?.let { match ->
            val about = match.groupValues.getOrNull(1)?.trim()?.takeUnless { it.isBlank() || it.equals("me", true) }
            return parsed("list_memories", *listOfNotNull(about?.let { "query" to it }).toTypedArray())
        }
        if (nameStatement.matches(command)) return parsed("remember_fact", "text" to command.trim())
        rememberCommand.matchEntire(command)?.let { match ->
            val text = match.groupValues[1].trim()
            // "remember to buy milk" is a reminder, not a memory.
            if (text.startsWith("to ", ignoreCase = true)) return null
            return queryResult("remember_fact", text, "What should I remember?")
        }
        forgetCommand.matchEntire(command)?.let { match ->
            return queryResult("forget_memory", match.groupValues[1], "What should I forget?")
        }
        return null
    }

    private fun parseEmailQuery(command: String): PhoneCommandParseResult? {
        if (unreadEmails.any { it.matches(command) }) return PhoneCommandParseResult.Parsed(PlanStep("email_unread_count", emptyMap()))
        if (todayEmails.any { it.matches(command) }) return PhoneCommandParseResult.Parsed(PlanStep("email_today", emptyMap()))
        if (summarizeEmails.matches(command)) return PhoneCommandParseResult.Parsed(PlanStep("summarize_emails", emptyMap()))
        val compose = composeEmail.firstNotNullOfOrNull { it.matchEntire(command) } ?: return null
        val recipient = compose.groupValues[1].trim()
        if (recipient.lowercase() in VAGUE_VALUES) return PhoneCommandParseResult.Clarification("Who should the email go to?")
        return PhoneCommandParseResult.Parsed(
            PlanStep("compose_email", mapOf("recipient_name" to recipient, "request" to compose.groupValues[2].trim()))
        )
    }

    private fun parseMessageQuery(command: String): PhoneCommandParseResult? {
        val (tool, match) = countMessages.firstNotNullOfOrNull { it.matchEntire(command) }?.let { "count_new_messages" to it }
            ?: listMessages.matchEntire(command)?.let { "list_new_messages" to it }
            ?: summarizeMessages.matchEntire(command)?.let { "summarize_new_messages" to it }
            ?: return null
        var app = match.groupValues[1].lowercase().takeIf(String::isNotBlank)?.let(messageApps::get)
        var contact = match.groupValues[2].trim().takeIf(String::isNotBlank)
        contact?.lowercase()?.let(messageApps::get)?.let { app = it; contact = null }
        val values = buildMap {
            app?.let { put("app", it) }
            contact?.let { put("contact", it) }
        }
        return PhoneCommandParseResult.Parsed(PlanStep(tool, values))
    }

    /** Placeholders that are not a real query, name, or place ("play something", "take me there"). */
    private val VAGUE_VALUES = setOf("something", "anything", "it", "there", "here", "that", "this", "someone", "somebody", "some music", "a video", "a song")

    private fun queryResult(tool: String, value: String, clarification: String): PhoneCommandParseResult {
        val cleanValue = value.trim()
        if (cleanValue.isBlank() || cleanValue.lowercase() in VAGUE_VALUES) return PhoneCommandParseResult.Clarification(clarification)
        val parameter = when (tool) {
            "call_contact" -> "name"
            "navigate" -> "destination"
            "set_favorite_song" -> "song"
            "remember_fact" -> "text"
            "forget_memory" -> "query"
            else -> "query"
        }
        return parsed(tool, parameter to cleanValue)
    }

    private fun parsed(tool: String, vararg values: Pair<String, String>) =
        PhoneCommandParseResult.Parsed(PlanStep(tool, values.toMap()))
}
