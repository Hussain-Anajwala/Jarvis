package com.jarvis.agents

import com.jarvis.core.JarvisAgent
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
            val location = call.parameters["location"]?.takeIf { it.isNotBlank() }
                ?: return ToolResult("failure", error = "Meeting location is missing")
            val date = call.parameters["date"]?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
            } ?: return ToolResult("failure", error = "Meeting date is missing or invalid")
            val startTime = date.atTime(hour, minute)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            val title = call.parameters["title"] ?: "Meeting"
            val endTime = startTime + 90 * 60 * 1000
            val providerResult = calendarProvider?.insertEvent(title, startTime, endTime, location)
                ?: CalendarWriteResult.PermissionUnavailable
            val (calendarEventId, calendarStatus) = when (providerResult) {
                is CalendarWriteResult.Written -> providerResult.eventId to "synced:${providerResult.calendarName}"
                CalendarWriteResult.PermissionUnavailable -> 0L to "local-only:calendar permission unavailable"
                CalendarWriteResult.NoWritableCalendar -> 0L to "local-only:no calendar account available"
                CalendarWriteResult.ProviderUnavailable -> 0L to "local-only:calendar provider unavailable"
            }
            db.eventDao().insert(
                Event(id, calendarEventId, title, startTime, endTime, location, true)
            )
            ToolResult("success", id, details = mapOf("calendarStatus" to calendarStatus))
        }
        "calendar.cancel" -> ToolResult("success", "cancel-requested")
        else -> ToolResult("failure", error = "Unsupported calendar tool")
    }
}

class ReminderAgent(private val db: AppDatabase) : JarvisAgent {
    override val supportedTools = setOf("reminder.create", "reminder.cancel")
    override suspend fun execute(call: ToolCall): ToolResult {
        val id = UUID.randomUUID().toString()
        db.reminderDao().insert(Reminder(id, call.parameters["title"] ?: "Departure reminder", System.currentTimeMillis() + 84_600_000, null, "scheduled", null))
        return ToolResult("success", id)
    }
}

class TaskAgent(private val db: AppDatabase) : JarvisAgent {
    override val supportedTools = setOf("task.create", "task.list", "task.complete")
    override suspend fun execute(call: ToolCall): ToolResult {
        val id = UUID.randomUUID().toString()
        db.taskDao().insert(Task(id, call.parameters["title"] ?: "New task", null, "medium", "open"))
        return ToolResult("success", id)
    }
}
