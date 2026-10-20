package com.jarvis.core

import android.content.Context

/** Kinds of data a request can carry; used for decisions and audit records (never the data itself). */
enum class DataCategory { GENERAL, PREFERENCE, CONTACT_CALENDAR, PRIVATE_CONTENT, SECRET }

/** Why a request is going to a model; private content may leave the phone only for an explicit, confirmed purpose. */
enum class RequestPurpose {
    /** An ordinary assistant request (tools may be proposed). */
    ASSISTANT,
    /** Summarising message notifications, after the user confirmed the disclosure dialog. */
    MESSAGE_SUMMARY,
    /** Summarising today's emails, after the user confirmed the disclosure dialog. */
    EMAIL_SUMMARY,
    /** Wording an email from facts the user dictated. */
    EMAIL_DRAFT
}

/** The user's privacy choices. */
data class PrivacySettings(
    /** Off: every request is answered on the phone (offline parser and local handlers); nothing goes to a model. */
    val cloudAllowed: Boolean = true
)

/** What the policy decided for one request. [audit] describes the decision without any protected value. */
sealed interface PrivacyDecision {
    val categories: Set<DataCategory>
    val audit: String

    /** Send [request] (possibly minimised or redacted) to the cloud. */
    data class Allow(val request: ReasoningRequest, override val categories: Set<DataCategory>, override val audit: String) : PrivacyDecision

    /** Nothing may be sent. [userMessage] tells the user why, in plain words. */
    data class LocalOnly(override val categories: Set<DataCategory>, override val audit: String, val userMessage: String) : PrivacyDecision
}

/**
 * The one place that decides what may reach a cloud reasoning provider. Deterministic rules, independent of any model:
 *  1. the user turned cloud AI off → local only;
 *  2. message or email content needs the user's confirmation for that purpose → otherwise local only;
 *  3. a secret in the user's own request → local only (it is not "fixed" by redaction: the request is about it);
 *  4. secrets in message/email content or a dictated email → only the secret values are redacted;
 *  5. history turns and saved memories that contain a secret are dropped (minimisation);
 *  6. a final check: if anything outbound still looks secret, nothing is sent (fail closed).
 * Phone numbers and email addresses in message/email content are masked by [PromptPrivacy] before this point.
 */
object PrivacyPolicy {
    private val contactOrCalendar = Regex(
        """\b(call|text|message|whatsapp|email|e-mail|meeting|calendar|appointment|contact|remind|invite)\b""",
        RegexOption.IGNORE_CASE
    )

    fun evaluate(request: ReasoningRequest, settings: PrivacySettings): PrivacyDecision {
        val categories = categorize(request)
        if (!settings.cloudAllowed) {
            return PrivacyDecision.LocalOnly(categories, "local only: cloud AI is turned off in Settings",
                "Cloud AI is off in Settings, so this was handled on your phone only.")
        }
        val private = request.purpose == RequestPurpose.MESSAGE_SUMMARY || request.purpose == RequestPurpose.EMAIL_SUMMARY
        if (private && !request.userConsented) {
            return PrivacyDecision.LocalOnly(categories, "local only: no confirmation to send ${what(request.purpose)}",
                "I need your confirmation before sending ${what(request.purpose)} to a cloud model, so nothing was sent.")
        }
        val notes = mutableListOf<String>()
        var outbound = request
        val utteranceSecret = SensitiveData.findings(request.utterance)
        if (utteranceSecret.isNotEmpty()) {
            if (request.purpose == RequestPurpose.ASSISTANT) {
                return PrivacyDecision.LocalOnly(categories, "local only: request contains a ${utteranceSecret.sorted().joinToString()} (value not recorded)",
                    "That looked like a password, PIN, code, card or ID number, so it stayed on your phone.")
            }
            val (redacted, count) = SensitiveData.redact(request.utterance)
            outbound = outbound.copy(utterance = redacted)
            notes += "redacted $count secret value(s) (${utteranceSecret.sorted().joinToString()})"
        }
        val history = outbound.history.filterNot { SensitiveData.isSecret(it.text) }
        if (history.size < outbound.history.size) notes += "dropped ${outbound.history.size - history.size} history turn(s) with a secret"
        val memories = outbound.memoryContext.filterNot(SensitiveData::isSecret)
        if (memories.size < outbound.memoryContext.size) notes += "dropped ${outbound.memoryContext.size - memories.size} memory item(s) with a secret"
        outbound = outbound.copy(history = history, memoryContext = memories)
        if (leaks(outbound)) {
            return PrivacyDecision.LocalOnly(categories, "local only: a secret remained after redaction (value not recorded)",
                "Part of this looked like a password or code that I couldn't safely remove, so nothing was sent.")
        }
        val audit = if (notes.isEmpty()) "allowed: nothing to remove" else "allowed after minimising: " + notes.joinToString("; ")
        return PrivacyDecision.Allow(outbound, categories, audit)
    }

    /** True if any outbound text still contains a secret value. */
    fun leaks(request: ReasoningRequest): Boolean =
        SensitiveData.isSecret(request.utterance) || request.history.any { SensitiveData.isSecret(it.text) } ||
            request.memoryContext.any(SensitiveData::isSecret)

    /** Rough categories for the audit record (keywords and purpose, not a classifier). */
    fun categorize(request: ReasoningRequest): Set<DataCategory> = buildSet {
        if (SensitiveData.isSecret(request.utterance) || request.history.any { SensitiveData.isSecret(it.text) } ||
            request.memoryContext.any(SensitiveData::isSecret)) add(DataCategory.SECRET)
        if (request.purpose == RequestPurpose.MESSAGE_SUMMARY || request.purpose == RequestPurpose.EMAIL_SUMMARY) add(DataCategory.PRIVATE_CONTENT)
        if (contactOrCalendar.containsMatchIn(request.utterance)) add(DataCategory.CONTACT_CALENDAR)
        if (request.memoryContext.isNotEmpty()) add(DataCategory.PREFERENCE)
        if (isEmpty()) add(DataCategory.GENERAL)
    }

    private fun what(purpose: RequestPurpose) = when (purpose) {
        RequestPurpose.MESSAGE_SUMMARY -> "message text"
        RequestPurpose.EMAIL_SUMMARY -> "email text"
        else -> "this"
    }
}

/**
 * Wraps the whole cloud provider chain (Groq, its retries, Gemini, and their fallbacks): the policy runs once, before
 * anything is sent, and the chain only ever sees the approved request. A blocked request is answered by the offline
 * parser and never reaches a provider, so a provider failure or fallback cannot bypass the policy.
 */
class PrivacyGuardProvider(
    private val cloud: ReasoningProvider,
    private val settings: () -> PrivacySettings = { PrivacySettings() },
    /** Receives each decision's audit text (no protected values). */
    private val audit: suspend (PrivacyDecision) -> Unit = {}
) : ReasoningProvider {
    override suspend fun reason(request: ReasoningRequest): ReasoningResponse {
        val decision = PrivacyPolicy.evaluate(request, settings())
        audit(decision)
        return when (decision) {
            is PrivacyDecision.Allow -> cloud.reason(decision.request)
            is PrivacyDecision.LocalOnly -> {
                val local = if (request.purpose == RequestPurpose.ASSISTANT) OfflineRuleProvider().reason(request) else ReasoningResponse("")
                local.copy(engineName = "offline", modelName = PRIVACY_LOCAL, privacyNote = decision.userMessage)
            }
        }
    }

    companion object {
        const val PRIVACY_LOCAL = "privacy-local"
    }
}

/** The user's privacy choices, kept on the phone. */
class PrivacyPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("privacy", Context.MODE_PRIVATE)
    var cloudAllowed: Boolean
        get() = prefs.getBoolean("cloud_allowed", true)
        set(value) { prefs.edit().putBoolean("cloud_allowed", value).apply() }
    fun settings() = PrivacySettings(cloudAllowed = cloudAllowed)
}
