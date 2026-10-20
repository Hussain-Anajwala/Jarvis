package com.jarvis.eval

import com.jarvis.core.JarvisCore
import com.jarvis.core.PlanStep
import com.jarvis.core.ReasoningProvider
import com.jarvis.core.ReasoningRequest
import com.jarvis.core.ReasoningResponse
import com.jarvis.core.ReasoningToolSchema
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalHarnessTest {
    private val datasetFile = File("../eval/utterances.json")

    @Test
    fun datasetIsLargeLabelledAndUsesOnlyRegisteredToolsAndArguments() {
        val items = EvalDataset.load(datasetFile)
        assertTrue("need >= 100 items, have ${items.size}", items.size >= 100)
        assertEquals(items.size, items.map { it.id }.toSet().size)
        val categories = items.groupingBy { it.category }.eachCount()
        for (required in listOf("create", "cancel", "incomplete", "multi_turn", "phone", "unsupported", "hinglish", "messages")) {
            assertTrue("missing category $required", (categories[required] ?: 0) > 0)
        }
        assertTrue(categories.getValue("create") >= 25 && categories.getValue("incomplete") >= 20 && categories.getValue("phone") >= 20)
        for (item in items) {
            assertTrue(item.id, item.expected.action in setOf("tool_call", "clarify", "no_tool"))
            assertEquals(item.id, "user", item.turns.last().role)
            if (item.expected.action == "tool_call") {
                val tool = item.expected.tool!!
                assertTrue("${item.id}: $tool not offered", tool in JarvisCore.AVAILABLE_TOOLS)
                val fields = ReasoningToolSchema.definition(tool)!!.allFields
                assertTrue("${item.id}: bad arg keys", item.expected.args.keys.all { it in fields })
            }
        }
    }

    private fun item(action: String, tool: String? = null, args: Map<String, String> = emptyMap(), id: String = "x") =
        EvalItem(id, "create", listOf(EvalTurn("user", "u")), EvalExpected(action, tool, args))

    private fun outcome(tool: String?, args: Map<String, String> = emptyMap(), reply: String = "", failure: String? = null, id: String = "x", latency: Long = 10) =
        EvalOutcome(id, "fake", "fake/m", tool, args, reply, latency, 0, failure, "2026-10-17")

    @Test
    fun scorerChecksToolArgsDatesClarificationAndNoTool() {
        val meeting = item("tool_call", "meeting.create", mapOf("hour" to "10", "location" to "college", "date" to "tomorrow", "title" to "dentist"))
        val good = outcome("meeting.create", mapOf("hour" to "10", "minute" to "0", "location" to "the College", "date" to "2026-10-18", "title" to "Dentist visit"))
        assertEquals(ItemScore(true, true, false, false), EvalScorer.score(meeting, good))
        val wrongDate = good.copy(args = good.args + ("date" to "2026-10-19"))
        assertEquals(false, EvalScorer.score(meeting, wrongDate).argsCorrect)
        // Saturday 2026-10-17: "friday" means 2026-10-23.
        assertTrue(EvalScorer.argsMatch(mapOf("date" to "friday"), mapOf("date" to "2026-10-23"), LocalDate.parse("2026-10-17")))
        assertTrue(EvalScorer.argsMatch(mapOf("duration_seconds" to "600"), mapOf("duration_seconds" to "600.0"), LocalDate.now()))

        val clarify = item("clarify")
        assertTrue(EvalScorer.score(clarify, outcome(null, reply = "What time is the meeting?")).decisionCorrect)
        assertFalse(EvalScorer.score(clarify, outcome(null, reply = "Done.")).decisionCorrect)
        assertFalse(EvalScorer.score(clarify, outcome("meeting.create")).decisionCorrect)

        val noTool = item("no_tool")
        assertTrue(EvalScorer.score(noTool, outcome(null, reply = "I can't order food.")).decisionCorrect)
        assertFalse(EvalScorer.score(noTool, outcome("calendar.cancel")).decisionCorrect)

        assertTrue(EvalScorer.score(meeting, outcome(null, failure = "groq: HTTP 403")).failed)
    }

    @Test
    fun metricsComputeAccuracyFalseClarificationAndPercentiles() {
        val items = listOf(
            item("tool_call", "set_timer", mapOf("duration_seconds" to "60"), id = "a"),
            item("tool_call", "set_timer", mapOf("duration_seconds" to "60"), id = "b"),
            item("clarify", id = "c"),
            item("no_tool", id = "d")
        )
        val outcomes = listOf(
            outcome("set_timer", mapOf("duration_seconds" to "60"), id = "a", latency = 100),
            outcome(null, reply = "How long?", id = "b", latency = 200),
            outcome(null, reply = "When?", id = "c", latency = 300),
            outcome(null, reply = "Hi", id = "d", latency = 400)
        )
        val m = EvalMetricsCalculator.compute(items, outcomes)
        assertEquals(0.75, m.toolAccuracy, 1e-9)
        assertEquals(0.5, m.argAccuracy, 1e-9)
        assertEquals(1.0, m.clarifyRate, 1e-9)
        assertEquals(0.5, m.falseClarifyRate, 1e-9)
        assertEquals(300L, m.p50Ms)
        assertEquals(400L, m.p95Ms)
    }

    private class CountingProvider : ReasoningProvider {
        val utterances = mutableListOf<String>()
        override suspend fun reason(request: ReasoningRequest): ReasoningResponse {
            utterances += request.utterance
            return ReasoningResponse("ok", listOf(PlanStep("set_timer", mapOf("duration_seconds" to "60"))), engineName = "fake", modelName = "m")
        }
    }

    @Test
    fun runnerIsResumableThrottledAndNeverExecutesTools() = runBlocking {
        val dir = Files.createTempDirectory("eval").toFile()
        try {
            val store = EvalResultStore(File(dir, "run.jsonl"))
            val items = (1..3).map { item("tool_call", "set_timer", mapOf("duration_seconds" to "60"), id = "i$it") }
            var now = 0L
            val sleeps = mutableListOf<Long>()
            val provider = CountingProvider()
            val runner = EvalRunner("fake", "m", provider, store, throttleMs = 1_000, clock = { now }, sleeper = { sleeps += it; now += it }, log = {})
            runner.run(items.take(2))
            assertEquals(2, provider.utterances.size)
            assertEquals(listOf(1_000L), sleeps)
            // A second run reuses stored results and only calls the provider for the missing item.
            val all = runner.run(items)
            assertEquals(3, provider.utterances.size)
            assertEquals(3, all.size)
            assertEquals(3, store.load().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rateLimitsPauseThenStopTheRunAndKeepFinishedItems() = runBlocking {
        val dir = Files.createTempDirectory("eval").toFile()
        try {
            val store = EvalResultStore(File(dir, "run.jsonl"))
            val items = (1..6).map { item("tool_call", "set_timer", mapOf("duration_seconds" to "60"), id = "i$it") }
            var calls = 0
            val provider = object : ReasoningProvider {
                override suspend fun reason(request: ReasoningRequest): ReasoningResponse {
                    calls++
                    // The first item answers; every later call is rate limited after the provider's own retries.
                    return if (calls == 1) ReasoningResponse("ok", listOf(PlanStep("set_timer", mapOf("duration_seconds" to "60"))), engineName = "fake", modelName = "m")
                    else ReasoningResponse("offline", engineName = "fake", failureReason = "HTTP 429",
                        attempts = List(3) { com.jarvis.core.ModelAttempt("m", 429, providerName = "fake") })
                }
            }
            val sleeps = mutableListOf<Long>()
            var now = 0L
            val runner = EvalRunner("fake", "m", provider, store, throttleMs = 0, clock = { now }, sleeper = { sleeps += it; now += it }, log = {},
                maxConsecutiveRateLimited = 3, rateLimitPauseMs = 1_000)
            val outcomes = runner.run(items)
            assertEquals("stops after three rate-limited items, no endless retry", 4, calls)
            assertEquals(listOf(1_000L, 2_000L), sleeps)
            assertEquals(listOf("i5", "i6"), runner.notRun)
            assertTrue(runner.stopReason!!.contains("rate-limited"))
            assertEquals(4, store.load().size)
            assertEquals(9, outcomes.sumOf { it.rateLimited })
            val report = EvalReport.markdown("fake", setOf("m"), items, outcomes, mapOf("Dataset" to "test"), runner.notRun, runner.stopReason)
            assertTrue(report.contains("not run: 2"))
            assertTrue(report.contains("Rate-limit (HTTP 429) responses: 9"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cacheKeyAndSubsetSelectionAreStable() {
        assertEquals(EvalCacheKey.promptHash(), EvalCacheKey.promptHash())
        assertTrue(EvalCacheKey.fileName("groq", "openai/gpt-oss-120b").startsWith("groq__openai_gpt-oss-120b__"))
        val items = EvalDataset.load(datasetFile)
        val subset = EvalDataset.select(items, null, null, perCategory = 2, limit = null)
        assertEquals(items.map { it.category }.toSet().size * 2, subset.size)
        assertEquals(listOf("cre-001"), EvalDataset.select(items, setOf("cre-001"), null, null, null).map { it.id })
    }
}
