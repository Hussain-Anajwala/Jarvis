package com.jarvis.core

import com.jarvis.data.PlanExecution
import com.jarvis.data.PlanStepRecord

/** In-memory [ExecutionStore] with the same conditional-move semantics as the Room DAO. */
class MemoryExecutionStore : ExecutionStore {
    val executions = linkedMapOf<String, PlanExecution>()
    val steps = linkedMapOf<String, PlanStepRecord>()
    override suspend fun insert(execution: PlanExecution, steps: List<PlanStepRecord>) {
        require(execution.id !in executions); executions[execution.id] = execution
        steps.forEach { require(it.id !in this.steps); this.steps[it.id] = it }
    }
    override suspend fun execution(id: String) = executions[id]
    override suspend fun executions() = executions.values.toList()
    override suspend fun steps(executionId: String) = steps.values.filter { it.executionId == executionId }.sortedBy { it.position }
    override suspend fun update(execution: PlanExecution) { executions[execution.id] = execution }
    override suspend fun update(step: PlanStepRecord) { steps[step.id] = step }
    override suspend fun moveStep(id: String, from: List<String>, to: String, attemptDelta: Int, startedAt: Long?, now: Long): Boolean {
        val s = steps[id] ?: return false
        if (s.status !in from) return false
        steps[id] = s.copy(status = to, attempts = s.attempts + attemptDelta, startedAt = startedAt ?: s.startedAt, updatedAt = now)
        return true
    }
}

/**
 * A scripted tool. [effects] counts real "side effects" (what a duplicate would double). Faults: [failTimes] failures
 * before success, [uncertain] (times out after acting), [verifyAs] forced verification, [crashAfterEffect] throws after
 * the effect happened (simulating a process death before the result is recorded).
 */
class FakeTool(
    override val name: String,
    override val requiresApproval: Boolean = false,
    override val retrySafe: Boolean = true,
    var failTimes: Int = 0,
    var uncertain: Boolean = false,
    var verifyAs: Verification.Status? = null,
    var crashAfterEffect: Boolean = false,
    val log: MutableList<String> = mutableListOf()
) : PlanTool {
    val effects = mutableMapOf<String, Int>()
    var calls = 0
    override val textArgs = setOf("label")
    override fun validate(args: Map<String, String>) = if (args["bad"] != null) "invalid argument" else null
    override fun postcondition(args: Map<String, String>) = "$name happened"
    override fun describe(args: Map<String, String>) = "$name ${args["label"].orEmpty()}".trim()
    override suspend fun execute(stepKey: String, args: Map<String, String>): ToolOutcome {
        calls++
        log += name
        if (failTimes > 0) { failTimes--; return ToolOutcome(ToolOutcome.Status.FAILED, message = "simulated failure") }
        // An idempotent tool creates its effect once per step key; a non-idempotent one creates a new one every call.
        val key = if (retrySafe) stepKey else "$stepKey/$calls"
        if (retrySafe && key in effects) return ToolOutcome(ToolOutcome.Status.DONE, key, "already done")
        effects[key] = (effects[key] ?: 0) + 1
        if (crashAfterEffect) { crashAfterEffect = false; throw ProcessDeath() }
        if (uncertain) return ToolOutcome(ToolOutcome.Status.UNCERTAIN, message = "no reply")
        return ToolOutcome(ToolOutcome.Status.DONE, key, "ok")
    }
    override suspend fun verify(stepKey: String, args: Map<String, String>, effectRef: String?): Verification {
        verifyAs?.let { return Verification(it, "forced $it") }
        return if (effectRef != null && effectRef in effects) Verification(Verification.Status.VERIFIED, "effect $name present")
        else Verification(Verification.Status.NOT_MET, "no effect found")
    }
    override suspend fun reconcile(stepKey: String, args: Map<String, String>) = if (retrySafe) stepKey.takeIf { it in effects } else null
    val undone = mutableListOf<String>()
    override suspend fun undo(effectRef: String): Boolean { undone += effectRef; return true }
    val totalEffects get() = effects.values.sum()
}

/** Thrown by [FakeTool] to simulate the process dying right after a side effect. */
class ProcessDeath : Error("process died")

/** In-memory infrastructure for the real Phase 5/6 tools (no Android, no real alarms). */
class MemoryTaskStore : TaskStore {
    val tasks = linkedMapOf<String, com.jarvis.data.AgentTask>()
    val goalMap = linkedMapOf<String, com.jarvis.data.Goal>()
    override suspend fun insert(task: com.jarvis.data.AgentTask) { require(task.id !in tasks) { "duplicate id" }; tasks[task.id] = task }
    override suspend fun get(id: String) = tasks[id]
    override suspend fun all() = tasks.values.toList()
    override suspend fun transition(id: String, from: List<String>, to: String, now: Long, result: String?, attemptDelta: Int): Boolean {
        val t = tasks[id] ?: return false
        if (t.status !in from) return false
        tasks[id] = t.copy(status = to, updatedAt = now, result = result ?: t.result, attempts = t.attempts + attemptDelta)
        return true
    }
    override suspend fun setTime(id: String, at: Long, now: Long) { tasks[id] = tasks.getValue(id).copy(scheduledAt = at, updatedAt = now) }
    override suspend fun setResult(id: String, result: String, now: Long) { tasks[id] = tasks.getValue(id).copy(result = result, updatedAt = now) }
    override suspend fun insertGoal(goal: com.jarvis.data.Goal) { goalMap[goal.id] = goal }
    override suspend fun goal(id: String) = goalMap[id]
    override suspend fun goals() = goalMap.values.toList()
    override suspend fun setGoalStatus(id: String, status: String, now: Long) { goalMap[id] = goalMap.getValue(id).copy(status = status, updatedAt = now) }
}

class FakeTaskAlarms : TaskScheduler {
    val armed = mutableMapOf<String, Long>()
    override fun schedule(taskId: String, at: Long): Boolean { armed[taskId] = at; return true }
    override fun cancel(taskId: String) { armed.remove(taskId) }
    override fun isScheduled(taskId: String) = taskId in armed
}

class FakeTaskNotes : TaskNotifier {
    override fun enabled() = true
    override fun post(task: com.jarvis.data.AgentTask) = true
    override fun dismiss(taskId: String) = Unit
    override fun missed(tasks: List<com.jarvis.data.AgentTask>) = Unit
}

class FakeTimers(private val clock: () -> Long) : TimerPort {
    val timers = linkedMapOf<Int, JarvisTimer>()
    val armed = mutableSetOf<Int>()
    override fun start(seconds: Int, label: String): Int {
        val id = timers.size + 1
        timers[id] = JarvisTimer(id, label, seconds, clock(), JarvisTimer.State.RUNNING); armed += id
        return id
    }
    override fun timer(id: Int) = timers[id]
    override fun armed(id: Int) = id in armed
    override fun stop(id: Int): Boolean { armed -= id; timers[id] = timers.getValue(id).copy(state = JarvisTimer.State.CANCELLED); return true }
}

/** Wraps a real tool to inject one kind of fault. */
class Faulty(private val inner: PlanTool, var fault: String?, private val sabotage: suspend (String?) -> Unit = {}) : PlanTool by inner {
    var executeCalls = 0
    override suspend fun execute(stepKey: String, args: Map<String, String>): ToolOutcome {
        executeCalls++
        when (fault) {
            "fail" -> return ToolOutcome(ToolOutcome.Status.FAILED, message = "simulated failure")
            "fail_once" -> { fault = null; return ToolOutcome(ToolOutcome.Status.FAILED, message = "simulated failure") }
        }
        val out = inner.execute(stepKey, args)
        return when (fault) {
            "not_met" -> { sabotage(out.effectRef); out }
            "uncertain" -> out.copy(status = ToolOutcome.Status.UNCERTAIN, effectRef = null, message = "no reply")
            "crash_after_effect" -> { fault = null; throw ProcessDeath() }
            else -> out
        }
    }
    override suspend fun verify(stepKey: String, args: Map<String, String>, effectRef: String?): Verification =
        if (fault == "unverifiable") Verification(Verification.Status.UNVERIFIABLE, "outcome not observable") else inner.verify(stepKey, args, effectRef)
}
