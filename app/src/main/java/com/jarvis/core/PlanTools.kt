package com.jarvis.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The typed actions a Phase 6 plan may use in the app. Each one says how its outcome is checked independently of its
 * own return value. Only local, low-risk actions exist: no messaging, calling, email, or purchases.
 */

/** Schedules a reminder. Idempotent: the task id is derived from the step id, so a repeat finds the same task. */
class ReminderPlanTool(private val engine: TaskEngine, private val tasks: TaskStore, private val alarms: TaskScheduler, private val clock: () -> Long = System::currentTimeMillis) : PlanTool {
    override val name = "reminder.schedule"
    override val requiresApproval = false
    override val retrySafe = true
    override val textArgs = setOf("title")

    override fun validate(args: Map<String, String>): String? {
        if (args["title"].isNullOrBlank()) return "a reminder needs something to remind you about"
        val at = args["at"]?.toLongOrNull() ?: return "a reminder needs a time"
        if (at <= clock()) return "that time has already passed"
        if (runCatching { ZoneId.of(args["zone"] ?: "") }.isFailure) return "unknown time zone"
        return null
    }

    private fun taskId(stepKey: String) = "plan-$stepKey"
    private fun at(args: Map<String, String>) = ZonedDateTime.ofInstant(Instant.ofEpochMilli(args.getValue("at").toLong()), ZoneId.of(args.getValue("zone")))

    override fun describe(args: Map<String, String>) = "Remind you to ${TaskReplies.yourWords(args["title"].orEmpty())} at ${ReminderTime.describe(at(args), ZonedDateTime.now(at(args).zone))}"
    override fun postcondition(args: Map<String, String>) = "a stored reminder in state scheduled at the requested time, with its alarm set"

    override suspend fun execute(stepKey: String, args: Map<String, String>): ToolOutcome {
        tasks.get(taskId(stepKey))?.let { return ToolOutcome(ToolOutcome.Status.DONE, it.id, "already created") }
        return when (val r = engine.createReminder(args.getValue("title"), args.getValue("at").toLong(), args.getValue("zone"), "plan step", id = taskId(stepKey))) {
            is CreateResult.Scheduled -> ToolOutcome(ToolOutcome.Status.DONE, r.task.id, "reminder created" + if (r.notificationsOff) " (notifications are off)" else "")
            is CreateResult.Failed -> ToolOutcome(ToolOutcome.Status.FAILED, null, r.reason)
        }
    }

    override suspend fun verify(stepKey: String, args: Map<String, String>, effectRef: String?): Verification {
        val task = tasks.get(effectRef ?: taskId(stepKey)) ?: return Verification(Verification.Status.NOT_MET, "no reminder record found")
        val wantAt = args.getValue("at").toLong()
        return when {
            task.status != TaskStates.SCHEDULED -> Verification(Verification.Status.NOT_MET, "reminder is ${task.status}, not scheduled")
            task.scheduledAt != wantAt -> Verification(Verification.Status.NOT_MET, "reminder is set for a different time")
            !alarms.isScheduled(task.id) -> Verification(Verification.Status.NOT_MET, "reminder is stored but its alarm is not set")
            else -> Verification(Verification.Status.VERIFIED, "reminder record scheduled and its alarm is set with Android")
        }
    }

    override suspend fun reconcile(stepKey: String, args: Map<String, String>) = tasks.get(taskId(stepKey))?.id
    override suspend fun undo(effectRef: String) = engine.cancel(effectRef)
}

/** Starts a JARVIS timer. Not idempotent (each start is a new timer), so it is never retried blindly. */
interface TimerPort {
    fun start(seconds: Int, label: String): Int
    fun timer(id: Int): JarvisTimer?
    fun armed(id: Int): Boolean
    fun stop(id: Int): Boolean
}

class TimerPlanTool(private val timers: TimerPort) : PlanTool {
    override val name = "timer.start"
    override val requiresApproval = false
    override val retrySafe = false
    override val textArgs = setOf("label")

    override fun validate(args: Map<String, String>): String? {
        val seconds = args["seconds"]?.toIntOrNull() ?: return "a timer needs a duration"
        return if (seconds in 1..86_400) null else "a timer must be 1 second to 24 hours"
    }

    override fun describe(args: Map<String, String>) = "Start a ${ClockText.duration(args.getValue("seconds").toInt())} timer"
    override fun postcondition(args: Map<String, String>) = "a running JARVIS timer of that length with its alarm set"

    override suspend fun execute(stepKey: String, args: Map<String, String>): ToolOutcome {
        val id = timers.start(args.getValue("seconds").toInt(), args["label"].orEmpty())
        return ToolOutcome(ToolOutcome.Status.DONE, id.toString(), "timer started")
    }

    override suspend fun verify(stepKey: String, args: Map<String, String>, effectRef: String?): Verification {
        val id = effectRef?.toIntOrNull() ?: return Verification(Verification.Status.UNVERIFIABLE, "no timer id was recorded")
        val timer = timers.timer(id) ?: return Verification(Verification.Status.NOT_MET, "no timer record found")
        return when {
            timer.state != JarvisTimer.State.RUNNING -> Verification(Verification.Status.NOT_MET, "timer is ${timer.state.name.lowercase()}")
            timer.seconds != args.getValue("seconds").toInt() -> Verification(Verification.Status.NOT_MET, "timer has a different length")
            !timers.armed(id) -> Verification(Verification.Status.NOT_MET, "timer is recorded but its alarm is not set")
            else -> Verification(Verification.Status.VERIFIED, "timer record running and its alarm is set with Android")
        }
    }

    /** A timer start can't be matched to this step after a crash, so recovery asks the user instead of repeating. */
    override suspend fun reconcile(stepKey: String, args: Map<String, String>): String? = null
    override suspend fun undo(effectRef: String) = effectRef.toIntOrNull()?.let(timers::stop) ?: false
}

/** Cancels every pending standalone reminder: it removes the user's data, so it always needs approval. Idempotent. */
class CancelAllRemindersTool(private val engine: TaskEngine, private val tasks: TaskStore, private val alarms: TaskScheduler) : PlanTool {
    override val name = "reminders.cancel_all"
    override val requiresApproval = true
    override val retrySafe = true

    override fun validate(args: Map<String, String>): String? = null
    override fun describe(args: Map<String, String>) = "Cancel all your pending reminders"
    override fun postcondition(args: Map<String, String>) = "no standalone reminder is pending and none has an alarm set"

    private suspend fun active() = tasks.all().filter { it.kind == TaskKinds.REMINDER && it.status in TaskStates.ACTIVE }

    override suspend fun execute(stepKey: String, args: Map<String, String>): ToolOutcome {
        val targets = active()
        val cancelled = targets.count { engine.cancel(it.id) }
        return ToolOutcome(ToolOutcome.Status.DONE, "cancelled:$cancelled", "cancelled $cancelled of ${targets.size} reminder(s)")
    }

    override suspend fun verify(stepKey: String, args: Map<String, String>, effectRef: String?): Verification {
        val left = active()
        val armed = tasks.all().count { it.kind == TaskKinds.REMINDER && alarms.isScheduled(it.id) }
        return if (left.isEmpty() && armed == 0) Verification(Verification.Status.VERIFIED, "no pending reminders and no reminder alarms remain")
        else Verification(Verification.Status.NOT_MET, "${left.size} reminder(s) still pending, $armed alarm(s) still set")
    }

    override suspend fun reconcile(stepKey: String, args: Map<String, String>): String? = null
}
