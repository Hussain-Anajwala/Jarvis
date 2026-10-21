package com.jarvis.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
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

/**
 * Personal memory (M5). [contentText] is the value the user sees and edits. [type] is fact, relationship,
 * preference, or favorite; [memoryKey] names well-known entries (e.g. favorite_song, user_name) or a person.
 * [embeddingVector] is unused: retrieval is lexical (see DECISION-LOG 2026-10-18).
 */
@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey val id: String,
    val contentText: String,
    val embeddingVector: String?,
    val sourceRef: String,
    val createdAt: Long,
    val lastUsedAt: Long?,
    val userDeletable: Boolean,
    @ColumnInfo(defaultValue = "'fact'") val type: String = "fact",
    @ColumnInfo(defaultValue = "''") val memoryKey: String = "",
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0
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
    val createdAt: Long,
    @ColumnInfo(defaultValue = "'system'") val modelName: String = "system"
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

/** A message notification captured while notification access is on (M3). Retention-limited; never synced. */
@Entity(
    tableName = "notification_records",
    indices = [Index("postedAt"), Index("notificationKey")]
)
data class NotificationRecord(
    @PrimaryKey val id: String,
    val notificationKey: String,
    val appPackage: String,
    val appLabel: String,
    val sender: String,
    val textSnippet: String,
    val postedAt: Long,
    val read: Boolean
)

/**
 * Phase 5: a durable unit of work that JARVIS runs on a schedule — a standalone reminder or one step of a goal.
 * [status] moves only along the transitions in `TaskStates` (core); every change is a conditional update, so a
 * repeated alarm or a restart can never deliver it twice.
 */
@Entity(tableName = "agent_tasks", indices = [Index("status"), Index("goalId")])
data class AgentTask(
    @PrimaryKey val id: String,
    /** "reminder" or "goal_step". Kinds that act on the world would require [approval] = "approved". */
    val kind: String,
    /** What to remind about (secret values removed). */
    val objective: String,
    /** The inputs the task needs, as JSON (e.g. the topic of a study session). */
    val inputsJson: String,
    /** When to run, epoch milliseconds; null while still being asked for. */
    val scheduledAt: Long?,
    /** The time zone the user meant when the time was set. */
    val timeZone: String?,
    val status: String,
    val attempts: Int,
    val maxAttempts: Int,
    /** A run that has not finished after this long is treated as interrupted. */
    val timeoutMs: Long,
    /** Outcome or safe error text ("delivered", "notifications are off", "done"). */
    val result: String?,
    /** The request that created it (secret values removed). */
    val originRequest: String?,
    /** "not_required", "pending", "approved", or "denied". */
    val approval: String,
    val goalId: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/** Phase 5: a bounded multi-step goal ("prepare for my exam next Sunday") whose steps are [AgentTask]s. */
@Entity(tableName = "goals")
data class Goal(
    @PrimaryKey val id: String,
    val title: String,
    /** "active", "paused", "completed", or "cancelled". */
    val status: String,
    val dueAt: Long?,
    val originRequest: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Phase 6: one multi-step request carried out as a plan (plan → approve → execute → verify). Text is sanitized
 * before it is stored. [approvalHash] is the hash of the exact steps the user approved; any change voids it.
 */
@Entity(tableName = "plan_executions", indices = [Index("status")])
data class PlanExecution(
    @PrimaryKey val id: String,
    val objective: String,
    /** PROPOSED, WAITING_APPROVAL, RUNNING, VERIFYING, SUCCEEDED, FAILED, NEEDS_ATTENTION, CANCELLED. */
    val status: String,
    val stepsHash: String,
    val approvalHash: String?,
    val approvedAt: Long?,
    val summary: String?,
    val failureReason: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/** Phase 6: one ordered step of a [PlanExecution]; [id] is stable and is the idempotency key for its effect. */
@Entity(tableName = "plan_steps", indices = [Index("executionId")])
data class PlanStepRecord(
    @PrimaryKey val id: String,
    val executionId: String,
    val position: Int,
    val tool: String,
    val argsJson: String,
    /** What must be observably true afterwards, in words. */
    val postcondition: String,
    val requiresApproval: Boolean,
    val retrySafe: Boolean,
    /** PENDING, RUNNING, VERIFYING, SUCCEEDED, UNVERIFIED, FAILED, NEEDS_ATTENTION, CANCELLED, SKIPPED. */
    val status: String,
    val attempts: Int,
    /** The id of what the step created (a task or timer), recorded right after the action. */
    val effectRef: String?,
    val result: String?,
    val evidence: String?,
    val error: String?,
    val startedAt: Long?,
    val updatedAt: Long
)
