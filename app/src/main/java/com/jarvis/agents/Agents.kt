package com.jarvis.agents

import com.jarvis.core.JarvisAgent
import com.jarvis.core.SafeStorage
import com.jarvis.core.ToolCall
import com.jarvis.core.ToolResult
import com.jarvis.data.Event
import com.jarvis.data.Reminder
import com.jarvis.data.Task
import com.jarvis.data.AppDatabase
import com.jarvis.core.CalendarProviderGateway
import com.jarvis.core.CalendarWriteResult
import java.util.UUID
import java.time.LocalDate
import java.time.ZoneId

class CalendarAgent(
    private val db: AppDatabase,
    private val calendarProvider: CalendarProviderGateway? = null
) : JarvisAgent {
    override val supportedTools = setOf("meeting.create", "calendar.cancel")
    override suspend fun execute(call: ToolCall): ToolResult = when (call.toolName) {
        "meeting.create" -> {
            val id = UUID.randomUUID().toString()
            val hour = call.parameters["hour"]?.toIntOrNull() ?: return ToolResult("failure", error = "Meeting time is missing")
            val minute = call.parameters["minute"]?.toIntOrNull() ?: return ToolResult("failure", error = "Meeting time is missing")
            // Blank means the user said there is no place (online / "no location"); JarvisCore never invents one.
            val location = SafeStorage.text(call.parameters["location"]?.trim().orEmpty())
            val date = call.parameters["date"]?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
            } ?: return ToolResult("failure", error = "Meeting date is missing or invalid")
            val startTime = date.atTime(hour, minute)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            // The calendar app may sync events to the cloud: secret values never go into a title or place.
            val title = SafeStorage.text(call.parameters["title"] ?: "Meeting")
            val endTime = startTime + 90 * 60 * 1000
            val providerResult = calendarProvider?.insertEvent(title, startTime, endTime, location)
                ?: CalendarWriteResult.PermissionUnavailable
            val (calendarEventId, calendarStatus) = when (providerResult) {
                is CalendarWriteResult.Written -> providerResult.eventId to
                    if (providerResult.verified) "synced:${providerResult.calendarName}" else "local-only:the calendar didn't show the event when checked"
                CalendarWriteResult.PermissionUnavailable -> 0L to "local-only:calendar permission unavailable"
                CalendarWriteResult.NoWritableCalendar -> 0L to "local-only:no calendar account available"
                CalendarWriteResult.ProviderUnavailable -> 0L to "local-only:calendar provider unavailable"
            }
            db.eventDao().insert(
                Event(id, calendarEventId, title, startTime, endTime, location, true)
            )
            ToolResult(
                "success",
                id,
                details = mapOf("calendarStatus" to calendarStatus),
                userMessage = "Meeting created."
            )
        }
        "calendar.cancel" -> ToolResult("success", "cancel-requested")
        else -> ToolResult("failure", error = "Unsupported calendar tool")
    }
}

class ReminderAgent(private val db: AppDatabase) : JarvisAgent {
    override val supportedTools = setOf("reminder.create", "reminder.cancel")
    override suspend fun execute(call: ToolCall): ToolResult {
        // Real reminders are created by the reminder dialogue (TaskEngine), which asks for the time. A model's
        // reminder.create carries only a title, so this never schedules anything itself (before 2026-10-21 it stored
        // a record 23.5 h ahead that never fired and said "Reminder scheduled.").
        if (call.toolName == "reminder.cancel") {
            return ToolResult("failure", error = "unsupported", userMessage = UNSUPPORTED_CANCEL)
        }
        val title = call.parameters["title"]?.takeIf(String::isNotBlank) ?: return ToolResult("failure", error = "no title", userMessage = "What should I remind you about?")
        return ToolResult("failure", error = "standalone reminders not implemented", userMessage = notScheduled(title))
    }

    companion object {
        const val UNSUPPORTED_CANCEL = "Say \"list my reminders\" or \"cancel the reminder I just created\" to cancel one."
        fun notScheduled(title: String) =
            "I haven't scheduled anything yet. When should I remind you about \"$title\"? Say, for example, " +
                "\"remind me to $title in 30 minutes\" or \"… tomorrow at 9 AM\"."
    }
}

class TaskAgent(private val db: AppDatabase) : JarvisAgent {
    override val supportedTools = setOf("task.create", "task.list", "task.complete")
    override suspend fun execute(call: ToolCall): ToolResult {
        val id = UUID.randomUUID().toString()
        db.taskDao().insert(Task(id, SafeStorage.text(call.parameters["title"] ?: "New task"), null, "medium", "open"))
        return ToolResult("success", id, userMessage = "Task added.")
    }
}
