package com.jarvis.core

import com.jarvis.data.AppEvent
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 8B: typed tool registry and the observed-event bus. */
class ToolRegistryTest {
    @Test
    fun everyToolHasMetadataAndUnknownOrBadlyTypedCallsFailSafely() {
        ReasoningToolSchema.all().forEach { assertTrue(it.name, ToolRegistry.spec(it.name) != null) }
        JarvisCore.AVAILABLE_TOOLS.forEach { assertTrue(it, ToolRegistry.spec(it) != null) }
        assertTrue("shell.exec", ToolRegistry.check("shell.exec", mapOf("cmd" to "rm -rf /")) is ToolCheck.Unknown)
        assertTrue("send_sms", ToolRegistry.check("send_sms", emptyMap()) is ToolCheck.Unknown)
        mapOf(
            "set_alarm" to mapOf("hour" to "25", "minute" to "0", "label" to ""),
            "set_volume" to mapOf("level" to "loud"),
            "flashlight" to mapOf("enabled" to "yes please"),
            "media_control" to mapOf("action" to "rewind"),
            "set_timer" to mapOf("duration_seconds" to "0"),
            "meeting.create" to mapOf("title" to "x", "hour" to "10", "minute" to "0", "location" to "", "date" to "tomorrow"),
            "play_music" to mapOf("query" to "Kesariya", "app" to "napster"),
            "get_weather" to mapOf("city" to "Mumbai")
        ).forEach { (tool, args) -> assertTrue("$tool $args", ToolRegistry.check(tool, args) is ToolCheck.Invalid) }
        ToolRegistry.check("set_alarm", mapOf("hour" to "7", "minute" to "30", "label" to "")).let { assertTrue("alarm $it", it is ToolCheck.Ok) }
        ToolRegistry.check("meeting.create", mapOf("title" to "x", "hour" to "10", "minute" to "0", "location" to "", "date" to "2026-10-22")).let { assertTrue("meeting $it", it is ToolCheck.Ok) }
        ToolRegistry.check("play_music", mapOf("query" to "Kesariya", "app" to "Spotify")).let { assertTrue("music $it", it is ToolCheck.Ok) }
    }

    @Test
    fun approvalMetadataAgreesWithTheExistingSafetyRules() {
        ToolRegistry.specs.filter { it.sideEffect == SideEffect.COMMUNICATION }.forEach {
            assertEquals("${it.id} can reach another person", Approval.CONFIRM_ON_SCREEN, it.approval)
        }
        // A medium/high-risk tool either needs a confirmation or has no side effect at all.
        ReasoningToolSchema.all().filter { it.riskLevel != "low" }.forEach { d ->
            val spec = ToolRegistry.spec(d.name)!!
            assertTrue(d.name, spec.approval != Approval.NONE || spec.sideEffect == SideEffect.NONE)
            assertTrue(d.name, ToolRiskGate.requiresConfirmation(d.name))
        }
        // Plan step metadata matches the real PlanTool classes.
        val tasks = MemoryTaskStore(); val alarms = FakeTaskAlarms()
        val engine = TaskEngine(tasks, alarms, FakeTaskNotes())
        listOf(ReminderPlanTool(engine, tasks, alarms), TimerPlanTool(FakeTimers { 0L }), CancelAllRemindersTool(engine, tasks, alarms)).forEach { tool ->
            val spec = ToolRegistry.spec(tool.name)!!
            assertEquals(tool.name, tool.retrySafe, spec.idempotent)
            assertEquals(tool.name, "plan", spec.origin)
            assertTrue(tool.name, tool.textArgs.all { a -> spec.args.any { it.name == a } })
        }
        assertTrue(ToolRegistry.spec("reminders.cancel_all")!!.approval == Approval.PLAN_APPROVAL)
    }

    @Test
    fun jarvisCoreChecksTheRegistryBeforeRoutingAnything() {
        val core = File("src/main/java/com/jarvis/core/JarvisCore.kt").readText()
        val check = core.indexOf("ToolRegistry.check(step.toolName")
        val route = core.indexOf("ToolRouter.route(step, availableTools)")
        assertTrue(check in 0 until route)
    }

    // ---- events ----

    private class Store : EventStore {
        val rows = linkedMapOf<String, AppEvent>()
        override suspend fun insertIfAbsent(event: AppEvent): Boolean { if (event.id in rows) return false; rows[event.id] = event; return true }
        override suspend fun unhandled() = rows.values.filter { it.handledAt == null }.sortedBy { it.createdAt }
        override suspend fun markHandled(id: String, at: Long, outcome: String): Boolean {
            val e = rows[id] ?: return false
            if (e.handledAt != null) return false
            rows[id] = e.copy(handledAt = at, outcome = outcome); return true
        }
    }

    private class Effects : EventEffects {
        val notified = mutableListOf<String>()
        val records = mutableListOf<String>()
        override fun notifyLocal(key: String, title: String, text: String) { notified += key }
        override suspend fun record(text: String) { records += text }
    }

    @Test
    fun duplicatesUnknownTypesAndUnsafePayloadsAreHandledSafely() = runBlocking {
        val store = Store(); val fx = Effects()
        var calls = 0
        val bus = EventBus(store, mapOf(EventTypes.PLAN_FINISHED to EventHandler { e, f -> calls++; f.notifyLocal("plan:${e.subjectId}", "t", "x"); "notified" }), fx)
        assertEquals(EmitResult.Handled("notified"), bus.emit(EventTypes.PLAN_FINISHED, "abc#1", "NEEDS_ATTENTION"))
        assertEquals(EmitResult.Duplicate, bus.emit(EventTypes.PLAN_FINISHED, "abc#1", "NEEDS_ATTENTION"))
        assertEquals("handled once", 1, calls)
        assertEquals(listOf("plan:abc#1"), fx.notified)
        assertTrue(bus.emit("send.message", "x", "ok") is EmitResult.Rejected)
        assertTrue(bus.emit(EventTypes.TASK_FAILED, "my PIN is 739204", "failed") is EmitResult.Rejected)
        assertTrue(bus.emit(EventTypes.TASK_FAILED, "t1", "OTP 552918") is EmitResult.Rejected)
        assertFalse(store.rows.values.joinToString().contains("739204"))
        assertEquals("no handler is fine, nothing happens", EmitResult.Handled("no handler"), bus.emit(EventTypes.TASK_MISSED, "t9", "missed"))
    }

    @Test
    fun anEventStoredBeforeACrashIsHandledOnceAfterRestartInOrder() = runBlocking {
        val store = Store(); val fx = Effects()
        // Stored but never handled (the process died in between).
        store.insertIfAbsent(AppEvent("${EventTypes.GOAL_COMPLETED}:g1:completed", EventTypes.GOAL_COMPLETED, "g1", "completed", 2, null, null))
        store.insertIfAbsent(AppEvent("${EventTypes.TASK_MISSED}:t1:missed", EventTypes.TASK_MISSED, "t1", "missed", 1, null, null))
        val order = mutableListOf<String>()
        val handler = EventHandler { e, _ -> order += e.type; "ok" }
        val bus = EventBus(store, mapOf(EventTypes.GOAL_COMPLETED to handler, EventTypes.TASK_MISSED to handler), fx)
        assertEquals(2, bus.recover())
        assertEquals(listOf(EventTypes.TASK_MISSED, EventTypes.GOAL_COMPLETED), order)
        assertEquals(0, bus.recover())
        // A failing handler is recorded as such and does not crash the caller.
        val failing = EventBus(store, mapOf(EventTypes.TASK_FAILED to EventHandler { _, _ -> error("boom") }), fx)
        assertEquals(EmitResult.Handled("handler failed (IllegalStateException)"), failing.emit(EventTypes.TASK_FAILED, "t2", "failed"))
    }

    @Test
    fun aCancelledSubjectGetsNoReactionAndHandlersCannotActBeyondTheirTwoEffects() = runBlocking {
        val store = Store(); val fx = Effects()
        var planStatus = "NEEDS_ATTENTION"
        val handler = EventHandler { e, f ->
            if (planStatus != e.status) return@EventHandler "plan changed since; nothing to do"
            f.notifyLocal("plan:${e.subjectId}", "t", "x"); "notified"
        }
        val bus = EventBus(store, mapOf(EventTypes.PLAN_FINISHED to handler), fx)
        planStatus = "CANCELLED" // the user cancelled before the handler ran
        assertEquals(EmitResult.Handled("plan changed since; nothing to do"), bus.emit(EventTypes.PLAN_FINISHED, "p1", "NEEDS_ATTENTION"))
        assertTrue(fx.notified.isEmpty())
        assertEquals(setOf("notifyLocal", "record"), EventEffects::class.java.declaredMethods.map { it.name }.toSet())
    }

    @Test
    fun theTaskEngineAndPlanExecutorEmitEachObservedChangeOnce() = runBlocking {
        val emitted = mutableListOf<String>()
        var now = 1_000_000L
        val tasks = MemoryTaskStore(); val alarms = FakeTaskAlarms()
        val engine = TaskEngine(tasks, alarms, FakeTaskNotes(), { now }, events = { t, s, st -> emitted += "$t:$s:$st" })
        val old = (engine.createReminder("old", now + 60_000, "Asia/Kolkata", null) as CreateResult.Scheduled).task.id
        alarms.armed.clear(); now += 60 * 60_000
        engine.recover(); engine.recover()
        assertEquals(listOf("${EventTypes.TASK_MISSED}:$old:missed"), emitted)

        val (goal, results) = engine.createGoal("Exam", null, listOf(TaskEngine.GoalStep(now + 60_000, "s1")), "Asia/Kolkata", null)
        engine.markDone((results.single() as CreateResult.Scheduled).task.id)
        assertTrue("${EventTypes.GOAL_COMPLETED}:${goal.id}:completed" in emitted)

        val finished = mutableListOf<String>()
        val store = MemoryExecutionStore()
        val ex = PlanExecutor(store, listOf(FakeTool("a"), FakeTool("b")), { now }, onFinished = { finished += it.status })
        val id = (ex.propose("x", listOf(ProposedStep("a", emptyMap()), ProposedStep("b", emptyMap()))) as ProposeResult.Created).execution.id
        ex.approve(id); ex.run(id); ex.run(id); ex.cancel(id)
        assertEquals(listOf(ExecStates.SUCCEEDED), finished)
    }
}
