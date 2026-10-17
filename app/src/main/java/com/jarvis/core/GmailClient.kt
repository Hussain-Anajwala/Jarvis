package com.jarvis.core

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"

enum class GmailError { NOT_AUTHORIZED, INSUFFICIENT_SCOPE, RATE_LIMITED, NETWORK, SERVER, MALFORMED }

sealed interface GmailResult<out T> {
    data class Success<T>(val value: T) : GmailResult<T>
    data class Failure(val error: GmailError, val detail: String) : GmailResult<Nothing>
}

/** One email for display. [senderAddress] stays on the device; prompts use [senderName] only. */
data class EmailSummary(
    val id: String,
    val senderName: String,
    val senderAddress: String?,
    val subject: String,
    val snippet: String,
    val receivedAt: Long
)

/**
 * Read-only Gmail REST client (gmail.readonly). Tokens come from [accessToken] and are never stored here.
 * Responses are untrusted: missing or malformed fields become MALFORMED failures or are skipped.
 */
class GmailClient(
    private val accessToken: suspend () -> String?,
    private val invalidateToken: suspend (String) -> Unit = {},
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://gmail.googleapis.com/gmail/v1/users/me/",
    private val sleeper: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun unreadCount(): GmailResult<Int> =
        get(url("labels/INBOX")) { body ->
            body.optInt("messagesUnread", -1).takeIf { it >= 0 }
        }

    suspend fun todaysEmails(startOfDayEpochSeconds: Long, max: Int = 10): GmailResult<List<EmailSummary>> {
        val list = get(
            url("messages").newBuilder()
                .addQueryParameter("q", "in:inbox after:$startOfDayEpochSeconds")
                .addQueryParameter("maxResults", max.coerceIn(1, 25).toString())
                .build()
        ) { body ->
            val messages = body.optJSONArray("messages") ?: return@get emptyList<String>()
            (0 until messages.length()).mapNotNull { messages.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
        }
        val ids = when (list) {
            is GmailResult.Failure -> return list
            is GmailResult.Success -> list.value
        }
        val emails = mutableListOf<EmailSummary>()
        for (id in ids) {
            if (!id.matches(Regex("[A-Za-z0-9]+"))) continue
            val detail = get(
                url("messages/$id").newBuilder()
                    .addQueryParameter("format", "metadata")
                    .addQueryParameter("metadataHeaders", "From")
                    .addQueryParameter("metadataHeaders", "Subject")
                    .build()
            ) { body -> parseMessage(id, body) }
            when (detail) {
                is GmailResult.Failure -> return detail
                is GmailResult.Success -> emails += detail.value
            }
        }
        return GmailResult.Success(emails)
    }

    private fun url(path: String): HttpUrl = (baseUrl.trimEnd('/') + "/" + path).toHttpUrl()

    private suspend fun <T> get(url: HttpUrl, parse: (JSONObject) -> T?): GmailResult<T> {
        var retried = false
        while (true) {
            val token = accessToken() ?: return GmailResult.Failure(GmailError.NOT_AUTHORIZED, "Gmail access is not authorized.")
            val response = try {
                withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").get().build())
                        .execute().use { it.code to it.body?.string().orEmpty() }
                }
            } catch (_: IOException) {
                if (!retried) { retried = true; sleeper(1_000); continue }
                return GmailResult.Failure(GmailError.NETWORK, "Gmail could not be reached.")
            }
            val (status, body) = response
            when {
                status in 200..299 -> {
                    val parsed = try { parse(JSONObject(body)) } catch (_: JSONException) { null }
                    return parsed?.let { GmailResult.Success(it) }
                        ?: GmailResult.Failure(GmailError.MALFORMED, "Gmail returned an unexpected response.")
                }
                status == 401 -> {
                    invalidateToken(token)
                    return GmailResult.Failure(GmailError.NOT_AUTHORIZED, "Gmail authorization expired. Connect Gmail again.")
                }
                status == 403 -> return if (body.contains("insufficient", ignoreCase = true) || body.contains("scope", ignoreCase = true)) {
                    GmailResult.Failure(GmailError.INSUFFICIENT_SCOPE, "Gmail read access was not granted. Connect Gmail again and allow reading.")
                } else if (body.contains("rateLimit", ignoreCase = true) || body.contains("quota", ignoreCase = true)) {
                    GmailResult.Failure(GmailError.RATE_LIMITED, "Gmail quota was reached. Try again later.")
                } else {
                    GmailResult.Failure(GmailError.INSUFFICIENT_SCOPE, "Gmail refused access (HTTP 403). Check that the Gmail API and read access are enabled.")
                }
                status == 429 || status >= 500 -> {
                    if (!retried) { retried = true; sleeper(1_500); continue }
                    return GmailResult.Failure(
                        if (status == 429) GmailError.RATE_LIMITED else GmailError.SERVER,
                        "Gmail is unavailable right now (HTTP $status)."
                    )
                }
                else -> return GmailResult.Failure(GmailError.SERVER, "Gmail request failed (HTTP $status).")
            }
        }
    }

    companion object {
        fun parseMessage(id: String, body: JSONObject): EmailSummary? {
            val payload = body.optJSONObject("payload") ?: return null
            val headers = payload.optJSONArray("headers") ?: return null
            var from = ""
            var subject = ""
            for (i in 0 until headers.length()) {
                val header = headers.optJSONObject(i) ?: continue
                when (header.optString("name").lowercase()) {
                    "from" -> from = header.optString("value")
                    "subject" -> subject = header.optString("value")
                }
            }
            val (name, address) = splitSender(from)
            return EmailSummary(
                id = id,
                senderName = name,
                senderAddress = address,
                subject = subject.trim().take(150).ifBlank { "(no subject)" },
                snippet = NotificationCapturePolicy.snippet(decodeEntities(body.optString("snippet"))),
                receivedAt = body.optString("internalDate").toLongOrNull() ?: 0L
            )
        }

        /** `"Asha Rao" <asha@example.com>` → ("Asha Rao", "asha@example.com"); a bare address keeps no name. */
        fun splitSender(raw: String): Pair<String, String?> {
            val match = Regex("""^\s*"?([^"<]*?)"?\s*<([^>]+)>\s*$""").find(raw)
            if (match != null) {
                val name = match.groupValues[1].trim()
                return (name.takeUnless { it.isBlank() || it.contains("@") } ?: "Unknown sender") to match.groupValues[2].trim()
            }
            val bare = raw.trim()
            return if (bare.contains("@")) "Unknown sender" to bare else (bare.ifBlank { "Unknown sender" } to null)
        }

        private fun decodeEntities(text: String): String =
            text.replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    }
}

/** Removes contact identifiers from text that is about to be sent to a model, even after user consent. */
object PromptPrivacy {
    private val EMAIL = Regex("""[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val PHONE = Regex("""\+?\d[\d\s().-]{6,}\d""")

    fun mask(text: String): String =
        EMAIL.replace(text, "[email]").let { PHONE.replace(it) { m -> if (m.value.count(Char::isDigit) >= 7) "[number]" else m.value } }
}

object EmailReplies {
    fun unread(count: Int) = when (count) {
        0 -> "You have no unread emails in your Gmail inbox."
        1 -> "You have 1 unread email in your Gmail inbox."
        else -> "You have $count unread emails in your Gmail inbox."
    }

    fun today(emails: List<EmailSummary>, locale: Locale = Locale.getDefault()): String {
        if (emails.isEmpty()) return "No emails have arrived in your inbox today."
        val time = SimpleDateFormat("h:mm a", locale)
        return "Today's emails:\n" + emails.joinToString("\n") {
            "• ${it.senderName} (${time.format(Date(it.receivedAt))}): ${it.subject} — ${it.snippet}"
        }
    }

    /** Sent to a model only after explicit confirmation; contains names, subjects, and snippets, never addresses. */
    fun summaryPrompt(emails: List<EmailSummary>): String =
        "Summarize these emails from today in at most four short sentences. Mention anything that needs action " +
            "and do not invent details.\n" + emails.joinToString("\n") { PromptPrivacy.mask("- ${it.senderName}: ${it.subject} — ${it.snippet}") }
}
