package com.jarvis.core

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
    private val provider: ReasoningProvider = MockReasoningProvider(),
    private val calendar: CalendarAgent = CalendarAgent(db),
    private val reminders: ReminderAgent = ReminderAgent(db),
    private val tasks: TaskAgent = TaskAgent(db),
    private val plans: PlanEngine = PlanEngine(db),
    private val travel: TravelContextAgent = OsrmTravelContextAgent()
) {
    companion object {
        private const val TAG = "JarvisCancellation"
    }
    suspend fun handle(utterance: String): String {
        val response = provider.reason(ReasoningRequest(utterance, (calendar.supportedTools + reminders.supportedTools + tasks.supportedTools).toList()))
        val step = response.steps.firstOrNull() ?: return response.reply
        if (step.toolName == "calendar.cancel") return "CONFIRM_CANCEL"
        if (step.toolName == "communication.draft") {
            return "DRAFT_COMMUNICATION:${step.parameters["body"].orEmpty()}"
        }
        if (step.toolName == "meeting.create") {
            val eventResult = calendar.execute(ToolCall(step.toolName, "low", step.parameters, null))
            val reminderId = UUID.randomUUID().toString()
            val event = db.eventDao().findById(eventResult.data)
                ?: return "I could not create the meeting event."
            val estimate = travel.estimate(event, 30)
            plans.createMeetingPlan(step.parameters["title"] ?: "meeting", event.id, reminderId, estimate)
            return "Meeting created for tomorrow at 10:00 AM at college. Travel time: ${estimate.minutes} min (${estimate.freshness})."
        }

        val agent = when {
            step.toolName.startsWith("reminder.") -> reminders
            step.toolName.startsWith("task.") -> tasks
            else -> calendar
        }
        val result = agent.execute(ToolCall(step.toolName, "low", step.parameters, null))
        db.traceDao().insert(DecisionTraceEntry(UUID.randomUUID().toString(), null, response.reply, "low", step.toolName, result.status, System.currentTimeMillis()))
        return if (result.status == "success") response.reply else "I could not complete that action: ${result.error}"
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
                System.currentTimeMillis()
            )
        )
    }

    suspend fun confirmCancel(planId: String): String {
        Log.d(TAG, "Executing cancellation for plan=$planId")
        val plan = db.planDao().observeAll().first().firstOrNull { it.id == planId && it.status == "active" }
        if (plan == null) return "That plan is no longer active."
        plans.cancelPlan(planId)
        return "Meeting cancelled. The linked departure reminder was cancelled too."
    }
}
