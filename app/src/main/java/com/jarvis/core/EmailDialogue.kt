package com.jarvis.core

import java.util.Locale

/**
 * A request to write an email, collected over several turns. Nothing is drafted until every essential fact is known,
 * and every fact comes from the user's own words: no dates, reasons, or recipients are invented, and no saved or
 * favourite contact is used unless the user names them.
 */
data class EmailRequest(
    val kind: Kind,
    val dates: String? = null,
    val reason: String? = null,
    /** Who the email is for, as the user said it ("my manager", "Asha Rao", "hr@company.com"); "" means "leave blank". */
    val recipient: String? = null,
    /** For a general email: what it should say. */
    val content: String? = null,
    val tone: String = "professional"
) {
    enum class Kind { LEAVE, GENERAL }
}

enum class EmailSlot { DATES, REASON, RECIPIENT, CONTENT }

sealed interface EmailStep {
    data class Ask(val slot: EmailSlot, val question: String, val request: EmailRequest) : EmailStep
    data class Ready(val request: EmailRequest) : EmailStep
    data object Cancelled : EmailStep
}

object EmailDialogue {
    private val writeVerb = Regex("""\b(write|draft|compose|prepare|create|make|send|type)\b""", RegexOption.IGNORE_CASE)
    private val emailNoun = Regex("""\b(e-?mail|mail|letter|application)\b""", RegexOption.IGNORE_CASE)
    private val leaveWords = Regex("""\b(leave|time off|day off|days off|vacation|holiday request|sick note)\b""", RegexOption.IGNORE_CASE)
    private val cancel = Regex("""^\s*(cancel|stop|never ?mind|forget (it|about it)|don'?t (write|send) (it|the email))\b""", RegexOption.IGNORE_CASE)
    private val skip = Regex("""^\s*(skip|no one|nobody|none|leave it (blank|empty)|i'?ll (add|fill|enter) (it|that|the address) (in )?later|not sure|later)\b""", RegexOption.IGNORE_CASE)

    private const val MONTH = """(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"""
    private const val WEEKDAY = """(?:mon|tues|wednes|thurs|fri|satur|sun)day"""
    /** A day that is unmistakably a date: a bare number ("2 days") is not. */
    private const val DAY = """(?:\d{1,2}(?:st|nd|rd|th)(?:\s+(?:of\s+)?$MONTH)?|\d{1,2}\s+(?:of\s+)?$MONTH|$MONTH\s+\d{1,2}(?:st|nd|rd|th)?|\d{1,2}[/.]\d{1,2}(?:[/.]\d{2,4})?|$WEEKDAY|today|tomorrow|(?:next|this|coming)\s+(?:week|$WEEKDAY))"""
    /** "from 20 October to 22 October", "20 to 22 October", "on Monday", "tomorrow", "next week". */
    private val dateExpression = Regex(
        """(?:from\s+)?(?:$DAY|\d{1,2}(?:st|nd|rd|th)?)\s*(?:to|till|until|through|-|–)\s*$DAY|(?:(?:on|for)\s+)?$DAY(?:\s+and\s+$DAY)?""",
        RegexOption.IGNORE_CASE
    )
    private val reasonExpression = Regex(
        """\b(?:because(?:\s+of)?|due\s+to|as\s+i|since\s+i|owing\s+to|the\s+reason\s+(?:is|being))\s+(.+?)(?:[.!?]|$)""",
        RegexOption.IGNORE_CASE
    )
    private val roles = listOf("manager", "boss", "supervisor", "team lead", "hr", "teacher", "professor", "principal", "class teacher", "hod", "head of department", "director", "dean", "mentor", "coordinator")
    private val recipientExpression = Regex(
        """\b(?:to|for|addressed to)\s+(?:my\s+|the\s+|our\s+)?([A-Za-z][\w.'-]*(?:\s+[A-Z][\w.'-]*){0,2}|[\w.+'-]+@[\w-]+(?:\.[\w-]+)+)""",
        RegexOption.IGNORE_CASE
    )
    /** Words after "to"/"for" that are not a person: "email to request leave", "for a leave application". */
    private val notRecipient = setOf(
        "request", "ask", "apply", "say", "tell", "inform", "let", "get", "take", "be", "a", "an", "leave", "the", "my",
        "sick", "medical", "personal", "family", "professional", "formal", "work", "office", "me", "him", "her", "them",
        "today", "tomorrow", "next", "this", "from", "on", "approval", "permission", "application", "email", "mail"
    )

    /** Recognises "write a (professional) email/leave application…"; null if this is not an email-writing request. */
    fun start(utterance: String): EmailRequest? {
        val text = utterance.trim()
        val isLeave = leaveWords.containsMatchIn(text) && Regex("""\b(application|request|letter|e-?mail|mail|apply)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val asksToWrite = writeVerb.containsMatchIn(text) && (emailNoun.containsMatchIn(text) || isLeave)
        if (!asksToWrite && !(isLeave && Regex("""^\s*(?:i\s+need\s+(?:a|to)|help\s+me)""", RegexOption.IGNORE_CASE).containsMatchIn(text))) return null
        if (Regex("""\b(unread|inbox|today'?s\s+(?:e-?mails?|mail)|summari[sz]e)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        val kind = if (isLeave) EmailRequest.Kind.LEAVE else EmailRequest.Kind.GENERAL
        val tone = Regex("""\b(professional|formal|polite|friendly|casual|short|brief)\b""", RegexOption.IGNORE_CASE).find(text)?.value?.lowercase(Locale.ROOT) ?: "professional"
        return absorb(EmailRequest(kind, tone = tone), text, asked = null)
    }

    /** The next question, or Ready once the essentials are known. */
    fun next(request: EmailRequest): EmailStep = when {
        request.kind == EmailRequest.Kind.LEAVE && request.dates == null ->
            EmailStep.Ask(EmailSlot.DATES, "Sure. What dates do you need leave for?", request)
        request.kind == EmailRequest.Kind.LEAVE && request.reason == null ->
            EmailStep.Ask(EmailSlot.REASON, "What is the reason for your leave?", request)
        request.kind == EmailRequest.Kind.GENERAL && request.content == null ->
            EmailStep.Ask(EmailSlot.CONTENT, "Sure. What should the email say? Tell me the main points.", request)
        request.recipient == null ->
            EmailStep.Ask(EmailSlot.RECIPIENT, "Who should the email be addressed to? Say a name, a role like \"my manager\", or \"skip\" to fill it in later.", request)
        else -> EmailStep.Ready(request)
    }

    /** Applies the user's answer to [asked] (plus any other facts it contains) and returns the next step. */
    fun answer(request: EmailRequest, asked: EmailSlot, answer: String): EmailStep {
        if (cancel.containsMatchIn(answer)) return EmailStep.Cancelled
        val updated = absorb(request, answer.trim(), asked)
        // An answer that gave nothing usable for the question asks again with an example, instead of guessing.
        if (updated == request) {
            val retry = when (asked) {
                EmailSlot.DATES -> "I need the leave dates, for example \"from 20 to 22 October\" or \"tomorrow\". What dates?"
                EmailSlot.REASON -> "What is the reason for your leave?"
                EmailSlot.RECIPIENT -> "Who should the email be addressed to? You can say \"skip\"."
                EmailSlot.CONTENT -> "What should the email say?"
            }
            return EmailStep.Ask(asked, retry, request)
        }
        return next(updated)
    }

    private fun absorb(request: EmailRequest, text: String, asked: EmailSlot?): EmailRequest {
        var r = request
        val clean = text.trim().trimEnd('.', '!')
        if (clean.isEmpty()) return r
        if (r.kind == EmailRequest.Kind.LEAVE && r.dates == null) {
            dateExpression.findAll(clean).map { it.value.trim() }.maxByOrNull { it.length }
                ?.takeIf { looksLikeDate(it) }?.let { r = r.copy(dates = it) }
        }
        if (r.kind == EmailRequest.Kind.LEAVE && r.reason == null) {
            val stated = reasonExpression.find(clean)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 }
            when {
                stated != null -> r = r.copy(reason = stated)
                asked == EmailSlot.REASON -> r = r.copy(reason = clean) // the answer to "what is the reason?" is the reason
            }
        }
        if (r.recipient == null) {
            when {
                asked == EmailSlot.RECIPIENT && skip.containsMatchIn(clean) -> r = r.copy(recipient = "")
                asked == EmailSlot.RECIPIENT -> r = r.copy(recipient = recipientFromAnswer(clean))
                asked == null -> recipientFromRequest(clean)?.let { r = r.copy(recipient = it) }
            }
        }
        if (r.kind == EmailRequest.Kind.GENERAL && r.content == null) {
            when {
                asked == EmailSlot.CONTENT -> r = r.copy(content = clean)
                asked == null -> Regex("""\b(?:about|regarding|saying|to\s+say|telling\s+(?:him|her|them)|that)\s+(.{6,})$""", RegexOption.IGNORE_CASE)
                    .find(clean)?.groupValues?.get(1)?.trim()?.let { r = r.copy(content = it) }
            }
        }
        return r
    }

    private fun looksLikeDate(value: String): Boolean =
        Regex("""\d|$MONTH|$WEEKDAY|today|tomorrow|week""", RegexOption.IGNORE_CASE).containsMatchIn(value) &&
            !Regex("""^\s*(?:to|for|on)\s*$""", RegexOption.IGNORE_CASE).matches(value)

    /** Only an explicit "to <someone>" in the request counts; "email for a leave application" names nobody. */
    private fun recipientFromRequest(text: String): String? {
        recipientExpression.findAll(text).forEach { m ->
            val candidate = m.groupValues[1].trim().trimEnd(',', '.')
            val first = candidate.split(Regex("\\s+")).first().lowercase(Locale.ROOT)
            if ("@" in candidate) return candidate
            if (first in notRecipient || Regex("""^\d""").containsMatchIn(first)) return@forEach
            roles.firstOrNull { candidate.lowercase(Locale.ROOT).startsWith(it) }?.let { return "my $it" }
            if (candidate.first().isUpperCase()) return candidate.split(Regex("\\s+")).takeWhile { it.first().isUpperCase() }.joinToString(" ")
        }
        return null
    }

    private fun recipientFromAnswer(answer: String): String {
        Regex("""[\w.+'-]+@[\w-]+(?:\.[\w-]+)+""").find(answer)?.let { return it.value }
        return answer.replace(Regex("""^(?:it'?s\s+|to\s+|address(?:ed)?\s+it\s+to\s+|send\s+it\s+to\s+)""", RegexOption.IGNORE_CASE), "").trim()
    }

    fun isRole(recipient: String): Boolean {
        val lower = recipient.lowercase(Locale.ROOT).removePrefix("my ").removePrefix("the ").removePrefix("our ").trim()
        return roles.any { lower == it || lower.startsWith("$it ") }
    }
}

/**
 * Writes the final email from collected facts only. Used as is when no cloud model is available, and as the check
 * against which a model's wording is accepted (the model's version must contain the user's dates).
 */
object EmailWriter {
    fun leave(request: EmailRequest, greetingName: String?, senderName: String?, signOff: String?): EmailDraft {
        val dates = request.dates.orEmpty().trim()
        val datesPhrase = when {
            Regex("""^(from|on|for)\b""", RegexOption.IGNORE_CASE).containsMatchIn(dates) -> dates
            Regex("""\b(to|till|until|through)\b|[-–]""", RegexOption.IGNORE_CASE).containsMatchIn(dates) -> "from $dates"
            Regex("""^(today|tomorrow|next|this|coming)\b""", RegexOption.IGNORE_CASE).containsMatchIn(dates) -> "for $dates"
            else -> "on $dates"
        }
        val reason = request.reason.orEmpty().trim().trimEnd('.')
        val reasonClause = when {
            Regex("""^(because|due to|as|since|owing to)\b""", RegexOption.IGNORE_CASE).containsMatchIn(reason) -> reason.replaceFirstChar { it.lowercase() }
            Regex("""^(i|i'm|i am|i've|my)\b""", RegexOption.IGNORE_CASE).containsMatchIn(reason) -> "as " + reason.replaceFirstChar { it.lowercase() }.replace(Regex("^i\\b"), "I")
            else -> "due to " + reason.replaceFirstChar { it.lowercase() }
        }
        val subject = "Leave application: ${dates.replaceFirstChar { it.uppercase() }}".take(EmailComposer.MAX_SUBJECT)
        val body = buildString {
            append(greeting(greetingName)).append("\n\n")
            append("I am writing to request leave $datesPhrase $reasonClause.\n\n")
            append("Please let me know if you need any further information or documents.\n\n")
            append("Thank you for considering my request.\n\n")
            append(closing(senderName, signOff))
        }
        return EmailDraft(subject, body.take(EmailComposer.MAX_BODY))
    }

    /** A general email written only from the user's own points (offline). */
    fun general(request: EmailRequest, greetingName: String?, senderName: String?, signOff: String?): EmailDraft {
        val content = request.content.orEmpty().trim().trimEnd('.')
        val sentence = content.replaceFirstChar { it.uppercase() } + "."
        val subject = content.split(Regex("\\s+")).take(8).joinToString(" ").replaceFirstChar { it.uppercase() }.take(EmailComposer.MAX_SUBJECT)
        val body = "${greeting(greetingName)}\n\n$sentence\n\n${closing(senderName, signOff)}"
        return EmailDraft(subject, body.take(EmailComposer.MAX_BODY))
    }

    fun greeting(name: String?): String = if (name.isNullOrBlank()) "Dear Sir/Madam," else "Dear $name,"

    private fun closing(senderName: String?, signOff: String?): String =
        listOfNotNull(signOff?.takeIf(String::isNotBlank) ?: "Sincerely,", senderName?.takeIf(String::isNotBlank)).joinToString("\n")

    /** The facts the user gave, for a cloud model to word (it is told not to add any). */
    fun factsPrompt(request: EmailRequest, greetingName: String?, senderName: String?, signOff: String?): String =
        "Write a complete ${request.tone} email. Use exactly these facts and add no others (no extra dates, reasons, " +
            "names, promises, or placeholders). " +
            (if (request.kind == EmailRequest.Kind.LEAVE) "Purpose: leave application. Leave dates: \"${request.dates}\". Reason: \"${request.reason}\". "
            else "What it must say: \"${request.content}\". ") +
            "Greeting: \"${greeting(greetingName)}\". " +
            (senderName?.let { "Sign it as $it. " } ?: "Do not invent a sender name; end with the sign-off only. ") +
            "Sign-off: \"${signOff ?: "Sincerely,"}\". Keep it concise. " +
            "Reply exactly in this format:\nSubject: <subject line>\n\n<email body>"

    /** A model draft is accepted only if it kept the user's dates and asks nothing back. */
    fun acceptable(draft: EmailDraft, request: EmailRequest): Boolean {
        val text = (draft.subject + "\n" + draft.body).lowercase(Locale.ROOT)
        if (Regex("""\[[^\]]*\]|<[^>]*>|\?\s*$""").containsMatchIn(draft.subject)) return false
        if (Regex("""\[(?:date|reason|name|your name|recipient)[^\]]*\]""", RegexOption.IGNORE_CASE).containsMatchIn(draft.body)) return false
        if (request.kind == EmailRequest.Kind.LEAVE) {
            val dateNumbers = Regex("""\d{1,2}""").findAll(request.dates.orEmpty()).map { it.value.trimStart('0') }.toList()
            if (dateNumbers.any { it !in text }) return false
        }
        return true
    }
}
