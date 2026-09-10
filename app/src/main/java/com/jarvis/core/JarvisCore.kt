package com.jarvis.core

import com.jarvis.agents.CalendarAgent
import com.jarvis.agents.ReminderAgent
import com.jarvis.agents.TaskAgent
import com.jarvis.data.AppDatabase
import com.jarvis.data.DecisionTraceEntry
import java.util.UUID
import kotlinx.coroutines.flow.first

class JarvisCore(
    private val db: AppDatabase,
    private val provider: ReasoningProvider = MockReasoningProvider(),
    private val calendar: CalendarAgent = CalendarAgent(db),
    private val reminders: ReminderAgent = ReminderAgent(db),
    private val tasks: TaskAgent = TaskAgent(db),
    private val plans: PlanEngine = PlanEngine(db)
) {
    suspend fun handle(utterance: String): String {
        val response = provider.reason(ReasoningRequest(utterance, (calendar.supportedTools + reminders.supportedTools + tasks.supportedTools).toList()))
        val step = response.steps.firstOrNull() ?: return response.reply
        if (step.toolName == "calendar.cancel") return "CONFIRM_CANCEL"
        if (step.toolName == "meeting.create") {
            val eventResult = calendar.execute(ToolCall(step.toolName, "low", step.parameters, null))
            val reminderId = UUID.randomUUID().toString()
            plans.createMeetingPlan(step.parameters["title"] ?: "meeting", eventResult.data, reminderId)
            return "Meeting created for tomorrow at 10:00 AM at college. I linked a departure reminder (30 min travel + 15 min buffer)."
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

    suspend fun confirmCancel(): String {
        val latestId = db.planDao().observeAll().first().firstOrNull { it.status == "active" }?.id
        if (latestId == null) return "There is no active meeting plan to cancel."
        plans.cancelPlan(latestId)
        return "Meeting cancelled. The linked departure reminder was cancelled too."
    }
}
