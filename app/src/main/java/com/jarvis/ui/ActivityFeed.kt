package com.jarvis.ui

import com.jarvis.data.DecisionTraceEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ActivityStatus(val label: String) {
    DONE("Done"),
    PROBLEM("Didn't work"),
    NOTE("Note")
}

enum class ActivityCategory { ACTION, SYSTEM }

/** One line in the Activity tab, derived from a stored decision trace. */
data class ActivityEvent(
    val id: String,
    val title: String,
    val detail: String?,
    val status: ActivityStatus,
    val category: ActivityCategory,
    val createdAt: Long,
    /** "Why" information kept small and secondary: which model chose it and how risky the action was. */
    val meta: String,
    val planId: String?,
    val riskLevel: String
)

/**
 * Turns raw trace rows into readable, privacy-safe events.
 *
 * - Titles come from the tool, not from stored text, so content a user typed (a message body, an email subject, a
 *   memory) is never echoed here. Only tools whose summaries are written by JARVIS itself fall back to that text.
 * - Status is judged from the recorded outcome with failure words checked first; anything unrecognised is a neutral
 *   [ActivityStatus.NOTE], never "Done" — the screen must not claim success it cannot back.
 * - Background checks and model-call attempts are [ActivityCategory.SYSTEM] so they can be hidden by default.
 */
object ActivityFeed {
    private val systemTools = setOf("background.plan_evaluation", "background.manual_recheck", "reasoning.request", "reasoning")

    private val titles = mapOf(
        "meeting.create" to "Created a meeting",
        "reminder.create" to "Created a reminder",
        "task.create" to "Created a task",
        "calendar.cancel" to "Cancelled a plan",
        "reminder.cancel" to "Cancelled a reminder",
        "set_timer" to "Timer",
        "set_alarm" to "Alarm",
        "play_youtube" to "YouTube",
        "play_music" to "Music",
        "media_control" to "Media control",
        "set_volume" to "Volume",
        "flashlight" to "Flashlight",
        "call_contact" to "Phone call",
        "navigate" to "Navigation",
        "communication.draft" to "Message draft",
        "communication.handoff" to "Message draft handed off",
        "set_favorite_song" to "Favorite song",
        "play_favorite_song" to "Favorite song",
        "count_new_messages" to "Checked new messages",
        "list_new_messages" to "Listed new messages",
        "summarize_new_messages" to "Message summary",
        "email_unread_count" to "Checked unread email",
        "email_today" to "Read today's email",
        "summarize_emails" to "Email summary",
        "compose_email" to "Email draft",
        "daily_briefing" to "Daily briefing",
        "get_weather" to "Weather",
        "remember_fact" to "Saved a memory",
        "forget_memory" to "Forgot a memory",
        "list_memories" to "Listed memories",
        "travel.monitor" to "Travel time check",
        "background.plan_evaluation" to "Background check",
        "background.manual_recheck" to "Travel re-check",
        "reasoning.request" to "Asked a language model",
        "reasoning" to "Chose where to answer",
        "privacy" to "Privacy decision",
        "maintenance.reminders" to "Cleaned up reminders"
    )

    /** Only these tools have summaries that JARVIS composed itself; everything else shows the title and outcome. */
    private val safeSummaryTools = setOf("travel.monitor", "background.plan_evaluation", "background.manual_recheck", "daily_briefing")

    private val failureWords = listOf(
        "fail", "denied", "unavailable", "invalid", "not resolved", "revoked", "error", "not granted", "not started",
        "no cloud model", "no recent", "access off", "refused", "rejected", "network", "no single match", "budget", "timed out"
    )
    /** Honest "can't tell" outcomes: the action went out but JARVIS could not confirm its effect. */
    private val noteWords = listOf("not observable", "not confirmed", "cancelled by user")
    private val successWords = listOf(
        "executed", "success", "delivered", "confirmed", "updated", "saved", "deleted", "listed", "answered",
        "applied", "handed off", "assembled", "turned on", "turned off", "sent", "prepared", "drafted", "cleared"
    )

    fun status(trace: DecisionTraceEntry): ActivityStatus {
        val outcome = trace.outcome.lowercase(Locale.ROOT)
        return when {
            outcome == "http_200" -> ActivityStatus.DONE
            outcome.startsWith("http_") -> ActivityStatus.PROBLEM
            outcome.startsWith("fallback") -> ActivityStatus.NOTE
            outcome == "budget_skipped" -> ActivityStatus.NOTE
            // "not started" must beat the "started"-like success words; failure words are checked first on purpose.
            failureWords.any { it in outcome } -> ActivityStatus.PROBLEM
            noteWords.any { it in outcome } -> ActivityStatus.NOTE
            successWords.any { it in outcome } -> ActivityStatus.DONE
            else -> ActivityStatus.NOTE
        }
    }

    fun from(trace: DecisionTraceEntry): ActivityEvent {
        // "planned" / "answered" rows are the model choosing a tool; the tool's own row records what really happened.
        val isRouting = trace.outcome == "planned" || trace.outcome == "answered" || trace.outcome.startsWith("fallback")
        val category = if (trace.toolName in systemTools || isRouting) ActivityCategory.SYSTEM else ActivityCategory.ACTION
        val title = titles[trace.toolName]
            ?: trace.toolName.replace('_', ' ').replace('.', ' ').replaceFirstChar { it.uppercase() }
        val detail = when {
            trace.toolName in safeSummaryTools -> trace.actionSummary.take(MAX_DETAIL)
            trace.outcome.isBlank() -> null
            trace.outcome.startsWith("http_") || trace.outcome == "network_error" || trace.outcome == "budget_skipped" -> humanOutcome(trace.outcome)
            else -> trace.outcome.replaceFirstChar { it.uppercase() }.take(MAX_DETAIL)
        }
        val model = trace.modelName.takeIf { it.isNotBlank() && it != "system" }
        return ActivityEvent(
            id = trace.id,
            title = title,
            detail = detail,
            status = status(trace),
            category = category,
            createdAt = trace.createdAt,
            meta = listOfNotNull(model, "${trace.riskLevel} risk").joinToString(" · "),
            planId = trace.planId,
            riskLevel = trace.riskLevel
        )
    }

    fun humanOutcome(outcome: String): String = when {
        outcome == "http_200" -> "The model answered"
        outcome.startsWith("http_429") -> "The model was rate limited"
        outcome.startsWith("http_") -> "The model returned an error (${outcome.removePrefix("http_")})"
        outcome == "network_error" -> "The network request failed"
        outcome == "budget_skipped" -> "Skipped to stay within the daily request budget"
        else -> outcome
    }

    /** "Today, 5:32 pm" / "Yesterday, 9:10 am" / "Mon 12 Oct, 4:00 pm" (with the year once it is not this year). */
    fun time(millis: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val moment = Instant.ofEpochMilli(millis).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val clock = DateTimeFormatter.ofPattern("h:mm a", locale).format(moment)
        val day: LocalDate = moment.toLocalDate()
        val label = when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE d MMM" else "d MMM yyyy", locale).format(moment)
        }
        return "$label, $clock"
    }

    /** Newest first; system checks only when asked for; "problems" shows every failed event, system or not. */
    fun filter(
        events: List<ActivityEvent>,
        show: ActivityFilter,
        risk: String?,
        planId: String?
    ): List<ActivityEvent> = events.asSequence()
        .filter { event ->
            when (show) {
                ActivityFilter.ACTIONS -> event.category == ActivityCategory.ACTION
                ActivityFilter.PROBLEMS -> event.status == ActivityStatus.PROBLEM
                ActivityFilter.EVERYTHING -> true
            }
        }
        .filter { risk == null || it.riskLevel == risk }
        .filter { planId == null || it.planId == planId }
        .sortedByDescending { it.createdAt }
        .toList()

    private const val MAX_DETAIL = 160
}

enum class ActivityFilter(val label: String) {
    ACTIONS("Actions"),
    PROBLEMS("Problems"),
    EVERYTHING("Everything")
}
