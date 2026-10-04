package com.jarvis.core

data class CommunicationRequest(
    val recipientName: String,
    val body: String
)

object CommunicationRequestParser {
    private val requestPattern = Regex(
        """\b(?:message|text|email)\s+(?:to\s+)?(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val bodySeparatorPattern = Regex(
        """\s+(?:that|saying|to say)\s+|\s*:\s*""",
        RegexOption.IGNORE_CASE
    )

    fun isCommunicationIntent(text: String): Boolean =
        Regex("""\b(message|text|email|send)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)

    fun parse(text: String): CommunicationRequest? {
        val remainder = requestPattern.find(text)?.groupValues?.get(1)?.trim() ?: return null
        if (remainder.isBlank()) return null

        val separator = bodySeparatorPattern.find(remainder)
        val recipient = (separator?.let { remainder.substring(0, it.range.first) } ?: remainder)
            .trim()
            .trim(',', '.', '!', '?', '"', '\'')
            .trim()
        val body = separator?.let { remainder.substring(it.range.last + 1).trim() }.orEmpty()
            .trimEnd('.', '!', '?')

        if (recipient.isBlank()) return null
        return CommunicationRequest(recipient, body)
    }
}
