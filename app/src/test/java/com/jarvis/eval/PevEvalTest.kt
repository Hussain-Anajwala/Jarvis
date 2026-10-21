package com.jarvis.eval

import com.jarvis.core.CancelAllRemindersTool
import com.jarvis.core.ExecStates
import com.jarvis.core.FakeTaskAlarms
import com.jarvis.core.FakeTaskNotes
import com.jarvis.core.FakeTimers
import com.jarvis.core.Faulty
import com.jarvis.core.MemoryExecutionStore
import com.jarvis.core.MemoryTaskStore
import com.jarvis.core.PlanExecutor
import com.jarvis.core.PlanParser
import com.jarvis.core.ProcessDeath
import com.jarvis.core.ProposeResult
import com.jarvis.core.ReminderPlanTool
import com.jarvis.core.StepStates
import com.jarvis.core.TaskEngine
import com.jarvis.core.TimerPlanTool
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 6 plan–execute–verify evaluation (docs/EVAL.md). Real parser, executor, and tool classes over in-memory reminder
 * and timer infrastructure with injected faults; no Android, no network, no real alarms. Deterministic: same inputs →
 * same report. Writes eval/reports/pev__<run>.md. Safety invariants are asserted; outcome accuracy is only reported.
 */
class PevEvalTest {
    private val start = ZonedDateTime.of(2026, 10, 21, 14, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
    /** Scenarios that check approval enforcement (denied, never answered, revoked, expired). */
    private val GUARDED = setOf("pev-05", "pev-06", "pev-07", "pev-08")

    private data class Row(val id: String, val expected: String?, val actual: String?, val stepsOk: Boolean?, val orderOk: Boolean?,
                           val unauthorized: Int, val duplicates: Int, val verified: Int, val executed: Int, val recovery: Boolean?, val overheadMs: Long)

    @Test
    fun runScenarios() = runBlocking {
        val file = File("../eval/pev_scenarios.json")
        val scenarios = JSONObject(file.readText()).getJSONArray("scenarios")
        val rows = (0 until scenarios.length()).map { run(scenarios.getJSONObject(it)) }
        val report = report(rows)
        File("../eval/reports").mkdirs()
        File("../eval/reports/pev__2026-10-21.md").writeText(report)
        // Structured rows for the research dashboard (Phase 7); same numbers as the report.
        File("../eval/results/pev__2026-10-21.jsonl").writeText(rows.joinToString("") { r ->
            JSONObject().put("id", r.id).put("expected", r.expected ?: JSONObject.NULL).put("actual", r.actual ?: JSONObject.NULL)
                .put("stepsOk", r.stepsOk ?: JSONObject.NULL).put("orderOk", r.orderOk ?: JSONObject.NULL)
                .put("unauthorized", r.unauthorized).put("duplicates", r.duplicates).put("verified", r.verified).put("executed", r.executed)
                .put("recovery", r.recovery ?: JSONObject.NULL).put("guarded", r.id in GUARDED).toString() + "\n"
        })
        println(report)
        val overheads = rows.filter { it.orderOk != null }.map { it.overheadMs }.sorted()
        println("Orchestration overhead this run: median ${overheads.getOrNull(overheads.size / 2)} ms, max ${overheads.lastOrNull()} ms")
        assertEquals("unauthorized effects", 0, rows.sumOf { it.unauthorized })
        assertEquals("duplicate effects", 0, rows.sumOf { it.duplicates })
    }

    private suspend fun run(s: JSONObject): Row {
        var now = start.toInstant().toEpochMilli()
        val clock = { now }
        val tasks = MemoryTaskStore(); val alarms = FakeTaskAlarms(); val timers = FakeTimers(clock)
        val engine = TaskEngine(tasks, alarms, FakeTaskNotes(), clock)
        val faults = s.optJSONObject("fault")
        val reminder = Faulty(ReminderPlanTool(engine, tasks, alarms, clock), faults?.optString("reminder.schedule")?.ifBlank { null }) { ref -> ref?.let(alarms::cancel) }
        val timer = Faulty(TimerPlanTool(timers), faults?.optString("timer.start")?.ifBlank { null }) { ref -> ref?.toIntOrNull()?.let { timers.armed -= it } }
        val cancelAll = Faulty(CancelAllRemindersTool(engine, tasks, alarms), faults?.optString("reminders.cancel_all")?.ifBlank { null })
        val store = MemoryExecutionStore()
        fun executor() = PlanExecutor(store, listOf(reminder, timer, cancelAll), clock)
        repeat(s.optInt("seed_reminders")) { engine.createReminder("seed $it", now + 3_600_000L * (it + 1), "Asia/Kolkata", null) }
        val seedIds = tasks.tasks.keys.toSet()

        val id = s.getString("id")
        val expectedSteps = s.optJSONArray("expected_steps")?.let { a -> (0 until a.length()).map(a::getString) }
        val expected = if (s.isNull("expected_status")) null else s.getString("expected_status")
        val parsed = PlanParser.parse(s.getString("text"), start)
        if (parsed == null) return Row(id, expected, null, expectedSteps == null, null, 0, 0, 0, 0, null, 0)
        if (parsed.missing != null) return Row(id, expected, "ASK_MISSING", null, null, 0, 0, 0, 0, null, 0)
        val stepsOk = parsed.steps.map { it.tool } == expectedSteps

        val ex = executor()
        val created = ex.propose(s.getString("text"), parsed.steps) as ProposeResult.Created
        val eid = created.execution.id
        val t0 = System.nanoTime()
        var crashed = false
        when (s.getString("approval")) {
            "yes" -> { ex.approve(eid); try { ex.run(eid) } catch (_: ProcessDeath) { crashed = true } }
            "no" -> ex.reject(eid)
            "none" -> ex.run(eid)
            "expire" -> { ex.approve(eid); now += PlanExecutor.APPROVAL_TTL_MS + 1; ex.run(eid) }
            "revoke_after_1" -> {
                ex.approve(eid)
                val revoking = object : com.jarvis.core.PlanTool by reminder {
                    override suspend fun execute(stepKey: String, args: Map<String, String>) =
                        reminder.execute(stepKey, args).also { ex.revoke(eid) }
                }
                PlanExecutor(store, listOf(revoking, timer, cancelAll), clock).run(eid)
            }
        }
        if (crashed) executor().recover() // a new process over the same records
        val overhead = (System.nanoTime() - t0) / 1_000_000
        val steps = store.steps(eid)
        val final = store.execution(eid)!!.status

        // Unauthorized: any effect without a valid approval (denied, never answered, expired, or after revocation).
        val approvalOk = s.getString("approval") == "yes"
        val reminderEffects = tasks.tasks.keys.count { it !in seedIds }
        val cancelledSeeds = seedIds.count { tasks.tasks.getValue(it).status == com.jarvis.core.TaskStates.CANCELLED }
        val unauthorized = if (s.optBoolean("unauthorized_check") && !approvalOk) {
            when (s.getString("approval")) {
                "revoke_after_1" -> cancelledSeeds + timers.timers.size // only the first (approved) step may have run
                else -> reminderEffects + timers.timers.size + cancelledSeeds
            }
        } else 0
        // Duplicates: more effects than steps that ran them.
        val reminderSteps = steps.count { it.tool == "reminder.schedule" && it.attempts > 0 }
        val timerSteps = steps.count { it.tool == "timer.start" && it.attempts > 0 }
        val duplicates = maxOf(0, reminderEffects - reminderSteps) + maxOf(0, timers.timers.size - timerSteps)
        val executedOrder = steps.filter { it.attempts > 0 }.map { it.tool }
        val orderOk = executedOrder == parsed.steps.map { it.tool }.take(executedOrder.size)
        return Row(id, expected, final, stepsOk, orderOk, unauthorized, duplicates,
            steps.count { it.status == StepStates.SUCCEEDED }, steps.count { it.attempts > 0 },
            if (s.optBoolean("recovery_check")) final == expected else null, overhead)
    }

    private fun pct(n: Int, d: Int) = if (d == 0) "n/a" else "%.0f%% (%d/%d)".format(100.0 * n / d, n, d)

    private fun report(rows: List<Row>): String {
        val plans = rows.filter { it.stepsOk != null }
        val ran = rows.filter { it.orderOk != null }
        val succeedExpected = rows.filter { it.expected == ExecStates.SUCCEEDED }
        val guarded = rows.filter { it.id in GUARDED }
        val recovery = rows.filter { it.recovery != null }
        return listOf(
            "# Plan–execute–verify evaluation (Phase 6)",
            "",
            "- Scenarios: ${rows.size} (eval/pev_scenarios.json v1, expectations written before the first run)",
            "- Real PlanParser, PlanExecutor, ReminderPlanTool, TimerPlanTool, CancelAllRemindersTool; in-memory reminder/timer",
            "  infrastructure and injected faults. JVM only — not a phone measurement.",
            "",
            "| Metric | Result |",
            "|---|---|",
            "| Final state matches expectation | ${pct(rows.count { it.expected == it.actual }, rows.size)} |",
            "| Plan validity (parsed steps match, or correctly not a plan) | ${pct(plans.count { it.stepsOk == true }, plans.size)} |",
            "| Correct step order (executed steps follow the plan) | ${pct(ran.count { it.orderOk == true }, ran.size)} |",
            "| Completed and verified, where success was expected | ${pct(succeedExpected.count { it.actual == ExecStates.SUCCEEDED }, succeedExpected.size)} |",
            "| Executed steps verified | ${pct(rows.sumOf { it.verified }, rows.sumOf { it.executed })} |",
            "| Unauthorized effects (denied / unanswered / revoked / expired) | ${rows.sumOf { it.unauthorized }} across ${guarded.size} scenarios |",
            "| Duplicate effects | ${rows.sumOf { it.duplicates }} |",
            "| Recovery after a simulated crash ends in the expected state | ${pct(recovery.count { it.recovery == true }, recovery.size)} |",
            // Wall-clock timing differs on every run, so it is printed to the console only (the report stays deterministic).
            "| Orchestration overhead per plan (fake tools, JVM) | timing-dependent; not in this report (see eval/research/static_evidence.json) |",
            "",
            "| Scenario | Expected | Actual | Steps executed | Verified |",
            "|---|---|---|---|---|",
            *rows.map { "| ${it.id} | ${it.expected ?: "not a plan"} | ${it.actual ?: "not a plan"} | ${it.executed} | ${it.verified} |" }.toTypedArray(),
            ""
        ).joinToString("\n")
    }
}
