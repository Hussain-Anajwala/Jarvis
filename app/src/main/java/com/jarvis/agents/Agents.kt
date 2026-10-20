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
                is CalendarWriteResult.Written -> providerResult.eventId to "synced:${providerResult.calendarName}"
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
        // Standalone reminders have no time and nothing that notifies (only meeting-linked reminders fire). Before
        // 2026-10-21 this stored one 23.5 h ahead and said "Reminder scheduled." (false), and reminder.cancel created one.
        if (call.toolName == "reminder.cancel") {
            return ToolResult("failure", error = "unsupported", userMessage = UNSUPPORTED_CANCEL)
        }
        val title = call.parameters["title"]?.takeIf(String::isNotBlank) ?: return ToolResult("failure", error = "no title", userMessage = "What should I remind you about?")
        return ToolResult("failure", error = "standalone reminders not implemented", userMessage = notScheduled(title))
    }

    companion object {
        const val UNSUPPORTED_CANCEL = "I can't cancel standalone reminders yet. Meeting reminders are cancelled with the meeting."
        fun notScheduled(title: String) =
            "I can't schedule a standalone reminder yet, so nothing will notify you about \"$title\". " +
                "Say \"set an alarm for 6 PM\" or \"set a timer for 20 minutes\" instead."
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
