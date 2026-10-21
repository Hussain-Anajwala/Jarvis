package com.jarvis.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6: plan → approve → execute → verify, with fake tools only (no real side effects). */
class PlanExecutorTest {
    private var now = 1_000_000L
    private val store = MemoryExecutionStore()
    private val order = mutableListOf<String>()
    private val a = FakeTool("a", log = order)
    private val b = FakeTool("b", log = order)
    private val c = FakeTool("c", log = order)
    private val send = FakeTool("send", requiresApproval = true, log = order)
    private val traces = mutableListOf<String>()
    private fun executor(timeout: Long = 1_000) = PlanExecutor(store, listOf(a, b, c, send), { now }, { traces += it }, timeout)
    private val ex = executor()

    private fun step(tool: String, vararg args: Pair<String, String>) = ProposedStep(tool, mapOf(*args))
    private fun proposeAndApprove(vararg steps: ProposedStep) = runBlocking {
        val id = (ex.propose("objective", steps.toList()) as ProposeResult.Created).execution.id
        assertTrue(ex.approve(id))
        id
    }

    @Test
    fun invalidPlansAreRejectedBeforeAnythingIsStored() = runBlocking {
        assertTrue(ex.propose("x", emptyList()) is ProposeResult.Rejected)
        assertTrue(ex.propose("x", listOf(step("unknown"))) is ProposeResult.Rejected)
        assertTrue(ex.propose("x", listOf(step("a", "bad" to "1"))) is ProposeResult.Rejected)
        assertTrue("a cycle (repeated identical step) is rejected", ex.propose("x", listOf(step("a"), step("a"))) is ProposeResult.Rejected)
        assertTrue(ex.propose("x", (1..9).map { step("a", "label" to "$it") }) is ProposeResult.Rejected)
        assertTrue(store.executions.isEmpty())
        val ok = ex.propose("x", listOf(step("a"), step("b"))) as ProposeResult.Created
        assertEquals(ExecStates.WAITING_APPROVAL, ok.execution.status)
        assertTrue(ok.text.contains("1. a") && ok.text.contains("2. b"))
    }

    @Test
    fun approvedStepsRunInOrderAndAreEachVerified() = runBlocking {
        val id = proposeAndApprove(step("a"), step("b"), step("c"))
        val report = ex.run(id)
        assertEquals(listOf("a", "b", "c"), order)
        assertEquals(ExecStates.SUCCEEDED, report.execution.status)
        assertTrue(report.steps.all { it.status == StepStates.SUCCEEDED && it.evidence!!.startsWith("effect") })
        assertTrue(report.text.startsWith("Done — every step was checked"))
    }

    @Test
    fun withoutApprovalNothingRunsAndRejectionOrRevocationStopsIt() = runBlocking {
        val id = (ex.propose("x", listOf(step("a"), step("send"))) as ProposeResult.Created).execution.id
        assertEquals(ExecStates.WAITING_APPROVAL, ex.run(id).execution.status)
        assertEquals("unauthorized actions prevented", 0, a.calls + send.calls)

        ex.reject(id)
        assertEquals(ExecStates.CANCELLED, store.executions.getValue(id).status)
        assertEquals(0, ex.run(id).steps.count { it.status == StepStates.SUCCEEDED })

        // Revoked between steps: the step that already ran stays, the approval-needing one never runs.
        val id2 = proposeAndApprove(step("a", "label" to "2"), step("send", "label" to "2"))
        val slowA = object : PlanTool by a {
            override suspend fun execute(stepKey: String, args: Map<String, String>): ToolOutcome {
                val r = a.execute(stepKey, args); runBlocking { ex.revoke(id2) }; return r
            }
        }
        val ex2 = PlanExecutor(store, listOf(slowA, send), { now })
        val report = ex2.run(id2)
        assertEquals(ExecStates.CANCELLED, report.execution.status)
        assertEquals(0, send.calls)
    }

    @Test
    fun approvalIsBoundToTheExactStepsAndExpires() = runBlocking {
        val id = (ex.propose("x", listOf(step("a"), step("send"))) as ProposeResult.Created).execution.id
        // The plan is changed after it was shown: approval must not cover the new step.
        val s = store.steps(id).last()
        store.update(s.copy(argsJson = """{"label":"someone else"}"""))
        assertFalse(ex.approve(id))
        assertEquals(0, send.calls)

        val id2 = (ex.propose("y", listOf(step("a", "label" to "y"), step("send", "label" to "y"))) as ProposeResult.Created).execution.id
        assertTrue(ex.approve(id2))
        now += PlanExecutor.APPROVAL_TTL_MS + 1
        assertEquals("expired approval needs renewal", ExecStates.WAITING_APPROVAL, ex.run(id2).execution.status)
        assertEquals(0, send.calls)
        assertTrue(ex.approve(id2))
        assertEquals(ExecStates.SUCCEEDED, ex.run(id2).execution.status)
    }

    @Test
    fun aFailingMiddleStepStopsThePlanAndReportsPartialCompletion() = runBlocking {
        b.failTimes = 99
        val id = proposeAndApprove(step("a"), step("b"), step("c"))
        val report = ex.run(id)
        assertEquals(ExecStates.FAILED, report.execution.status)
        assertEquals(listOf(StepStates.SUCCEEDED, StepStates.FAILED, StepStates.SKIPPED), report.steps.map { it.status })
        assertEquals("retry-safe step retried up to the limit", PlanExecutor.MAX_ATTEMPTS, b.calls)
        assertEquals(0, c.calls)
        assertTrue(report.text.startsWith("Partly done"))
    }

    @Test
    fun verificationDecidesSuccessNotTheToolsOwnReply() = runBlocking {
        b.verifyAs = Verification.Status.NOT_MET
        val failed = ex.run(proposeAndApprove(step("a", "label" to "1"), step("b", "label" to "1")))
        assertEquals(ExecStates.FAILED, failed.execution.status)
        assertTrue(failed.steps[1].error!!.startsWith("expected outcome not observed"))

        b.verifyAs = Verification.Status.UNVERIFIABLE
        val unverified = ex.run(proposeAndApprove(step("a", "label" to "2"), step("b", "label" to "2"), step("c", "label" to "2")))
        assertEquals("unverifiable is never reported as verified success", ExecStates.NEEDS_ATTENTION, unverified.execution.status)
        assertEquals(StepStates.UNVERIFIED, unverified.steps[1].status)
        assertEquals("a step that can't be checked doesn't block the rest", StepStates.SUCCEEDED, unverified.steps[2].status)
        assertTrue(unverified.text.contains("couldn't confirm"))
    }

    @Test
    fun aNonIdempotentUncertainStepIsNeverRepeatedBlindly() = runBlocking {
        val timer = FakeTool("timer", retrySafe = false, uncertain = true)
        val e = PlanExecutor(store, listOf(a, timer), { now })
        val id = (e.propose("x", listOf(step("a"), step("timer"))) as ProposeResult.Created).execution.id
        e.approve(id)
        val report = e.run(id)
        assertEquals(1, timer.calls)
        assertEquals(StepStates.NEEDS_ATTENTION, report.steps[1].status)
        assertEquals(ExecStates.NEEDS_ATTENTION, report.execution.status)
        assertEquals("rerunning does not repeat it", 1, e.run(id).let { timer.calls })
    }

    @Test
    fun aTimedOutStepIsTreatedAsUncertain() = runBlocking {
        val slow = object : PlanTool by FakeTool("slow", retrySafe = false) {
            override suspend fun execute(stepKey: String, args: Map<String, String>): ToolOutcome { delay(5_000); return ToolOutcome(ToolOutcome.Status.DONE, "x") }
        }
        val e = PlanExecutor(store, listOf(slow), { now }, stepTimeoutMs = 50)
        val id = (e.propose("x", listOf(step("slow"))) as ProposeResult.Created).execution.id
        val report = e.run(id)
        assertEquals(StepStates.NEEDS_ATTENTION, report.steps.single().status)
        assertTrue(report.steps.single().error!!.contains("timed out"))
    }

    @Test
    fun duplicateRunsAndRepeatedCallsCreateEachEffectOnce() = runBlocking {
        val id = proposeAndApprove(step("a"), step("b"))
        ex.run(id); ex.run(id); ex.recover()
        assertEquals(1, a.totalEffects); assertEquals(1, b.totalEffects)
    }

    @Test
    fun cancellationBeforeOrBetweenStepsStopsFutureStepsAndUndoesScheduledWork() = runBlocking {
        val before = proposeAndApprove(step("a", "label" to "1"), step("b", "label" to "1"))
        assertTrue(ex.cancel(before))
        assertEquals(0, ex.run(before).steps.count { it.status == StepStates.SUCCEEDED })
        assertEquals(0, a.calls)

        val between = proposeAndApprove(step("a", "label" to "2"), step("b", "label" to "2"))
        val cancelling = object : PlanTool by a {
            override suspend fun execute(stepKey: String, args: Map<String, String>): ToolOutcome {
                val r = a.execute(stepKey, args); runBlocking { store.update(store.execution(between)!!.copy(status = ExecStates.CANCELLED)) }; return r
            }
        }
        PlanExecutor(store, listOf(cancelling, b), { now }).run(between)
        assertEquals(0, b.calls)
        // Undo of finished work on an explicit cancel:
        val third = proposeAndApprove(step("a", "label" to "3"), step("c", "label" to "3"))
        c.verifyAs = Verification.Status.UNVERIFIABLE
        ex.run(third)
        store.update(store.execution(third)!!.copy(status = ExecStates.RUNNING))
        assertTrue(ex.cancel(third))
        assertTrue(a.undone.isNotEmpty())
    }

    @Test
    fun afterACrashAnIdempotentStepIsReconciledNotRepeated() = runBlocking {
        a.crashAfterEffect = true
        val id = proposeAndApprove(step("a"), step("b"))
        try { ex.run(id) } catch (_: ProcessDeath) { }
        assertEquals(StepStates.RUNNING, store.steps(id).first().status)
        assertNull("the result was never recorded", store.steps(id).first().effectRef)
        // A new process (same store): reconcile finds the effect by the step's stable id.
        val reports = executor().recover()
        assertEquals(1, a.totalEffects)
        assertEquals(ExecStates.SUCCEEDED, reports.single().execution.status)
        assertEquals(1, b.totalEffects)
    }

    @Test
    fun afterACrashANonIdempotentStepNeedsTheUserInsteadOfRepeating() = runBlocking {
        val timer = FakeTool("timer", retrySafe = false, crashAfterEffect = true)
        val e = PlanExecutor(store, listOf(timer, b), { now })
        val id = (e.propose("x", listOf(step("timer"), step("b"))) as ProposeResult.Created).execution.id
        e.approve(id)
        try { e.run(id) } catch (_: ProcessDeath) { }
        val report = PlanExecutor(store, listOf(timer, b), { now }).recover().single()
        assertEquals("no duplicate timer", 1, timer.totalEffects)
        assertEquals(StepStates.NEEDS_ATTENTION, report.steps[0].status)
        assertEquals(StepStates.SKIPPED, report.steps[1].status)
        assertEquals(0, b.calls)
    }

    @Test
    fun afterACrashTheRestWaitsForRenewedApprovalIfItExpired() = runBlocking {
        a.crashAfterEffect = true
        val id = proposeAndApprove(step("a"), step("send"))
        try { ex.run(id) } catch (_: ProcessDeath) { }
        now += PlanExecutor.APPROVAL_TTL_MS + 1
        val report = executor().recover().single()
        assertEquals(ExecStates.WAITING_APPROVAL, report.execution.status)
        assertEquals(StepStates.SUCCEEDED, report.steps[0].status)
        assertEquals(0, send.calls)
    }

    @Test
    fun structuredArgumentsAreStoredExactlyEvenWhenTheyLookLikeCardNumbers() = runBlocking {
        // Regression: 13-digit epoch milliseconds pass the Luhn check about one time in ten and were stored as "[card]".
        val tasks = MemoryTaskStore(); val alarms = FakeTaskAlarms()
        val tool = ReminderPlanTool(TaskEngine(tasks, alarms, FakeTaskNotes(), { now }), tasks, alarms, { now })
        val e = PlanExecutor(store, listOf(tool), { now })
        var luhnLike = 0
        for (i in 0 until 200) {
            val at = 1_792_573_200_000L + i * 60_000L
            if (SensitiveData.isSecret(at.toString())) luhnLike++
            val id = (e.propose("t$i", listOf(step("reminder.schedule", "title" to "stretch $i", "at" to "$at", "zone" to "Asia/Kolkata"))) as ProposeResult.Created).execution.id
            assertEquals(at.toString(), PlanExecutor.args(store.steps(id).single())["at"])
        }
        assertTrue("the test covers timestamps the detector flags ($luhnLike)", luhnLike > 0)
    }

    @Test
    fun objectivesArgumentsResultsErrorsAndTracesAreSanitized() = runBlocking {
        val pin = "739204"
        b.failTimes = 99
        val result = ex.propose("pay rent, my PIN is $pin", listOf(step("a", "label" to "card PIN is $pin"), step("b")))
        val id = (result as ProposeResult.Created).execution.id
        ex.approve(id); ex.run(id)
        val all = store.executions.values.joinToString { it.toString() } + store.steps.values.joinToString { it.toString() } + traces.joinToString()
        assertFalse("a protected value was stored or traced", all.contains(pin))
        assertTrue(store.executions.getValue(id).objective.contains("[redacted]"))
    }
}
