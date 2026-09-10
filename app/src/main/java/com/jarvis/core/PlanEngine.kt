package com.jarvis.core

import com.jarvis.data.AppDatabase
import com.jarvis.data.DecisionTraceEntry
import com.jarvis.data.Plan
import com.jarvis.data.PlanItem
import com.jarvis.data.PlanItemDependency
import com.jarvis.data.Reminder
import com.jarvis.data.MonitoringRule
import java.util.UUID

class PlanEngine(private val db: AppDatabase) {
    suspend fun createPlan(
        goal: String,
        items: List<PlanItem>,
        dependencies: List<PlanItemDependency>,
        monitoringRules: List<MonitoringRule> = emptyList(),
        sourceConversationRef: String? = null
    ): String {
        require(items.isNotEmpty()) { "A plan must contain at least one item" }
        require(items.all { it.planId == items.first().planId }) { "All plan items must belong to the same plan" }
        val now = System.currentTimeMillis()
        val planId = items.first().planId
        db.planDao().insert(Plan(planId, goal, "active", now, now, sourceConversationRef))
        items.forEach { db.planItemDao().insert(it) }
        dependencies.forEach { dependency ->
            require(items.any { it.id == dependency.planItemId } && items.any { it.id == dependency.dependsOnItemId }) {
                "Plan dependencies must reference items in the same plan"
            }
            db.planItemDao().insertDependency(dependency)
        }
        monitoringRules.forEach { db.monitoringRuleDao().insert(it) }
        return planId
    }

    suspend fun createMeetingPlan(goal: String, eventId: String, reminderId: String): String {
        val now = System.currentTimeMillis()
        val event = db.eventDao().findById(eventId)
        val planId = UUID.randomUUID().toString()
        val eventItem = PlanItem(UUID.randomUUID().toString(), planId, "calendar_event", eventId, null, "active")
        val reminderItem = PlanItem(UUID.randomUUID().toString(), planId, "reminder", reminderId, eventItem.id, "active")
        createPlan(
            goal,
            listOf(eventItem, reminderItem),
            listOf(PlanItemDependency(reminderItem.id, eventItem.id)),
            sourceConversationRef = "text-demo"
        )
        db.reminderDao().insert(
            Reminder(reminderId, "Leave for $goal", (event?.startTime ?: now) - 45 * 60 * 1000, null, "scheduled", reminderItem.id)
        )
        db.traceDao().insert(DecisionTraceEntry(UUID.randomUUID().toString(), planId, "Created meeting and linked departure reminder", "low", "plan.create", "executed", now))
        return planId
    }

    suspend fun cancelPlan(planId: String) {
        val items = db.planItemDao().forPlan(planId)
        val dependencies = db.planItemDao().dependencies()
            .filter { dependency -> items.any { it.id == dependency.planItemId } }
        val cancelled = items.map { it.id }.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            dependencies.forEach { dependency ->
                if (dependency.dependsOnItemId in cancelled && cancelled.add(dependency.planItemId)) changed = true
            }
        } while (changed)
        items.filter { it.id in cancelled }.forEach { item ->
            db.planItemDao().updateStatus(item.id, "cancelled")
            if (item.itemType == "reminder" && item.refId != null) db.reminderDao().updateStatus(item.refId, "cancelled")
        }
        val now = System.currentTimeMillis()
        db.planDao().updateStatus(planId, "cancelled", now)
        db.traceDao().insert(DecisionTraceEntry(UUID.randomUUID().toString(), planId, "Cancelled plan and cascaded to ${cancelled.size - 1} dependent item(s)", "medium", "plan.cancel", "confirmed", now))
    }
}
