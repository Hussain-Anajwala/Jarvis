package com.jarvis.eval

import com.jarvis.core.AdaptiveRouter
import com.jarvis.core.ClockCommandParser
import com.jarvis.core.EmailDialogue
import com.jarvis.core.GoalDialogue
import com.jarvis.core.MemoryRetriever
import com.jarvis.core.OnDeviceIntents
import com.jarvis.core.PlanParser
import com.jarvis.core.ReminderCommands
import com.jarvis.data.Memory
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Phase 9B efficiency measurements. Opt-in (`JARVIS_PERF=1`) because wall-clock timings differ on every run; writes
 * eval/reports/efficiency__phase9.md. Timing boundary: local JVM compute on the development PC only (no Android, no
 * network, no I/O), after warm-up — not phone latency. Cloud-request counts come from the stored, deterministic results.
 */
class EfficiencyBenchmarkTest {
    private val now = ZonedDateTime.of(2026, 10, 21, 14, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    private fun stats(samplesNs: List<Long>): String {
        val us = samplesNs.map { it / 1000.0 }.sorted()
        fun p(q: Double) = us[((us.size - 1) * q).toInt()]
        return "n = ${us.size}, median %.1f µs, p95 %.1f µs".format(p(0.5), p(0.95))
    }

    private fun time(repeats: Int, block: () -> Unit): List<Long> {
        repeat(200) { block() } // warm-up
        return (1..repeats).map { val t = System.nanoTime(); block(); System.nanoTime() - t }
    }

    @Test
    fun measure() = runBlocking {
        assumeTrue("set JARVIS_PERF=1 to measure", System.getenv("JARVIS_PERF") == "1")
        val texts = JSONObject(File("../eval/intents/intents.json").readText()).getJSONArray("items").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("text") } }

        // 1. The full on-device decision chain per utterance (what the app runs before any model).
        val classify = texts.flatMap { t -> time(20) { runBlocking { OnDeviceIntents.classify(t, now, null) } } }
        // 2. The ViewModel's "is this a new command?" check, which repeats several of the same parsers.
        val repeatCheck = texts.flatMap { t -> time(20) {
            ClockCommandParser.parse(t); EmailDialogue.start(t); ReminderCommands.parse(t); GoalDialogue.begin(t, now); PlanParser.parse(t, now)
        } }
        // 3. Router decision alone (the parsers it consults) for the same texts.
        val route = texts.flatMap { t -> time(20) { AdaptiveRouter.decide(t, null, emptyList(), true) } }
        // 4. Memory lookup: the 14-memory fixture, and 500 synthetic memories (deterministic sentences).
        val fixture = JSONObject(File("../eval/memory/memory_cases.json").readText())
        val ms = fixture.getJSONArray("memories").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Memory(o.getString("id"), o.getString("text"), null, "t", 1, null, true, o.getString("type"), o.getString("key"), 1) } }
        val queries = fixture.getJSONArray("queries").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("text") } }
        val subjects = listOf("college", "gym", "sister", "exam", "song", "office", "doctor", "train", "book", "coffee")
        val big = ms + (1..500).map { i -> Memory("s$i", "Note $i about ${subjects[i % 10]} number ${i * 7}", null, "t", i.toLong(), null, true, "fact", "", i.toLong()) }
        val small = queries.flatMap { q -> time(20) { MemoryRetriever.lookup(q, ms) } }
        val large = queries.flatMap { q -> time(5) { MemoryRetriever.lookup(q, big) } }

        // 5. Cloud requests avoided: intents answered on the phone vs sent to the router/cloud ("none"), stored results.
        fun noneCount(label: String) = File("../eval/results/intents__$label.jsonl").readLines().filter(String::isNotBlank).count { JSONObject(it).getString("got") == "none" }
        val total = texts.size

        val report = listOf(
            "# Efficiency measurements (Phase 9B)", "",
            "Timing boundary: local JVM compute on the development PC after warm-up; no Android runtime, no I/O, no network.",
            "Not phone latency (end-to-end phone latency was not measured). Re-run with JARVIS_PERF=1; numbers vary between runs.", "",
            "| Measurement | Result |", "|---|---|",
            "| On-device decision chain per utterance (OnDeviceIntents.classify, 55 texts × 20) | ${stats(classify)} |",
            "| ViewModel new-command check (5 parsers repeated) | ${stats(repeatCheck)} |",
            "| Router decision (parsers only) | ${stats(route)} |",
            "| Memory lookup, 14 memories (20 queries × 20) | ${stats(small)} |",
            "| Memory lookup, 514 memories (20 queries × 5) | ${stats(large)} |",
            "| Intent-set items sent to the router/cloud (\"none\"), baseline | ${noneCount("baseline")}/$total |",
            "| Intent-set items sent to the router/cloud (\"none\"), Phase 9 | ${noneCount("phase9")}/$total |",
            ""
        ).joinToString("\n")
        File("../eval/reports/efficiency__phase9.md").writeText(report)
        println(report)
    }
}
