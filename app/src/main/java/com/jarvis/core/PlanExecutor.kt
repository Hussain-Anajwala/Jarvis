package com.jarvis.core

import com.jarvis.data.PlanExecution
import com.jarvis.data.PlanStepRecord
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Execution (whole plan) states. */
object ExecStates {
    const val PROPOSED = "PROPOSED"
    const val WAITING_APPROVAL = "WAITING_APPROVAL"
    const val RUNNING = "RUNNING"
    const val VERIFYING = "VERIFYING"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val NEEDS_ATTENTION = "NEEDS_ATTENTION"
    const val CANCELLED = "CANCELLED"
    val FINAL = setOf(SUCCEEDED, FAILED, NEEDS_ATTENTION, CANCELLED)
}

/** Step states. UNVERIFIED = the action ran but its outcome could not be observed. */
object StepStates {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val VERIFYING = "VERIFYING"
    const val SUCCEEDED = "SUCCEEDED"
    const val UNVERIFIED = "UNVERIFIED"
    const val FAILED = "FAILED"
    const val NEEDS_ATTENTION = "NEEDS_ATTENTION"
    const val CANCELLED = "CANCELLED"
    const val SKIPPED = "SKIPPED"
}

/** What a tool reports after acting. [UNCERTAIN] = it may or may not have happened (a timeout, a handoff). */
data class ToolOutcome(val status: Status, val effectRef: String? = null, val message: String = "") {
    enum class Status { DONE, FAILED, UNCERTAIN }
}

/** Independent evidence about the expected postcondition. */
data class Verification(val status: Status, val evidence: String) {
    enum class Status { VERIFIED, NOT_MET, UNVERIFIABLE }
}

/**
 * A typed action a plan may use. Arguments are validated strings, never code. [stepKey] is the step's stable id: an
 * idempotent tool uses it to make a repeated call find the effect it already created instead of creating another.
 */
interface PlanTool {
    val name: String
    /** True for anything with a meaningful effect outside JARVIS's own records (sending, deleting, buying…). */
    val requiresApproval: Boolean
    /** True when running again cannot create a second effect (the tool is idempotent on [stepKey]). */
    val retrySafe: Boolean
    /**
     * Arguments that hold free text from the user and are sanitized before storage. Structured values (epoch times,
     * durations, zone ids) are not: a 13-digit epoch millisecond can look like a Luhn-valid card number.
     */
    val textArgs: Set<String> get() = emptySet()
    fun validate(args: Map<String, String>): String?
    fun postcondition(args: Map<String, String>): String
    fun describe(args: Map<String, String>): String
    suspend fun execute(stepKey: String, args: Map<String, String>): ToolOutcome
    suspend fun verify(stepKey: String, args: Map<String, String>, effectRef: String?): Verification
    /** After a crash: the effect this step already created, if it can be found; null if none or unknowable. */
    suspend fun reconcile(stepKey: String, args: Map<String, String>): String?
    /** Undo on plan cancellation where that is meaningful (cancel a scheduled reminder); false if not supported. */
    suspend fun undo(effectRef: String): Boolean = false
}

/** Storage for executions (Room in the app, a map in tests). */
interface ExecutionStore {
    suspend fun insert(execution: PlanExecution, steps: List<PlanStepRecord>)
    suspend fun execution(id: String): PlanExecution?
    suspend fun executions(): List<PlanExecution>
    suspend fun steps(executionId: String): List<PlanStepRecord>
    suspend fun update(execution: PlanExecution)
    suspend fun update(step: PlanStepRecord)
    /** Conditional move; false when the step was no longer in one of [from] (another run took it). */
    suspend fun moveStep(id: String, from: List<String>, to: String, attemptDelta: Int, startedAt: Long?, now: Long): Boolean
}

data class ProposedStep(val tool: String, val args: Map<String, String>)

sealed interface ProposeResult {
    data class Created(val execution: PlanExecution, val steps: List<PlanStepRecord>, val text: String) : ProposeResult
    data class Rejected(val reason: String) : ProposeResult
}

/** The final, user-facing account of a run: only what the evidence supports. */
data class RunReport(val execution: PlanExecution, val steps: List<PlanStepRecord>, val text: String)

/**
 * Phase 6 plan → approve → execute → verify. Rules enforced here, independent of any model:
 *  - a plan is validated (known tools, valid arguments, ≤ [MAX_STEPS], no repeated identical step) before it is stored;
 *  - approval is bound to the hash of the exact steps shown, expires after [APPROVAL_TTL_MS], and is re-checked before
 *    every step; a plan containing any approval-required step cannot run without it;
 *  - each step is moved to RUNNING (persisted, attempt counted) **before** the action, so a crash leaves evidence;
 *  - a step is SUCCEEDED only when its postcondition is verified; a tool's own "done" is not proof;
 *  - retries only for retry-safe steps, at most [MAX_ATTEMPTS]; an uncertain non-idempotent step is never repeated
 *    blindly — it becomes NEEDS_ATTENTION;
 *  - cancellation stops future steps and undoes scheduled work created by finished steps.
 */
class PlanExecutor(
    private val store: ExecutionStore,
    tools: List<PlanTool>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val trace: suspend (String) -> Unit = {},
    private val stepTimeoutMs: Long = STEP_TIMEOUT_MS
) {
    private val registry = tools.associateBy { it.name }

    companion object {
        const val MAX_STEPS = 8
        const val MAX_ATTEMPTS = 3
        const val STEP_TIMEOUT_MS = 10_000L
        const val APPROVAL_TTL_MS = 30 * 60_000L

        fun hash(steps: List<ProposedStep>): String = MessageDigest.getInstance("SHA-256")
            .digest(steps.joinToString("\n") { s -> s.tool + "|" + s.args.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" } }.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

        fun args(step: PlanStepRecord): Map<String, String> = JSONObject(step.argsJson).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    }

    fun tool(name: String) = registry[name]

    /** Validates and stores a proposal. Nothing runs yet. */
    suspend fun propose(objective: String, steps: List<ProposedStep>): ProposeResult {
        if (steps.isEmpty()) return ProposeResult.Rejected("the plan has no steps")
        if (steps.size > MAX_STEPS) return ProposeResult.Rejected("the plan has ${steps.size} steps; the limit is $MAX_STEPS")
        if (steps.toSet().size < steps.size) return ProposeResult.Rejected("the plan repeats the same step")
        steps.forEachIndexed { i, step ->
            val tool = registry[step.tool] ?: return ProposeResult.Rejected("step ${i + 1} uses an unknown action \"${step.tool}\"")
            tool.validate(step.args)?.let { return ProposeResult.Rejected("step ${i + 1}: $it") }
        }
        val now = clock()
        val id = UUID.randomUUID().toString()
        val needsApproval = steps.size > 1 || steps.any { registry.getValue(it.tool).requiresApproval }
        val execution = PlanExecution(id, SafeStorage.text(objective).take(200), if (needsApproval) ExecStates.WAITING_APPROVAL else ExecStates.PROPOSED,
            hash(steps), null, null, null, null, now, now)
        val records = steps.mapIndexed { i, step ->
            val tool = registry.getValue(step.tool)
            val stored = step.args.mapValues { (k, v) -> if (k in tool.textArgs) SafeStorage.text(v) else v }
            PlanStepRecord("$id#${i + 1}", id, i + 1, step.tool, JSONObject(stored).toString(),
                SafeStorage.text(tool.postcondition(step.args)), tool.requiresApproval, tool.retrySafe, StepStates.PENDING, 0, null, null, null, null, null, now)
        }
        store.insert(execution, records)
        trace("plan ${id.take(8)} proposed with ${steps.size} step(s): " + steps.joinToString(", ") { it.tool })
        return ProposeResult.Created(execution, records, proposalText(steps))
    }

    fun proposalText(steps: List<ProposedStep>): String =
        "Here's the plan:\n" + steps.mapIndexed { i, s ->
            val tool = registry.getValue(s.tool)
            "${i + 1}. ${tool.describe(s.args)}" + if (tool.requiresApproval) " (needs your approval: it changes or removes data)" else ""
        }.joinToString("\n") + "\nShall I go ahead? Say yes to run exactly these steps, or no."

    /** The user approved the plan they were shown: approval covers exactly these steps (by hash) for a limited time. */
    suspend fun approve(id: String): Boolean {
        val e = store.execution(id) ?: return false
        if (e.status != ExecStates.WAITING_APPROVAL && e.status != ExecStates.PROPOSED) return false
        val steps = store.steps(id)
        if (hash(steps.map { ProposedStep(it.tool, args(it)) }) != e.stepsHash) return false // changed since shown
        store.update(e.copy(status = ExecStates.PROPOSED, approvalHash = e.stepsHash, approvedAt = clock(), updatedAt = clock()))
        trace("plan ${id.take(8)} approved by you")
        return true
    }

    suspend fun reject(id: String) = finishCancelled(id, "you declined the plan", undo = false)

    /** Withdraws approval: no further step runs; finished steps are kept and reported. */
    suspend fun revoke(id: String): Boolean {
        val e = store.execution(id) ?: return false
        if (e.status in ExecStates.FINAL) return false
        store.update(e.copy(approvalHash = null, approvedAt = null, updatedAt = clock()))
        trace("plan ${id.take(8)} approval withdrawn")
        return finishCancelled(id, "approval withdrawn", undo = false)
    }

    /** Stops future steps and undoes scheduled work that finished steps created. */
    suspend fun cancel(id: String) = finishCancelled(id, "cancelled by you", undo = true)

    private suspend fun finishCancelled(id: String, reason: String, undo: Boolean): Boolean {
        val e = store.execution(id) ?: return false
        if (e.status in ExecStates.FINAL) return false
        val now = clock()
        store.update(e.copy(status = ExecStates.CANCELLED, failureReason = reason, updatedAt = now))
        var undone = 0
        for (step in store.steps(id)) {
            when (step.status) {
                StepStates.PENDING -> store.update(step.copy(status = StepStates.CANCELLED, updatedAt = now))
                StepStates.SUCCEEDED, StepStates.UNVERIFIED -> if (undo && step.effectRef != null && registry[step.tool]?.undo(step.effectRef) == true) {
                    undone++
                    store.update(step.copy(result = "undone after cancellation", updatedAt = now))
                }
            }
        }
        trace("plan ${id.take(8)} cancelled: $reason" + if (undone > 0) "; undid $undone step(s)" else "")
        return true
    }

    private fun approvalValid(e: PlanExecution, steps: List<PlanStepRecord>): Boolean {
        val needs = steps.size > 1 || steps.any { it.requiresApproval }
        if (!needs) return true
        val at = e.approvedAt ?: return false
        return e.approvalHash == e.stepsHash && clock() - at <= APPROVAL_TTL_MS &&
            hash(steps.map { ProposedStep(it.tool, args(it)) }) == e.stepsHash
    }

    /** Runs the remaining steps in order. Safe to call again: finished steps are not repeated. */
    suspend fun run(id: String): RunReport {
        var e = store.execution(id) ?: error("no such plan")
        if (e.status in ExecStates.FINAL) return report(id)
        var steps = store.steps(id)
        if (!approvalValid(e, steps)) {
            store.update(e.copy(status = ExecStates.WAITING_APPROVAL, updatedAt = clock()))
            trace("plan ${id.take(8)} needs (renewed) approval before it can run")
            return report(id)
        }
        e = e.copy(status = ExecStates.RUNNING, updatedAt = clock()).also { store.update(it) }
        for (step in steps) {
            if (step.status in setOf(StepStates.SUCCEEDED, StepStates.UNVERIFIED, StepStates.SKIPPED, StepStates.CANCELLED)) continue
            // Re-read before every step: a cancel or revoke from elsewhere stops the run here.
            val current = store.execution(id)!!
            if (current.status == ExecStates.CANCELLED) return report(id)
            if (!approvalValid(current, store.steps(id))) {
                store.update(current.copy(status = ExecStates.WAITING_APPROVAL, updatedAt = clock()))
                return report(id)
            }
            val outcome = runStep(store.steps(id).first { it.id == step.id })
            if (outcome == StepStates.FAILED || outcome == StepStates.NEEDS_ATTENTION) {
                store.steps(id).filter { it.status == StepStates.PENDING }.forEach { store.update(it.copy(status = StepStates.SKIPPED, updatedAt = clock())) }
                break
            }
        }
        steps = store.steps(id)
        val final = when {
            steps.any { it.status == StepStates.FAILED } -> ExecStates.FAILED
            steps.any { it.status == StepStates.NEEDS_ATTENTION || it.status == StepStates.UNVERIFIED } -> ExecStates.NEEDS_ATTENTION
            steps.all { it.status == StepStates.SUCCEEDED } -> ExecStates.SUCCEEDED
            else -> ExecStates.NEEDS_ATTENTION
        }
        val failure = steps.firstOrNull { it.status == StepStates.FAILED || it.status == StepStates.NEEDS_ATTENTION }?.error
        store.update(store.execution(id)!!.copy(status = final, failureReason = failure?.let(SafeStorage::text), summary = summary(steps), updatedAt = clock()))
        trace("plan ${id.take(8)} finished: $final (${steps.count { it.status == StepStates.SUCCEEDED }}/${steps.size} verified)")
        return report(id)
    }

    /** One step: claim it (persisted), act, record the effect, verify, retry only when safe. Returns the step's final state. */
    private suspend fun runStep(step: PlanStepRecord): String {
        val tool = registry[step.tool] ?: return fail(step, "the action \"${step.tool}\" is no longer available", StepStates.FAILED)
        val args = args(step)
        tool.validate(args)?.let { return fail(step, it, StepStates.FAILED) }
        var current = step
        while (true) {
            if (current.attempts >= MAX_ATTEMPTS) return fail(current, "gave up after ${current.attempts} attempts", StepStates.FAILED)
            if (!store.moveStep(current.id, listOf(StepStates.PENDING), StepStates.RUNNING, 1, clock(), clock())) {
                return store.steps(step.executionId).first { it.id == step.id }.status // someone else has it
            }
            current = reload(current)
            val outcome = try {
                withTimeout(stepTimeoutMs) { tool.execute(current.id, args) }
            } catch (_: TimeoutCancellationException) {
                ToolOutcome(ToolOutcome.Status.UNCERTAIN, message = "timed out after ${stepTimeoutMs / 1000} s")
            } catch (error: Exception) {
                ToolOutcome(ToolOutcome.Status.FAILED, message = "error (${error.javaClass.simpleName})")
            }
            store.update(current.copy(effectRef = outcome.effectRef ?: current.effectRef, result = SafeStorage.text(outcome.message), updatedAt = clock()))
            current = reload(current)
            when (outcome.status) {
                ToolOutcome.Status.FAILED -> {
                    if (tool.retrySafe && current.attempts < MAX_ATTEMPTS) { current = back(current); continue }
                    return fail(current, outcome.message.ifBlank { "the action failed" }, StepStates.FAILED)
                }
                ToolOutcome.Status.UNCERTAIN -> {
                    val found = tool.reconcile(current.id, args)
                    if (found != null) {
                        store.update(current.copy(effectRef = found, updatedAt = clock())); current = reload(current)
                    } else if (tool.retrySafe && current.attempts < MAX_ATTEMPTS) {
                        current = back(current); continue
                    } else {
                        return fail(current, "it may or may not have happened (${outcome.message}); check before trying again", StepStates.NEEDS_ATTENTION)
                    }
                }
                ToolOutcome.Status.DONE -> Unit
            }
            return verify(current, tool, args)
        }
    }

    private suspend fun verify(step: PlanStepRecord, tool: PlanTool, args: Map<String, String>): String {
        store.moveStep(step.id, listOf(StepStates.RUNNING), StepStates.VERIFYING, 0, null, clock())
        val v = try { tool.verify(step.id, args, reload(step).effectRef) } catch (error: Exception) {
            Verification(Verification.Status.UNVERIFIABLE, "verification error (${error.javaClass.simpleName})")
        }
        val s = reload(step)
        val to = when (v.status) {
            Verification.Status.VERIFIED -> StepStates.SUCCEEDED
            Verification.Status.UNVERIFIABLE -> StepStates.UNVERIFIED
            Verification.Status.NOT_MET -> StepStates.FAILED
        }
        store.update(s.copy(status = to, evidence = SafeStorage.text(v.evidence), error = if (to == StepStates.FAILED) "expected outcome not observed: ${SafeStorage.text(v.evidence)}" else s.error, updatedAt = clock()))
        trace("step ${s.position} ${s.tool}: $to — ${v.evidence}")
        return to
    }

    private suspend fun back(step: PlanStepRecord): PlanStepRecord {
        store.moveStep(step.id, listOf(StepStates.RUNNING), StepStates.PENDING, 0, null, clock())
        return reload(step)
    }

    private suspend fun fail(step: PlanStepRecord, reason: String, to: String): String {
        val s = reload(step)
        store.update(s.copy(status = to, error = SafeStorage.text(reason), updatedAt = clock()))
        trace("step ${s.position} ${s.tool}: $to — $reason")
        return to
    }

    private suspend fun reload(step: PlanStepRecord) = store.steps(step.executionId).first { it.id == step.id }

    /**
     * After a restart: a step left RUNNING/VERIFYING is reconciled — verified if its effect was recorded or can be found,
     * re-run only if retry-safe, otherwise marked NEEDS_ATTENTION (never repeated blindly). Then the plan continues only
     * if its approval is still valid; otherwise it waits for renewed approval.
     */
    suspend fun recover(): List<RunReport> {
        val reports = mutableListOf<RunReport>()
        for (e in store.executions().filter { it.status == ExecStates.RUNNING || it.status == ExecStates.VERIFYING }) {
            for (step in store.steps(e.id).filter { it.status == StepStates.RUNNING || it.status == StepStates.VERIFYING }) {
                val tool = registry[step.tool]
                val args = args(step)
                val ref = step.effectRef ?: tool?.reconcile(step.id, args)
                when {
                    tool == null -> fail(step, "the action is no longer available", StepStates.NEEDS_ATTENTION)
                    ref != null -> {
                        store.update(step.copy(effectRef = ref, status = StepStates.RUNNING, updatedAt = clock()))
                        verify(reload(step), tool, args)
                    }
                    tool.retrySafe -> store.moveStep(step.id, listOf(StepStates.RUNNING, StepStates.VERIFYING), StepStates.PENDING, 0, null, clock())
                    else -> fail(step, "JARVIS stopped while this step was running and can't tell whether it happened; check before repeating it", StepStates.NEEDS_ATTENTION)
                }
            }
            trace("plan ${e.id.take(8)} recovered after a restart")
            reports += if (store.steps(e.id).any { it.status == StepStates.NEEDS_ATTENTION }) finishAttention(e.id) else run(e.id)
        }
        return reports
    }

    private suspend fun finishAttention(id: String): RunReport {
        val steps = store.steps(id)
        steps.filter { it.status == StepStates.PENDING }.forEach { store.update(it.copy(status = StepStates.SKIPPED, updatedAt = clock())) }
        store.update(store.execution(id)!!.copy(status = ExecStates.NEEDS_ATTENTION, summary = summary(store.steps(id)),
            failureReason = steps.first { it.status == StepStates.NEEDS_ATTENTION }.error, updatedAt = clock()))
        return report(id)
    }

    private fun summary(steps: List<PlanStepRecord>): String =
        "${steps.count { it.status == StepStates.SUCCEEDED }} verified, ${steps.count { it.status == StepStates.UNVERIFIED }} unverified, " +
            "${steps.count { it.status == StepStates.FAILED || it.status == StepStates.NEEDS_ATTENTION }} failed or blocked, " +
            "${steps.count { it.status == StepStates.SKIPPED || it.status == StepStates.CANCELLED }} not run"

    suspend fun report(id: String): RunReport {
        val e = store.execution(id)!!
        val steps = store.steps(id)
        val lines = steps.map { s ->
            val what = registry[s.tool]?.describe(args(s)) ?: s.tool
            val mark = when (s.status) {
                StepStates.SUCCEEDED -> "✓ verified"
                StepStates.UNVERIFIED -> "done, but I couldn't confirm it"
                StepStates.FAILED -> "✗ failed: ${s.error}"
                StepStates.NEEDS_ATTENTION -> "! needs you: ${s.error}"
                StepStates.SKIPPED -> "not run"
                StepStates.CANCELLED -> "cancelled"
                else -> s.status.lowercase()
            }
            "${s.position}. $what — $mark"
        }
        val head = when (e.status) {
            ExecStates.SUCCEEDED -> "Done — every step was checked:"
            ExecStates.FAILED -> if (steps.any { it.status == StepStates.SUCCEEDED }) "Partly done — a step failed, so I stopped:" else "That didn't work:"
            ExecStates.NEEDS_ATTENTION -> "Not fully confirmed — please check:"
            ExecStates.CANCELLED -> "Stopped (${e.failureReason}):"
            ExecStates.WAITING_APPROVAL -> "This plan needs your approval before it runs" + if (e.approvedAt != null) " again (the earlier approval expired or the steps changed):" else ":"
            else -> "Plan status: ${e.status.lowercase()}:"
        }
        return RunReport(e, steps, head + "\n" + lines.joinToString("\n"))
    }
}
