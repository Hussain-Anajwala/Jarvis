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
        db.planDao().insert(Plan(planId, SafeStorage.text(goal), "active", now, now, sourceConversationRef))
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

    suspend fun createMeetingPlan(
        goal: String,
        eventId: String,
        reminderId: String,
        travel: TravelTimeEstimate,
        calendarStatus: String,
        modelName: String = "system"
    ): String {
        val now = System.currentTimeMillis()
        val event = db.eventDao().findById(eventId)
        val planId = UUID.randomUUID().toString()
        val eventItem = PlanItem(UUID.randomUUID().toString(), planId, "calendar_event", eventId, null, "active")
        val reminderItem = PlanItem(UUID.randomUUID().toString(), planId, "reminder", reminderId, eventItem.id, "active")
        createPlan(
            goal,
            listOf(eventItem, reminderItem),
            listOf(PlanItemDependency(reminderItem.id, eventItem.id)),
            monitoringRules = listOf(
                MonitoringRule(
                    UUID.randomUUID().toString(),
                    reminderItem.id,
                    "travel_time",
                    null,
                    now,
                    "${travel.minutes}|${travel.freshness}"
                )
            ),
            sourceConversationRef = "text-demo"
        )
        // With a real route the reminder says when to leave; without one it is a plain 15-minute heads-up.
        val leadMinutes = if (travel.known) travel.minutes + 15 else 15
        val start = event?.startTime ?: now
        // A departure time that has already passed becomes "leave now", never a reminder set in the past.
        val leaveBy = start - leadMinutes * 60 * 1000L
        val late = travel.known && leaveBy <= now && start > now
        val reminderTitle = when {
            late -> "Leave now for $goal"
            travel.known -> "Leave for $goal"
            else -> "$goal starts in 15 minutes"
        }
        db.reminderDao().insert(
            Reminder(reminderId, SafeStorage.text(reminderTitle), if (late) now + 60_000L else leaveBy, null, "scheduled", reminderItem.id)
        )
        val calendarSummary = when {
            calendarStatus.startsWith("synced:") -> "saved to calendar ${calendarStatus.removePrefix("synced:")}"
            calendarStatus == "local-only:no calendar account available" -> "saved locally only — no calendar account available"
            else -> "saved locally only — ${calendarStatus.removePrefix("local-only:")}"
        }
        db.traceDao().insertRedacted(
            DecisionTraceEntry(
                UUID.randomUUID().toString(),
                planId,
                (if (travel.known) "Created meeting and linked departure reminder (${travel.minutes} min ${travel.freshness})"
                else "Created meeting and a 15-minute reminder (travel time unknown: ${travel.source})") + "; $calendarSummary",
                "low",
                "plan.create",
                "executed",
                now,
                modelName
            )
        )
        return planId
    }

    suspend fun cancelPlan(
        planId: String,
        calendarCancellationStatus: String? = null,
        modelName: String = "system"
    ) {
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
        val calendarSummary = calendarCancellationStatus?.let { "; $it" }.orEmpty()
        db.traceDao().insertRedacted(
            DecisionTraceEntry(
                UUID.randomUUID().toString(),
                planId,
                "Cancelled plan and cascaded to ${cancelled.size - 1} dependent item(s)$calendarSummary",
                "medium",
                "plan.cancel",
                "confirmed",
                now,
                modelName
            )
        )
    }
}
