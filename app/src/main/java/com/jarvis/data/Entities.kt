package com.jarvis.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profiles")
data class UserProfile(
    @PrimaryKey val id: String,
    val displayName: String?,
    val homeLocation: String?,
    val defaultTravelBufferMin: Int,
    val createdAt: Long
)

@Entity(tableName = "plans")
data class Plan(
    @PrimaryKey val id: String,
    val goalText: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceConversationRef: String?
)

@Entity(tableName = "plan_items")
data class PlanItem(
    @PrimaryKey val id: String,
    val planId: String,
    val itemType: String,
    val refId: String?,
    val dependsOnItemId: String?,
    val status: String
)

@Entity(
    tableName = "plan_item_dependencies",
    primaryKeys = ["planItemId", "dependsOnItemId"]
)
data class PlanItemDependency(
    val planItemId: String,
    val dependsOnItemId: String
)

@Entity(tableName = "events")
data class Event(
    @PrimaryKey val id: String,
    val androidCalendarEventId: Long,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val locationText: String?,
    val createdByJarvis: Boolean
)

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey val id: String,
    val title: String,
    val triggerTime: Long?,
    val triggerLocationRef: String?,
    val status: String,
    val planItemId: String?
)

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey val id: String,
    val title: String,
    val dueAt: Long?,
    val priority: String,
    val status: String
)

@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey val id: String,
    val contentText: String,
    val embeddingVector: String?,
    val sourceRef: String,
    val createdAt: Long,
    val lastUsedAt: Long?,
    val userDeletable: Boolean
)

@Entity(tableName = "permission_grants")
data class PermissionGrant(
    @PrimaryKey val id: String,
    val capability: String,
    val status: String,
    val updatedAt: Long
)

@Entity(tableName = "decision_trace_entries")
data class DecisionTraceEntry(
    @PrimaryKey val id: String,
    val planId: String?,
    val actionSummary: String,
    val riskLevel: String,
    val toolName: String,
    val outcome: String,
    val createdAt: Long
)

@Entity(tableName = "automation_rules")
data class AutomationRule(
    @PrimaryKey val id: String,
    val patternDescription: String,
    val status: String,
    val createdFromPlanIds: String
)

@Entity(tableName = "monitoring_rules")
data class MonitoringRule(
    @PrimaryKey val id: String,
    val planItemId: String,
    val conditionType: String,
    val lastCheckedAt: Long?,
    val nextCheckAt: Long?,
    val lastKnownValue: String?
)
