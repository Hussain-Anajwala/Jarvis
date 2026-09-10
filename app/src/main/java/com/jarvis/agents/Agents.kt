package com.jarvis.agents

import com.jarvis.core.JarvisAgent
import com.jarvis.core.ToolCall
import com.jarvis.core.ToolResult
import com.jarvis.data.Event
import com.jarvis.data.Reminder
import com.jarvis.data.Task
import com.jarvis.data.AppDatabase
import java.util.UUID
import java.util.Calendar

class CalendarAgent(private val db: AppDatabase) : JarvisAgent {
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
            db.eventDao().insert(Event(id, 0L, call.parameters["title"] ?: "Meeting", tomorrowTen, tomorrowTen + 90 * 60 * 1000, "college", true))
            ToolResult("success", id)
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
