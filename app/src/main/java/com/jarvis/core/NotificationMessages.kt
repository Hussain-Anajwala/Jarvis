package com.jarvis.core

import com.jarvis.data.NotificationRecord
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One message extracted from a posted notification (a MessagingStyle notification yields several). */
data class CapturedMessage(val sender: String, val text: String, val timestamp: Long)

/**
 * Rules for what the notification listener keeps. Only message-like notifications are stored, with a
 * short snippet, a masked sender when the sender is a raw phone number, and a retention limit.
 */
object NotificationCapturePolicy {
    const val RETENTION_MS = 48L * 60 * 60 * 1000
    const val MAX_RECORDS = 500
    const val SNIPPET_CHARS = 120

    private val KNOWN_MESSAGING_PACKAGES = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.google.android.apps.messaging" to "Messages",
        "com.samsung.android.messaging" to "Samsung Messages",
        "org.telegram.messenger" to "Telegram",
        "org.thoughtcrime.securesms" to "Signal",
        "com.instagram.android" to "Instagram",
        "com.facebook.orca" to "Messenger",
        "com.snapchat.android" to "Snapchat",
        "com.discord" to "Discord",
        "com.Slack" to "Slack",
        "com.microsoft.teams" to "Teams"
    )

    /** How far before a notification's post time a bundled message may be and still count as new. */
    const val FRESH_WINDOW_MS = 10L * 60 * 1000

    fun knownLabel(packageName: String): String? = KNOWN_MESSAGING_PACKAGES[packageName]

    /**
     * Messaging apps re-post a conversation with its earlier messages. Only messages that arrived after capture
     * started (access granted or data cleared) and shortly before this post are stored, so history from before
     * access was granted never enters the store.
     */
    fun isFresh(messageTime: Long, postTime: Long, captureSince: Long): Boolean =
        messageTime >= captureSince && messageTime >= postTime - FRESH_WINDOW_MS

    fun isMessageLike(
        packageName: String,
        ownPackage: String,
        category: String?,
        hasMessagingStyle: Boolean,
        isOngoing: Boolean,
        isGroupSummary: Boolean
    ): Boolean {
        if (packageName == ownPackage || isOngoing || isGroupSummary) return false
        return category == "msg" || hasMessagingStyle || packageName in KNOWN_MESSAGING_PACKAGES
    }

    /** A sender that is only a phone number is stored as "Unknown number"; numbers never reach prompts. */
    fun sanitizeSender(raw: String?): String {
        val trimmed = raw.orEmpty().trim().replace(Regex("\\s+"), " ")
        if (trimmed.isBlank()) return "Unknown sender"
        val digits = trimmed.count(Char::isDigit)
        if (digits >= 6 && trimmed.all { it.isDigit() || it in "+-() ." }) return "Unknown number"
        return trimmed.take(60)
    }

    fun snippet(text: String?): String {
        val collapsed = text.orEmpty().replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length <= SNIPPET_CHARS) collapsed else collapsed.take(SNIPPET_CHARS - 1).trimEnd() + "…"
    }

    /** Stable id so re-posted (updated) notifications do not duplicate the same message. */
    fun recordId(packageName: String, sender: String, text: String, timestamp: Long): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$packageName|$sender|$text|$timestamp".toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun toRecords(
        notificationKey: String,
        packageName: String,
        appLabel: String,
        messages: List<CapturedMessage>,
        postTime: Long = Long.MIN_VALUE,
        captureSince: Long = Long.MIN_VALUE
    ): List<NotificationRecord> = messages.mapNotNull { message ->
        if (postTime != Long.MIN_VALUE && !isFresh(message.timestamp, postTime, captureSince)) return@mapNotNull null
        val text = snippet(message.text)
        if (text.isBlank()) return@mapNotNull null
        val sender = sanitizeSender(message.sender)
        NotificationRecord(
            id = recordId(packageName, sender, text, message.timestamp),
            notificationKey = notificationKey,
            appPackage = packageName,
            appLabel = appLabel,
            sender = sender,
            textSnippet = text,
            postedAt = message.timestamp,
            read = false
        )
    }
}

data class MessageFilter(val app: String? = null, val contact: String? = null) {
    fun matches(record: NotificationRecord): Boolean {
        val appOk = app.isNullOrBlank() || record.appLabel.contains(app.trim(), ignoreCase = true) ||
            record.appPackage.contains(app.trim().replace(" ", ""), ignoreCase = true)
        val contactOk = contact.isNullOrBlank() || record.sender.contains(contact.trim(), ignoreCase = true)
        return appOk && contactOk
    }

    fun describe(): String = buildString {
        if (!contact.isNullOrBlank()) append(" from ${contact.trim()}")
        if (!app.isNullOrBlank()) append(" in ${app.trim()}")
    }

    companion object {
        fun from(step: PlanStep) = MessageFilter(step.parameters["app"], step.parameters["contact"])
    }
}

/** Builds replies for message tools from local records only; nothing here calls a model. */
object NewMessageReplies {
    const val ACCESS_OFF =
        "Notification access is off, so I can't see new messages. Turn it on in Permissions → Notification access. " +
            "Only messages that arrive while access is on can be counted."
    private const val SCOPE_NOTE = "I only see messages that arrived while notification access was on (kept for 48 hours)."
    const val LIST_LIMIT = 10

    fun count(records: List<NotificationRecord>, filter: MessageFilter): String {
        val matching = records.filter(filter::matches)
        if (matching.isEmpty()) return "No new messages${filter.describe()}. $SCOPE_NOTE"
        val byApp = matching.groupingBy { it.appLabel }.eachCount().entries
            .sortedByDescending { it.value }
            .joinToString(", ") { "${it.value} in ${it.key}" }
        val noun = if (matching.size == 1) "message" else "messages"
        return "You have ${matching.size} new $noun${filter.describe()}: $byApp. $SCOPE_NOTE"
    }

    fun list(records: List<NotificationRecord>, filter: MessageFilter, locale: Locale = Locale.getDefault()): Pair<String, List<String>> {
        val matching = records.filter(filter::matches).sortedByDescending { it.postedAt }
        if (matching.isEmpty()) return "No new messages${filter.describe()}. $SCOPE_NOTE" to emptyList()
        val shown = matching.take(LIST_LIMIT)
        val time = SimpleDateFormat("h:mm a", locale)
        val lines = shown.joinToString("\n") { "• ${it.appLabel} — ${it.sender} (${time.format(Date(it.postedAt))}): ${it.textSnippet}" }
        val more = if (matching.size > shown.size) "\n…and ${matching.size - shown.size} more." else ""
        return "New messages${filter.describe()}:\n$lines$more\nMarked as read in JARVIS." to shown.map { it.id }
    }

    /** The only place message text is prepared for a model; called after explicit user confirmation. */
    fun summaryPrompt(records: List<NotificationRecord>): String =
        "Summarize these new message notifications for the user in at most four short sentences. " +
            "Group by sender, mention anything that needs a reply, and do not invent details.\n" +
            records.joinToString("\n") { PromptPrivacy.mask("- ${it.appLabel}, ${it.sender}: ${it.textSnippet}") }
}
