package com.jarvis.core

import com.jarvis.data.AgentTask
import com.jarvis.data.Goal
import java.util.UUID
import org.json.JSONObject

/** Task states and the only transitions allowed between them. */
object TaskStates {
    const val PENDING = "pending"
    const val SCHEDULED = "scheduled"
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val PAUSED = "paused"
    const val MISSED = "missed"

    val ACTIVE = listOf(PENDING, SCHEDULED, PAUSED)

    val TRANSITIONS: Map<String, Set<String>> = mapOf(
        PENDING to setOf(SCHEDULED, FAILED, CANCELLED, PAUSED),
        SCHEDULED to setOf(RUNNING, CANCELLED, PAUSED, MISSED),
        RUNNING to setOf(COMPLETED, FAILED, SCHEDULED),
        PAUSED to setOf(SCHEDULED, CANCELLED, MISSED),
        COMPLETED to emptySet(), FAILED to emptySet(), CANCELLED to emptySet(), MISSED to emptySet()
    )

    fun allowed(from: String, to: String) = to in TRANSITIONS[from].orEmpty()
}

/** Task kinds. Only reminders exist; anything that acts on the world would need approval first. */
object TaskKinds {
    const val REMINDER = "reminder"
    const val GOAL_STEP = "goal_step"
    /** Notification-only kinds: safe to run without approval. */
    val NOTIFY_ONLY = setOf(REMINDER, GOAL_STEP)
}

/** Storage the engine needs (Room in the app, a map in tests). */
interface TaskStore {
    suspend fun insert(task: AgentTask)
    suspend fun get(id: String): AgentTask?
    suspend fun all(): List<AgentTask>
    /** Conditional update; returns false if the task was no longer in one of [from]. */
    suspend fun transition(id: String, from: List<String>, to: String, now: Long, result: String? = null, attemptDelta: Int = 0): Boolean
    suspend fun setTime(id: String, at: Long, now: Long)
    suspend fun setResult(id: String, result: String, now: Long)
    suspend fun insertGoal(goal: Goal)
    suspend fun goal(id: String): Goal?
    suspend fun goals(): List<Goal>
    suspend fun setGoalStatus(id: String, status: String, now: Long)
}

/** The OS alarm for a task. */
interface TaskScheduler {
    /** Sets (or replaces) the alarm; returns true when it is exact. Throws if the OS refused. */
    fun schedule(taskId: String, at: Long): Boolean
    fun cancel(taskId: String)
    fun isScheduled(taskId: String): Boolean
}

/** How a due task reaches the user. */
interface TaskNotifier {
    fun enabled(): Boolean
    /** Posts the reminder with a stable id per task (re-posting replaces it); false if Android refused. */
    fun post(task: AgentTask): Boolean
    fun dismiss(taskId: String)
    fun missed(tasks: List<AgentTask>)
}

sealed interface CreateResult {
    data class Scheduled(val task: AgentTask, val exact: Boolean, val notificationsOff: Boolean) : CreateResult
    data class Failed(val reason: String) : CreateResult
}

/**
 * Durable reminders and goal steps (Phase 5). A task is "scheduled" only after it is stored **and** the OS alarm is set;
 * delivery is idempotent (scheduled → running is a conditional update, so a second alarm or a restart cannot deliver it
 * twice); failures are recorded as failed with a safe reason, never as completed.
 */
class TaskEngine(
    private val store: TaskStore,
    private val scheduler: TaskScheduler,
    private val notifier: TaskNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: suspend (String) -> Unit = {}
) {
    companion object {
        /** A task due this recently is still delivered (late) after a restart instead of being reported missed. */
        const val LATE_GRACE_MS = 10 * 60_000L
        const val RETRY_DELAY_MS = 60_000L
        const val DEFAULT_TIMEOUT_MS = 2 * 60_000L
        const val MAX_ATTEMPTS = 3
    }

    suspend fun createReminder(objective: String, at: Long, timeZone: String, origin: String?, goalId: String? = null, inputs: Map<String, String> = emptyMap()): CreateResult {
        val now = clock()
        if (at <= now) return CreateResult.Failed("that time has already passed")
        val task = AgentTask(
            id = UUID.randomUUID().toString(),
            kind = if (goalId == null) TaskKinds.REMINDER else TaskKinds.GOAL_STEP,
            objective = SafeStorage.text(objective.trim()).take(200),
            inputsJson = JSONObject(inputs.mapValues { SafeStorage.text(it.value) }).toString(),
            scheduledAt = at, timeZone = timeZone, status = TaskStates.PENDING, attempts = 0, maxAttempts = MAX_ATTEMPTS,
            timeoutMs = DEFAULT_TIMEOUT_MS, result = null, originRequest = origin?.let(SafeStorage::text),
            approval = if (goalId == null) "not_required" else "approved", goalId = goalId, createdAt = now, updatedAt = now
        )
        return create(task)
    }

    /** Stores, then schedules; only both together make a task "scheduled". */
    suspend fun create(task: AgentTask): CreateResult {
        if (task.kind !in TaskKinds.NOTIFY_ONLY && task.approval != "approved") {
            return CreateResult.Failed("this kind of task needs your approval before it can be scheduled")
        }
        val at = task.scheduledAt ?: return CreateResult.Failed("no time given")
        store.insert(task)
        val exact = try {
            scheduler.schedule(task.id, at)
        } catch (error: Exception) {
            store.transition(task.id, listOf(TaskStates.PENDING), TaskStates.FAILED, clock(), "couldn't set the alarm (${error.javaClass.simpleName})")
            log("task ${task.id.take(8)} failed to schedule")
            return CreateResult.Failed("Android didn't let me set the alarm, so the reminder isn't scheduled")
        }
        store.transition(task.id, listOf(TaskStates.PENDING), TaskStates.SCHEDULED, clock())
        return CreateResult.Scheduled(store.get(task.id)!!, exact, !notifier.enabled())
    }

    /** The alarm fired. Safe to call more than once: only the first call that finds it scheduled delivers it. */
    suspend fun deliver(id: String): String {
        if (!store.transition(id, listOf(TaskStates.SCHEDULED), TaskStates.RUNNING, clock())) return "ignored: not scheduled (duplicate, paused, or cancelled)"
        val task = store.get(id) ?: return "ignored: gone"
        if (!notifier.enabled()) {
            store.transition(id, listOf(TaskStates.RUNNING), TaskStates.FAILED, clock(), "notifications are off, so the reminder couldn't be shown")
            log("reminder not shown: notifications are off")
            return "failed: notifications off"
        }
        if (notifier.post(task)) {
            store.transition(id, listOf(TaskStates.RUNNING), TaskStates.COMPLETED, clock(), "delivered")
            return "delivered"
        }
        return retryOrFail(task, "Android refused the notification")
    }

    private suspend fun retryOrFail(task: AgentTask, reason: String): String {
        val now = clock()
        if (task.attempts + 1 < task.maxAttempts) {
            store.setTime(task.id, now + RETRY_DELAY_MS, now)
            store.transition(task.id, listOf(TaskStates.RUNNING), TaskStates.SCHEDULED, now, "retrying: $reason", attemptDelta = 1)
            runCatching { scheduler.schedule(task.id, now + RETRY_DELAY_MS) }
            return "retry scheduled"
        }
        store.transition(task.id, listOf(TaskStates.RUNNING), TaskStates.FAILED, now, reason, attemptDelta = 1)
        return "failed: $reason"
    }

    /** Cancels the stored task and its alarm, then checks the alarm is gone. */
    suspend fun cancel(id: String): Boolean {
        if (!store.transition(id, TaskStates.ACTIVE, TaskStates.CANCELLED, clock(), "cancelled by you")) return false
        scheduler.cancel(id)
        notifier.dismiss(id)
        return !scheduler.isScheduled(id)
    }

    suspend fun pause(id: String): Boolean {
        if (!store.transition(id, listOf(TaskStates.SCHEDULED, TaskStates.PENDING), TaskStates.PAUSED, clock())) return false
        scheduler.cancel(id)
        return true
    }

    /** Resumes a paused task: re-armed if still in the future, otherwise marked missed (never fired late silently). */
    suspend fun resume(id: String): Boolean {
        val task = store.get(id) ?: return false
        if (task.status != TaskStates.PAUSED) return false
        val at = task.scheduledAt ?: return false
        if (at <= clock()) return store.transition(id, listOf(TaskStates.PAUSED), TaskStates.MISSED, clock(), "its time passed while paused").let { false }
        return try {
            scheduler.schedule(id, at)
            store.transition(id, listOf(TaskStates.PAUSED), TaskStates.SCHEDULED, clock())
        } catch (_: Exception) {
            false
        }
    }

    data class Recovery(val rescheduled: Int, val deliveredLate: Int, val missed: Int, val retried: Int)

    /** After boot, app start, an app update, or a clock/time-zone change: re-arm, deliver late, or report missed. */
    suspend fun recover(): Recovery {
        val now = clock()
        var rescheduled = 0; var late = 0; var retried = 0
        val missed = mutableListOf<AgentTask>()
        for (task in store.all()) {
            val at = task.scheduledAt ?: continue
            when (task.status) {
                TaskStates.PENDING -> if (now - task.updatedAt > task.timeoutMs) {
                    // Stored but never armed (the process died in between): arm it now, or report it missed.
                    if (at > now) runCatching { scheduler.schedule(task.id, at) }.onSuccess {
                        store.transition(task.id, listOf(TaskStates.PENDING), TaskStates.SCHEDULED, now); rescheduled++
                    } else if (store.transition(task.id, listOf(TaskStates.PENDING), TaskStates.MISSED, now, "missed while JARVIS wasn't running")) missed += task
                }
                TaskStates.SCHEDULED -> when {
                    at > now -> { runCatching { scheduler.schedule(task.id, at) }; rescheduled++ }
                    now - at <= LATE_GRACE_MS -> { deliver(task.id); late++ }
                    store.transition(task.id, listOf(TaskStates.SCHEDULED), TaskStates.MISSED, now, "missed while the phone was off or JARVIS was stopped") -> missed += task
                }
                TaskStates.RUNNING -> if (now - task.updatedAt > task.timeoutMs) {
                    // Interrupted mid-delivery: retry (the notification id is stable, so it can't appear twice).
                    retryOrFail(task, "interrupted while delivering"); retried++
                }
            }
        }
        if (missed.isNotEmpty()) notifier.missed(missed)
        return Recovery(rescheduled, late, missed.size, retried)
    }

    // ---- Goals ----

    data class GoalStep(val at: Long, val objective: String, val inputs: Map<String, String> = emptyMap())

    /** Creates a goal and schedules every approved step; a step that can't be scheduled is reported, not hidden. */
    suspend fun createGoal(title: String, dueAt: Long?, steps: List<GoalStep>, timeZone: String, origin: String?): Pair<Goal, List<CreateResult>> {
        val now = clock()
        val goal = Goal(UUID.randomUUID().toString(), SafeStorage.text(title).take(120), "active", dueAt, origin?.let(SafeStorage::text), now, now)
        store.insertGoal(goal)
        val results = steps.map { createReminder(it.objective, it.at, timeZone, origin, goal.id, it.inputs) }
        return goal to results
    }

    suspend fun pauseGoal(goalId: String): Int {
        store.setGoalStatus(goalId, "paused", clock())
        return store.all().filter { it.goalId == goalId && it.status == TaskStates.SCHEDULED }.count { pause(it.id) }
    }

    /** Future steps are re-armed; steps whose time passed while paused are marked missed (skipped, not fired). */
    suspend fun resumeGoal(goalId: String): Int {
        store.setGoalStatus(goalId, "active", clock())
        return store.all().filter { it.goalId == goalId && it.status == TaskStates.PAUSED }.count { resume(it.id) }
    }

    suspend fun cancelGoal(goalId: String): Int {
        store.setGoalStatus(goalId, "cancelled", clock())
        return store.all().filter { it.goalId == goalId && it.status in TaskStates.ACTIVE }.count { cancel(it.id) }
    }

    /** The user says a session is done (notification action or Plans tab). Delivery alone never counts as done. */
    suspend fun markDone(id: String): Boolean {
        val task = store.get(id) ?: return false
        if (task.status == TaskStates.CANCELLED) return false
        store.setResult(id, "done", clock())
        val goalId = task.goalId ?: return true
        if (store.all().filter { it.goalId == goalId }.all { it.result == "done" || it.status == TaskStates.CANCELLED }) {
            store.setGoalStatus(goalId, "completed", clock())
        }
        return true
    }

    data class GoalProgress(val goal: Goal, val done: Int, val total: Int, val next: AgentTask?)

    suspend fun progress(goalId: String): GoalProgress? {
        val goal = store.goal(goalId) ?: return null
        val steps = store.all().filter { it.goalId == goalId && it.status != TaskStates.CANCELLED }
        return GoalProgress(goal, steps.count { it.result == "done" }, steps.size,
            steps.filter { it.status == TaskStates.SCHEDULED }.minByOrNull { it.scheduledAt ?: Long.MAX_VALUE })
    }
}
