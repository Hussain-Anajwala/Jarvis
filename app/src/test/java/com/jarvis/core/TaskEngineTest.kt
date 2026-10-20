package com.jarvis.core

import com.jarvis.data.AgentTask
import com.jarvis.data.Goal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskEngineTest {
    /** In-memory store with the same conditional-update semantics as the Room DAO; rejects illegal transitions. */
    private class Store : TaskStore {
        val tasks = linkedMapOf<String, AgentTask>()
        val goalMap = linkedMapOf<String, Goal>()
        override suspend fun insert(task: AgentTask) { require(task.id !in tasks) { "duplicate id" }; tasks[task.id] = task }
        override suspend fun get(id: String) = tasks[id]
        override suspend fun all() = tasks.values.toList()
        override suspend fun transition(id: String, from: List<String>, to: String, now: Long, result: String?, attemptDelta: Int): Boolean {
            val t = tasks[id] ?: return false
            if (t.status !in from) return false
            check(TaskStates.allowed(t.status, to)) { "illegal transition ${t.status} -> $to" }
            tasks[id] = t.copy(status = to, updatedAt = now, result = result ?: t.result, attempts = t.attempts + attemptDelta)
            return true
        }
        override suspend fun setTime(id: String, at: Long, now: Long) { tasks[id] = tasks.getValue(id).copy(scheduledAt = at, updatedAt = now) }
        override suspend fun setResult(id: String, result: String, now: Long) { tasks[id] = tasks.getValue(id).copy(result = result, updatedAt = now) }
        override suspend fun insertGoal(goal: Goal) { goalMap[goal.id] = goal }
        override suspend fun goal(id: String) = goalMap[id]
        override suspend fun goals() = goalMap.values.toList()
        override suspend fun setGoalStatus(id: String, status: String, now: Long) { goalMap[id] = goalMap.getValue(id).copy(status = status, updatedAt = now) }
    }

    private class Alarms : TaskScheduler {
        val armed = mutableMapOf<String, Long>()
        var refuse = false
        var exact = true
        override fun schedule(taskId: String, at: Long): Boolean { if (refuse) throw SecurityException("denied"); armed[taskId] = at; return exact }
        override fun cancel(taskId: String) { armed.remove(taskId) }
        override fun isScheduled(taskId: String) = taskId in armed
    }

    private class Notes : TaskNotifier {
        var on = true
        var refuse = false
        val posted = mutableListOf<String>()
        val missedTasks = mutableListOf<String>()
        override fun enabled() = on
        override fun post(task: AgentTask): Boolean { if (refuse) return false; posted += task.id; return true }
        override fun dismiss(taskId: String) = Unit
        override fun missed(tasks: List<AgentTask>) { missedTasks += tasks.map { it.objective } }
    }

    private var now = 1_000_000L
    private val store = Store()
    private val alarms = Alarms()
    private val notes = Notes()
    private val engine = TaskEngine(store, alarms, notes, { now })

    private fun create(minutes: Long = 30, title: String = "stretch") = runBlocking {
        engine.createReminder(title, now + minutes * 60_000, "Asia/Kolkata", "remind me to $title") as CreateResult.Scheduled
    }

    @Test
    fun aReminderIsScheduledOnlyWhenStoredAndArmed() = runBlocking {
        val created = create()
        assertEquals(TaskStates.SCHEDULED, store.tasks.getValue(created.task.id).status)
        assertTrue(alarms.isScheduled(created.task.id))
        alarms.refuse = true
        val failed = engine.createReminder("call", now + 60_000, "Asia/Kolkata", null)
        assertTrue(failed is CreateResult.Failed)
        assertEquals(TaskStates.FAILED, store.tasks.values.last().status)
        assertTrue(engine.createReminder("late", now - 1, "Asia/Kolkata", null) is CreateResult.Failed)
    }

    @Test
    fun deliveryHappensOnceEvenIfTheAlarmFiresTwice() = runBlocking {
        val id = create().task.id
        now += 30 * 60_000
        assertEquals("delivered", engine.deliver(id))
        assertTrue(engine.deliver(id).startsWith("ignored"))
        engine.recover()
        assertEquals(listOf(id), notes.posted)
        assertEquals(TaskStates.COMPLETED, store.tasks.getValue(id).status)
    }

    @Test
    fun cancellationRemovesTheTaskAndItsAlarmAndAFiredAlarmIsIgnored() = runBlocking {
        val id = create().task.id
        assertTrue(engine.cancel(id))
        assertFalse(alarms.isScheduled(id))
        assertTrue(engine.deliver(id).startsWith("ignored"))
        assertTrue(notes.posted.isEmpty())
        assertFalse("can't cancel twice", engine.cancel(id))
    }

    @Test
    fun pauseAndResumeReArmFutureTasksAndMarkPassedOnesMissed() = runBlocking {
        val soon = create(10).task.id
        val later = create(120).task.id
        assertTrue(engine.pause(soon)); assertTrue(engine.pause(later))
        assertTrue(alarms.armed.isEmpty())
        now += 30 * 60_000
        assertFalse(engine.resume(soon))
        assertEquals(TaskStates.MISSED, store.tasks.getValue(soon).status)
        assertTrue(engine.resume(later))
        assertTrue(alarms.isScheduled(later))
    }

    @Test
    fun aRefusedNotificationIsRetriedThenFailedNeverReportedDone() = runBlocking {
        val id = create(1).task.id
        notes.refuse = true
        now += 60_000
        assertEquals("retry scheduled", engine.deliver(id))
        now += TaskEngine.RETRY_DELAY_MS
        assertEquals("retry scheduled", engine.deliver(id))
        now += TaskEngine.RETRY_DELAY_MS
        assertTrue(engine.deliver(id).startsWith("failed"))
        val task = store.tasks.getValue(id)
        assertEquals(TaskStates.FAILED, task.status)
        assertEquals(3, task.attempts)
    }

    @Test
    fun notificationsOffIsAFailureWithAReason() = runBlocking {
        notes.on = false
        val created = create(1)
        assertTrue(created.notificationsOff)
        now += 60_000
        engine.deliver(created.task.id)
        assertEquals(TaskStates.FAILED, store.tasks.getValue(created.task.id).status)
        assertTrue(store.tasks.getValue(created.task.id).result!!.contains("notifications are off"))
    }

    @Test
    fun afterARestartFutureTasksAreReArmedRecentOnesDeliveredAndOldOnesReportedMissed() = runBlocking {
        val t0 = now
        val future = create(60).task.id
        val recent = create(15, "recent").task.id
        val old = create(1, "old one").task.id
        val interrupted = create(2, "interrupted").task.id
        alarms.armed.clear() // a reboot clears every alarm
        now = t0 + 3 * 60_000
        store.transition(interrupted, listOf(TaskStates.SCHEDULED), TaskStates.RUNNING, now) // died mid-delivery
        now = t0 + 16 * 60_000 // recent was due 1 min ago, old 15 min ago (beyond the 10-min grace)
        val recovery = engine.recover()
        assertTrue(alarms.isScheduled(future))
        assertEquals(TaskStates.SCHEDULED, store.tasks.getValue(future).status)
        assertEquals(TaskStates.COMPLETED, store.tasks.getValue(recent).status)
        assertEquals(TaskStates.MISSED, store.tasks.getValue(old).status)
        assertEquals(listOf("old one"), notes.missedTasks)
        assertEquals(TaskStates.SCHEDULED, store.tasks.getValue(interrupted).status)
        assertEquals(1, store.tasks.getValue(interrupted).attempts)
        assertEquals(TaskEngine.Recovery(rescheduled = 1, deliveredLate = 1, missed = 1, retried = 1), recovery)
        // Running recovery again changes nothing and posts nothing twice.
        engine.recover()
        assertEquals(1, notes.posted.count { it == recent })
    }

    @Test
    fun goalsPauseResumeCancelAndCompleteOnlyWhenTheUserMarksStepsDone() = runBlocking {
        val steps = (1..3).map { TaskEngine.GoalStep(now + it * 86_400_000L, "Study: topic $it") }
        val (goal, results) = engine.createGoal("Prepare for exam", now + 4 * 86_400_000L, steps, "Asia/Kolkata", "help me prepare")
        assertTrue(results.all { it is CreateResult.Scheduled })
        assertEquals(3, engine.pauseGoal(goal.id))
        assertTrue(alarms.armed.isEmpty())
        assertEquals("paused", store.goalMap.getValue(goal.id).status)
        assertEquals(3, engine.resumeGoal(goal.id))
        val ids = store.tasks.values.filter { it.goalId == goal.id }.map { it.id }
        now = steps[0].at
        engine.deliver(ids[0])
        assertEquals("delivered is not done", 0, engine.progress(goal.id)!!.done)
        engine.markDone(ids[0])
        assertEquals(1, engine.progress(goal.id)!!.done)
        engine.markDone(ids[1]); engine.markDone(ids[2])
        assertEquals("completed", store.goalMap.getValue(goal.id).status)
        val (other, _) = engine.createGoal("Second", null, steps.map { it.copy(at = it.at + 86_400_000L) }, "Asia/Kolkata", null)
        assertEquals(3, engine.cancelGoal(other.id))
        assertEquals("cancelled", store.goalMap.getValue(other.id).status)
    }

    @Test
    fun consequentialTaskKindsNeedApprovalAndSecretsAreNotStored() = runBlocking {
        val email = AgentTask("e1", "send_email", "Email the report", "{}", now + 60_000, "Asia/Kolkata", TaskStates.PENDING, 0, 3, 120_000, null, null, "pending", null, now, now)
        assertTrue(engine.create(email) is CreateResult.Failed)
        assertTrue(store.tasks.isEmpty())
        val secret = create(5, "pay rent, card PIN is 739204").task
        assertFalse(store.tasks.getValue(secret.id).objective.contains("739204"))
    }
}
