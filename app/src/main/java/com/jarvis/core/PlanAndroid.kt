package com.jarvis.core

import android.content.Context
import com.jarvis.data.AppDatabase
import com.jarvis.data.DecisionTraceEntry
import com.jarvis.data.PlanExecution
import com.jarvis.data.PlanStepRecord
import java.util.UUID

class RoomExecutionStore(db: AppDatabase) : ExecutionStore {
    private val dao = db.planExecutionDao()
    override suspend fun insert(execution: PlanExecution, steps: List<PlanStepRecord>) { dao.insertExecution(execution); dao.insertSteps(steps) }
    override suspend fun execution(id: String) = dao.execution(id)
    override suspend fun executions() = dao.executions()
    override suspend fun steps(executionId: String) = dao.steps(executionId)
    override suspend fun update(execution: PlanExecution) = dao.updateExecution(execution)
    override suspend fun update(step: PlanStepRecord) = dao.updateStep(step)
    override suspend fun moveStep(id: String, from: List<String>, to: String, attemptDelta: Int, startedAt: Long?, now: Long) =
        dao.moveStep(id, from, to, attemptDelta, startedAt, now) > 0
}

class AndroidTimerPort(context: Context) : TimerPort {
    private val engine = JarvisTimerEngine(context)
    override fun start(seconds: Int, label: String) = engine.start(seconds, label).id
    override fun timer(id: Int) = engine.all().firstOrNull { it.id == id }
    override fun armed(id: Int) = engine.isArmed(id)
    override fun stop(id: Int) = engine.stop(id) is JarvisTimerEngine.StopResult.Stopped
}

object PlanExecutors {
    fun create(context: Context): PlanExecutor {
        val db = AppDatabase.create(context)
        val tasks = RoomTaskStore(db)
        val alarms = AlarmTaskScheduler(context)
        val engine = TaskEngines.create(context)
        return PlanExecutor(
            RoomExecutionStore(db),
            listOf(ReminderPlanTool(engine, tasks, alarms), TimerPlanTool(AndroidTimerPort(context)), CancelAllRemindersTool(engine, tasks, alarms)),
            trace = { line ->
                db.traceDao().insertRedacted(DecisionTraceEntry(UUID.randomUUID().toString(), null, line, "low", "plan.execute", line, System.currentTimeMillis()))
            }
        )
    }
}
