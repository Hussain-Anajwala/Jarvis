package com.jarvis.core

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.data.AgentTask
import com.jarvis.data.AppDatabase
import com.jarvis.data.Goal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on the phone against a real Room database (in memory, never the app's own): a plan interrupted after its first
 * side effect is finished by a *new* executor and store over the same database, without repeating that effect.
 * Alarms are faked, so no real alarm is set.
 */
@RunWith(AndroidJUnit4::class)
class PlanRecoveryTest {
    private class Alarms : TaskScheduler {
        val armed = mutableMapOf<String, Long>()
        override fun schedule(taskId: String, at: Long): Boolean { armed[taskId] = at; return true }
        override fun cancel(taskId: String) { armed.remove(taskId) }
        override fun isScheduled(taskId: String) = taskId in armed
    }
    private class Notes : TaskNotifier {
        override fun enabled() = true
        override fun post(task: AgentTask) = true
        override fun dismiss(taskId: String) = Unit
        override fun missed(tasks: List<AgentTask>) = Unit
    }
    private class Crash : Error()

    @Test
    fun anInterruptedPlanIsReconciledFromRoomAfterARestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val now = System.currentTimeMillis()
            val alarms = Alarms()
            val tasks = RoomTaskStore(db)
            val engine = TaskEngine(tasks, alarms, Notes())
            val real = ReminderPlanTool(engine, tasks, alarms)
            var crash = true
            val crashing = object : PlanTool by real {
                override suspend fun execute(stepKey: String, args: Map<String, String>): ToolOutcome {
                    val out = real.execute(stepKey, args)
                    if (crash) { crash = false; throw Crash() } // dies after creating the reminder, before recording it
                    return out
                }
            }
            val steps = listOf(
                ProposedStep("reminder.schedule", mapOf("title" to "stretch", "at" to "${now + 3_600_000}", "zone" to "Asia/Kolkata")),
                ProposedStep("reminder.schedule", mapOf("title" to "drink water", "at" to "${now + 7_200_000}", "zone" to "Asia/Kolkata"))
            )
            val first = PlanExecutor(RoomExecutionStore(db), listOf(crashing))
            val id = (first.propose("two reminders", steps) as ProposeResult.Created).execution.id
            first.approve(id)
            try { first.run(id) } catch (_: Crash) { }
            assertEquals(StepStates.RUNNING, db.planExecutionDao().steps(id).first().status)

            // "Restart": a new executor and store, same database.
            val report = PlanExecutor(RoomExecutionStore(db), listOf(ReminderPlanTool(engine, RoomTaskStore(db), alarms))).recover().single()
            assertEquals(ExecStates.SUCCEEDED, report.execution.status)
            assertEquals(2, db.agentTaskDao().all().size) // the first reminder was not created twice
            assertEquals(2, alarms.armed.size)
            assertEquals(listOf(StepStates.SUCCEEDED, StepStates.SUCCEEDED), db.planExecutionDao().steps(id).map { it.status })
            // Existing Phase 5 records are untouched by plan runs.
            db.goalDao().insert(Goal("g1", "Prepare for exam", "active", null, null, now, now))
            assertEquals(1, db.goalDao().all().size)
        } finally {
            db.close()
        }
    }
}
