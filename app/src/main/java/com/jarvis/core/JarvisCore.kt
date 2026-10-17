package com.jarvis.core

import com.jarvis.BuildConfig
import com.jarvis.agents.CalendarAgent
import com.jarvis.agents.ReminderAgent
import com.jarvis.agents.TaskAgent
import com.jarvis.data.AppDatabase
import com.jarvis.data.DecisionTraceEntry
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.flow.first

class JarvisCore(
    private val db: AppDatabase,
    private val provider: ReasoningProvider,
    private val calendarProvider: CalendarProviderGateway? = null,
    private val calendar: CalendarAgent = CalendarAgent(db, calendarProvider),
    private val reminders: ReminderAgent = ReminderAgent(db),
    private val tasks: TaskAgent = TaskAgent(db),
    private val plans: PlanEngine = PlanEngine(db),
    private val travel: TravelContextAgent = OsrmTravelContextAgent(),
    private val personalFacts: PersonalFactsStore = InMemoryPersonalFactsStore()
) {
    companion object {
        private const val TAG = "JarvisCancellation"
        val AVAILABLE_TOOLS = listOf(
            "meeting.create",
            "calendar.cancel",
            "reminder.create",
            "task.create",
            "communication.draft",
            "set_alarm"
        ) + ToolRouter.PHONE_TOOL_NAMES + ToolRouter.FAVORITE_SONG_TOOLS
    }
    private val conversation = mutableListOf<ReasoningTurn>()
    private var lastResolvedModelName = "offline/offline-rule"
    var lastEngineLabel: String = if (
        BuildConfig.GROQ_API_KEY.isNotBlank() ||
        BuildConfig.GEMINI_API_KEY.isNotBlank() ||
        BuildConfig.GEMINI_API_KEY_2.isNotBlank()
    ) "Reasoning providers configured" else "Offline parser (no provider keys configured)"
        private set

    suspend fun handle(utterance: String): String {
        val availableTools = AVAILABLE_TOOLS
        val request = ReasoningRequest(
            utterance = utterance,
            availableTools = availableTools,
            history = conversation.takeLast(10)
        )
        val response = if (ToolRouter.usesLocalPhoneParser(utterance)) {
            OfflineRuleProvider().reason(request)
        } else {
            provider.reason(request)
        }
        lastResolvedModelName = "${response.engineName}/${response.modelName ?: "offline-rule"}"
        lastEngineLabel = when {
            response.failureReason != null -> {
                val statuses = response.attempts
                    .filter { it.providerName != "offline" }
                    .takeLast(3)
                    .joinToString("; ") { attempt ->
                        val status = attempt.httpStatus?.let { "HTTP $it" } ?: "skipped"
                        "${attempt.providerName}/${attempt.modelName} $status"
                    }
                "Offline fallback${statuses.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()}: ${response.failureReason}"
            }
            response.engineName == "offline" -> "Offline parser"
            else -> {
                val status = response.attempts.lastOrNull {
                    it.providerName == response.engineName && it.modelName == response.modelName
                }?.httpStatus?.let { "; HTTP $it" }.orEmpty()
                "${response.engineName} (${response.modelName ?: "model unavailable"}$status)"
            }
        }
        response.attempts.forEach { attempt ->
            val statusText = attempt.httpStatus?.let { "HTTP $it" } ?: "network error"
            val attemptTime = System.currentTimeMillis()
            val model = "${attempt.providerName}/${attempt.modelName}"
            db.traceDao().insert(
                DecisionTraceEntry(
                    id = UUID.randomUUID().toString(),
                    planId = null,
                    actionSummary = "$model attempt $statusText${attempt.detail?.let { ": $it" }.orEmpty()}",
                    riskLevel = "low",
                    toolName = "reasoning.request",
                    outcome = attempt.httpStatus?.let { "http_$it" }
                        ?: if (attempt.detail.orEmpty().contains("budget", ignoreCase = true)) "budget_skipped"
                        else "network_error",
                    createdAt = attemptTime,
                    modelName = model
                )
            )
        }
        conversation.add(ReasoningTurn("user", utterance))
        conversation.add(ReasoningTurn("model", response.reply))
        while (conversation.size > 20) conversation.removeAt(0)
        val step = response.steps.firstOrNull()
        val traceOutcome = response.failureReason?.let { "fallback: $it" }
            ?: if (step == null) "answered" else "planned"
        db.traceDao().insert(
            DecisionTraceEntry(
                id = UUID.randomUUID().toString(),
                planId = null,
                actionSummary = response.failureReason?.let {
                    "${response.engineName}/${response.modelName ?: "unknown"} unavailable: $it; used offline parser"
                } ?: "Reasoning response from $lastResolvedModelName",
                riskLevel = step?.let { ReasoningToolSchema.definition(it.toolName)?.riskLevel } ?: "low",
                toolName = step?.toolName ?: "reasoning",
                outcome = traceOutcome,
                createdAt = System.currentTimeMillis(),
                modelName = lastResolvedModelName
            )
        )
        if (step == null) return response.reply
        val route = ToolRouter.route(step, availableTools)
        if (route is ToolRoute.Reject) {
            val error = "I couldn't safely process that request. Please try again with the missing details."
            Log.w(TAG, "Rejected reasoning tool call ${step.toolName}: ${route.reason}")
            db.traceDao().insert(
                DecisionTraceEntry(
                    UUID.randomUUID().toString(),
                    null,
                    "Rejected invalid tool call from ${response.engineName}",
                    "low",
                    step.toolName,
                    "invalid",
                    System.currentTimeMillis(),
                    lastResolvedModelName
                )
            )
            return error
        }
        when (route) {
            ToolRoute.ConfirmCancel -> return "CONFIRM_CANCEL"
            is ToolRoute.DraftCommunication -> return "DRAFT_COMMUNICATION:${route.recipient}\u0000${route.body}"
            is ToolRoute.AlarmHandoff -> return "ALARM_HANDOFF:${route.hour}:${route.minute}\u0000${route.label}"
            is ToolRoute.PhoneTool -> return phoneToolRequest(route.step)
            is ToolRoute.FavoriteSong -> return handleFavoriteSong(route.step)
            is ToolRoute.MeetingCreate, is ToolRoute.Agent, is ToolRoute.Reject -> Unit
        }
        if (route is ToolRoute.MeetingCreate) {
            val eventResult = calendar.execute(ToolCall(step.toolName, "low", step.parameters, null))
            if (eventResult.status != "success") return "I could not create the meeting: ${eventResult.error}"
            val reminderId = UUID.randomUUID().toString()
            val event = db.eventDao().findById(eventResult.data)
                ?: return "I could not create the meeting event."
            val estimate = travel.estimate(event, 30)
            val calendarStatus = eventResult.details["calendarStatus"] ?: "local-only:calendar provider unavailable"
            plans.createMeetingPlan(
                step.parameters["title"] ?: "meeting",
                event.id,
                reminderId,
                estimate,
                calendarStatus,
                lastResolvedModelName
            )
            val calendarMessage = when {
                calendarStatus.startsWith("synced:") -> " Added to ${calendarStatus.removePrefix("synced:")} ."
                calendarStatus == "local-only:no calendar account available" -> " Saved locally only — no calendar account available."
                else -> " Saved locally only — calendar ${calendarStatus.removePrefix("local-only:")} ."
            }
            val meetingTime = java.text.SimpleDateFormat("EEE, MMM d 'at' h:mm a", java.util.Locale.getDefault())
                .format(java.util.Date(event.startTime))
            return "${event.title} created for $meetingTime at ${event.locationText}. Travel time: ${estimate.minutes} min (${estimate.freshness}).$calendarMessage"
        }

        val agent = when {
            step.toolName.startsWith("reminder.") -> reminders
            step.toolName.startsWith("task.") -> tasks
            else -> calendar
        }
        val result = agent.execute(ToolCall(step.toolName, "low", step.parameters, null))
        db.traceDao().insert(
            DecisionTraceEntry(
                UUID.randomUUID().toString(),
                null,
                "${response.reply} [engine=${response.engineName}]",
                "low",
                step.toolName,
                result.status,
                System.currentTimeMillis(),
                lastResolvedModelName
            )
        )
        return if (result.status == "success") {
            result.userMessage ?: response.reply
        } else {
            "I could not complete that action: ${result.error}"
        }
    }

    private fun phoneToolRequest(step: PlanStep): String =
        "PHONE_TOOL:" + PhoneToolRequestCodec.encode(step)

    private suspend fun handleFavoriteSong(step: PlanStep): String {
        val outcome = FavoriteSongTools.handle(step, personalFacts)
        // The song title is a personal preference; the trace records only the action.
        recordPhoneToolOutcome(
            step.toolName,
            if (outcome is FavoriteSongOutcome.PlayMusic) "resolved locally" else "answered locally"
        )
        return when (outcome) {
            is FavoriteSongOutcome.Reply -> outcome.message
            is FavoriteSongOutcome.PlayMusic -> phoneToolRequest(outcome.step)
        }
    }

    suspend fun recordCommunicationHandoff(body: String) {
        db.traceDao().insert(
            DecisionTraceEntry(
                UUID.randomUUID().toString(),
                null,
                "Drafted + handed off: $body",
                "medium",
                "communication.handoff",
                "confirmed",
                System.currentTimeMillis(),
                lastResolvedModelName
            )
        )
    }

    suspend fun recordPhoneToolOutcome(toolName: String, outcome: String) {
        val definition = ReasoningToolSchema.definition(toolName)
        db.traceDao().insert(
            DecisionTraceEntry(
                UUID.randomUUID().toString(),
                null,
                "$toolName phone action $outcome",
                definition?.riskLevel ?: "low",
                toolName,
                outcome,
                System.currentTimeMillis(),
                lastResolvedModelName
            )
        )
    }

    suspend fun recordAlarmHandoff(hour: Int, minute: Int) {
        val time = String.format("%02d:%02d", hour, minute)
        db.traceDao().insert(
            DecisionTraceEntry(
                UUID.randomUUID().toString(),
                null,
                "Handed off alarm request to Clock app for $time",
                "low",
                "alarm.handoff",
                "executed",
                System.currentTimeMillis(),
                lastResolvedModelName
            )
        )
    }

    suspend fun confirmCancel(planId: String): String {
        Log.d(TAG, "Executing cancellation for plan=$planId")
        val plan = db.planDao().observeAll().first().firstOrNull { it.id == planId && it.status == "active" }
        if (plan == null) return "That plan is no longer active."
        val eventItem = db.planItemDao().forPlan(planId).firstOrNull { it.itemType == "calendar_event" }
        val event = eventItem?.refId?.let { db.eventDao().findById(it) }
        val calendarCancellationStatus = when {
            event == null || event.androidCalendarEventId <= 0L -> "saved locally only — no calendar account available"
            calendarProvider?.deleteEvent(event.androidCalendarEventId) == true -> "Calendar Provider event removed"
            else -> "Calendar Provider event could not be removed; check calendar permission"
        }
        plans.cancelPlan(planId, calendarCancellationStatus, lastResolvedModelName)
        val calendarMessage = when {
            event == null || event.androidCalendarEventId <= 0L ->
                " The meeting was saved locally only — no calendar account available."
            calendarCancellationStatus == "Calendar Provider event removed" -> " The Calendar event was removed too."
            else -> " The Calendar event could not be removed; check calendar permission."
        }
        return "Meeting cancelled. The linked departure reminder was cancelled too.$calendarMessage"
    }
}
