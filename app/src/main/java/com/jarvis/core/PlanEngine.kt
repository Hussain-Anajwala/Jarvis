package com.jarvis.core

import com.jarvis.data.AppDatabase
import com.jarvis.data.DecisionTraceEntry
import com.jarvis.data.Plan
import com.jarvis.data.PlanItem
import com.jarvis.data.Reminder
import java.util.UUID

class PlanEngine(private val db: AppDatabase) {
    suspend fun createMeetingPlan(goal: String, eventId: String, reminderId: String): String {
        val now = System.currentTimeMillis()
        val event = db.eventDao().findById(eventId)
        val planId = UUID.randomUUID().toString()
        val eventItem = PlanItem(UUID.randomUUID().toString(), planId, "calendar_event", eventId, null, "active")
        val reminderItem = PlanItem(UUID.randomUUID().toString(), planId, "reminder", reminderId, eventItem.id, "active")
        db.planDao().insert(Plan(planId, goal, "active", now, now, "text-demo"))
        db.planItemDao().insert(eventItem)
        db.planItemDao().insert(reminderItem)
        db.reminderDao().insert(
            Reminder(reminderId, "Leave for $goal", (event?.startTime ?: now) - 45 * 60 * 1000, null, "scheduled", reminderItem.id)
        )
        db.traceDao().insert(DecisionTraceEntry(UUID.randomUUID().toString(), planId, "Created meeting and linked departure reminder", "low", "plan.create", "executed", now))
        return planId
    }

    suspend fun cancelPlan(planId: String) {
        val items = db.planItemDao().forPlan(planId)
        items.forEach { item ->
            db.planItemDao().updateStatus(item.id, "cancelled")
            if (item.itemType == "reminder" && item.refId != null) db.reminderDao().updateStatus(item.refId, "cancelled")
        }
        val now = System.currentTimeMillis()
        db.planDao().updateStatus(planId, "cancelled", now)
        db.traceDao().insert(DecisionTraceEntry(UUID.randomUUID().toString(), planId, "Cancelled meeting and cascaded to ${items.size - 1} dependent reminder(s)", "medium", "plan.cancel", "confirmed", now))
    }
}
