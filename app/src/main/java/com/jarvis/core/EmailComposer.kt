package com.jarvis.core

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class EmailDraft(val subject: String, val body: String)

/**
 * Builds editable email drafts. The model sees the recipient's *name* and the user's request only;
 * the address is resolved from Contacts on the device and added at handoff time. JARVIS never sends:
 * the mail app opens with the draft and the user taps Send there.
 */
object EmailComposer {
    const val MAX_SUBJECT = 120
    const val MAX_BODY = 4_000

    fun prompt(recipientName: String, request: String, tone: String?, senderName: String?, signOff: String?): String =
        "Draft a properly formatted email to $recipientName. The user's request: \"$request\". " +
            "Tone: ${tone?.takeIf(String::isNotBlank) ?: "polite and concise"}. " +
            (senderName?.let { "Sign it as $it. " } ?: "End without inventing the sender's name. ") +
            (signOff?.let { "Use the sign-off \"$it\". " } ?: "") +
            "Do not invent facts, dates, or commitments that the request does not contain. " +
            "Reply exactly in this format:\nSubject: <subject line>\n\n<email body>"

    /** Parses `Subject: …` followed by the body; anything else is rejected so the template is used. */
    fun parse(reply: String): EmailDraft? {
        val text = reply.trim().removePrefix("```").removeSuffix("```").trim()
        val match = Regex("""^\s*\**subject\**\s*:\s*(.+?)\s*\n+([\s\S]+)$""", RegexOption.IGNORE_CASE).find(text) ?: return null
        val subject = match.groupValues[1].trim().take(MAX_SUBJECT)
        val body = match.groupValues[2].trim().take(MAX_BODY)
        return if (subject.isBlank() || body.isBlank()) null else EmailDraft(subject, body)
    }

    /** Offline fallback: a plain draft built only from the user's own words. */
    fun template(recipientName: String, request: String, senderName: String?, signOff: String?): EmailDraft {
        val topic = request.trim().trimEnd('.', '!', '?')
        val subject = topic.replaceFirstChar(Char::uppercase).take(MAX_SUBJECT)
        val closing = listOfNotNull(signOff ?: "Regards,", senderName).joinToString("\n")
        val body = "Dear $recipientName,\n\nI am writing regarding the following: $topic.\n\n$closing"
        return EmailDraft(subject, body)
    }

    private val SIMPLE_ADDRESS = Regex("""^[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$""")

    /** Returns null for an address that is not a plain email, so it cannot inject extra mailto fields. */
    fun mailto(address: String, draft: EmailDraft): String? {
        val clean = address.trim()
        // An empty To field is allowed: the user adds the recipient in the mail app.
        if (clean.isNotEmpty() && !SIMPLE_ADDRESS.matches(clean)) return null
        return "mailto:$clean?subject=${encode(draft.subject)}&body=${encode(draft.body)}"
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
