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
import java.util.Calendar

class CalendarAgent(
    private val db: AppDatabase,
    private val calendarProvider: CalendarProviderGateway? = null
) : JarvisAgent {
    override val supportedTools = setOf("meeting.create", "calendar.cancel")
    override suspend fun execute(call: ToolCall): ToolResult = when (call.toolName) {
        "meeting.create" -> {
            val id = UUID.randomUUID().toString()
            val tomorrowTen = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 10)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val title = call.parameters["title"] ?: "Meeting"
            val endTime = tomorrowTen + 90 * 60 * 1000
            val providerResult = calendarProvider?.insertEvent(title, tomorrowTen, endTime, "college")
                ?: CalendarWriteResult.PermissionUnavailable
            val (calendarEventId, calendarStatus) = when (providerResult) {
                is CalendarWriteResult.Written -> providerResult.eventId to "synced:${providerResult.calendarName}"
                CalendarWriteResult.PermissionUnavailable -> 0L to "local-only:calendar permission unavailable"
                CalendarWriteResult.NoWritableCalendar -> 0L to "local-only:no calendar account available"
                CalendarWriteResult.ProviderUnavailable -> 0L to "local-only:calendar provider unavailable"
            }
            db.eventDao().insert(
                Event(id, calendarEventId, title, tomorrowTen, endTime, "college", true)
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
