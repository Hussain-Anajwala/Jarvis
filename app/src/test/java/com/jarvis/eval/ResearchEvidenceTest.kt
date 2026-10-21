package com.jarvis.eval

import com.jarvis.core.EvidenceParser
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Builds app/src/main/assets/research/evidence.json from the repository's real artifacts:
 *  - routing metrics computed from the eval/results JSONL files with the same scorer as the eval reports;
 *  - plan–execute–verify metrics computed from eval/results/pev__2026-10-21.jsonl;
 *  - hand-written entries (tests, device observations, estimates) from eval/research/static_evidence.json.
 * Normal runs check that the committed asset matches what the artifacts give (so the dashboard can't go stale silently);
 * `JARVIS_EVIDENCE_WRITE=1` rewrites it.
 */
class ResearchEvidenceTest {
    private val dataset = EvalDataset.load(File("../eval/utterances.json")).associateBy { it.id }
    private val asset = File("src/main/assets/research/evidence.json")

    private data class RoutingRun(
        val id: String, val title: String, val file: String, val sourceType: String, val runDate: String,
        val subset: String, val description: String, val notes: String?
    )

    private val runs = listOf(
        RoutingRun("routing-offline-2026-10-21", "Offline rule parser", "offline__offline-rule__11db5924f854__2026-10-21.jsonl", "RECORDED_BENCHMARK", "2026-10-21", "original",
            "Local rule parser only; no network.", null),
        RoutingRun("routing-fixed-cloud-2026-10-21", "Fixed cloud (Groq)", "groq__llama-3.3-70b-versatile__11db5924f854__2026-10-21.jsonl", "RECORDED_BENCHMARK", "2026-10-21", "original",
            "Every item to Groq (free tier).", "Rate limits sent part of the run to the fallback model gpt-oss-20b; not a clean single-model baseline."),
        RoutingRun("routing-hybrid-2026-10-21", "Hybrid (app before Phase 2)", "hybrid__local_llama-3.3-70b-versatile__11db5924f854__2026-10-21.jsonl", "RECORDED_BENCHMARK", "2026-10-21", "original",
            "Recognised phone commands on the local parser, everything else to Groq.", null),
        RoutingRun("routing-adaptive-replay-2026-10-21", "Adaptive router (replay)", "adaptive-replay__adaptive_replay__11db5924f854__2026-10-21.jsonl", "REPLAY", "2026-10-21", "original",
            "Adaptive routing; items it sends to the cloud reuse the hybrid run's recorded Groq answers.",
            "Not a new measurement. The gain over hybrid comes from 8 phase0 items the router was designed for (in-sample)."),
        RoutingRun("routing-offline-holdout-2026-10-21", "Offline rule parser — held-out items", "offline__offline-rule__11db5924f854__2026-10-21.jsonl", "RECORDED_BENCHMARK", "2026-10-21", "holdout",
            "The 23 items written before the adaptive router existed.", "Small sample."),
        RoutingRun("routing-adaptive-holdout-2026-10-21", "Adaptive router — held-out items (live)", "adaptive__adaptive_llama-3.3-70b-versatile__11db5924f854__2026-10-21.jsonl", "RECORDED_BENCHMARK", "2026-10-21", "holdout",
            "Live run on the 23 held-out items (Groq for cloud-routed items).", "Small sample (23); 23/23 has a 95% lower bound of about 86%. Fixed cloud and hybrid were not run on these items (quota cap)."),
        RoutingRun("routing-offline-2026-10-18", "Offline rule parser (earlier run)", "offline__offline-rule__11db5924f854.jsonl", "HISTORICAL", "2026-10-18", "original",
            "Earlier run on 126 items, same prompt.", "Historical: before Phases 0-6; not re-run."),
        RoutingRun("routing-fixed-cloud-2026-10-18", "Fixed cloud (Groq, earlier run)", "groq__llama-3.3-70b-versatile__11db5924f854.jsonl", "HISTORICAL", "2026-10-18", "original",
            "Earlier run on 126 items, same prompt.", "Historical: about half the answers came from gpt-oss-20b under rate limits."),
        RoutingRun("routing-hybrid-2026-10-18", "Hybrid (earlier run)", "hybrid__local_llama-3.3-70b-versatile__11db5924f854.jsonl", "HISTORICAL", "2026-10-18", "original",
            "Earlier run on 126 items, same prompt.", "Historical: before Phases 0-6; not re-run.")
    )

    private fun pct(n: Int, d: Int) = if (d == 0) null else Math.round(1000.0 * n / d) / 10.0

    private fun metric(id: String, label: String, value: Double?, unit: String, num: Int? = null, den: Int? = null, denLabel: String? = null, definition: String) =
        JSONObject().put("id", id).put("label", label).put("value", value ?: JSONObject.NULL).put("unit", unit)
            .put("numerator", num ?: JSONObject.NULL).put("denominator", den ?: JSONObject.NULL)
            .put("denominator_label", denLabel ?: JSONObject.NULL).put("definition", definition)

    private fun ratio(id: String, label: String, num: Int, den: Int, denLabel: String, definition: String) =
        metric(id, label, if (den == 0) null else Math.round(1000.0 * num / den) / 10.0, "percent", num, den, denLabel, definition)
            .also { if (den == 0) { it.put("numerator", JSONObject.NULL); it.put("denominator", JSONObject.NULL) } }

    private fun routing(run: RoutingRun): JSONObject {
        val file = File("../eval/results", run.file)
        require(file.exists()) { "missing ${run.file}" }
        val outcomes = EvalResultStore(file).load().values
            .filter { dataset[it.itemId]?.let { item -> (item.category == "holdout") == (run.subset == "holdout") } ?: error("${it.itemId} not in the dataset") }
        val scored = outcomes.map { dataset.getValue(it.itemId) to EvalScorer.score(dataset.getValue(it.itemId), it) }
        val tool = scored.filter { it.first.expected.action == "tool_call" }
        val clarify = scored.filter { it.first.expected.action == "clarify" }
        val latencies = outcomes.filter { it.failure == null }.map { it.latencyMs }.sorted()
        val cloud = outcomes.count { it.model.startsWith("groq/") || it.model.startsWith("replay/groq/") || it.model.startsWith("gemini") }
        val metrics = JSONArray()
            .put(ratio("tool_accuracy", "Tool-selection accuracy", scored.count { it.second.decisionCorrect }, scored.size, "items",
                "Correct decision (right tool, a clarifying question, or no tool, as labelled) / items scored."))
            .put(ratio("arg_accuracy", "Argument accuracy", tool.count { it.second.argsCorrect == true }, tool.size, "tool-call items",
                "Right tool with all labelled arguments matching / items labelled as tool calls."))
            .put(ratio("clarify_rate", "Clarification rate", clarify.count { it.second.clarified }, clarify.size, "items labelled clarify",
                "Items answered with a question and no tool / items labelled clarify."))
            .put(ratio("false_clarify_rate", "False clarification rate", tool.count { it.second.clarified }, tool.size, "tool-call items",
                "Tool-call items answered with a question instead / items labelled as tool calls."))
            .put(metric("cloud_requests", if (run.sourceType == "REPLAY") "Cloud-routed items (replayed answers)" else "Cloud model answers", cloud.toDouble(), "count",
                definition = "Items answered by a cloud model (Groq or Gemini), counted from each item's recorded model."))
            .put(metric("latency_p50", "Latency p50", EvalMetricsCalculator.percentile(latencies, 0.5).takeIf { latencies.isNotEmpty() }?.toDouble(), "ms",
                definition = "Median per-item latency over items that did not fail" + if (run.sourceType == "REPLAY") "; cloud items use the hybrid run's recorded latency." else "."))
            .put(metric("latency_p95", "Latency p95", EvalMetricsCalculator.percentile(latencies, 0.95).takeIf { latencies.isNotEmpty() }?.toDouble(), "ms",
                definition = "95th-percentile per-item latency over items that did not fail."))
            .put(metric("failures", "Items failed", outcomes.count { it.failure != null }.toDouble(), "count", definition = "Items where the engine gave no answer (a cloud failure counts here, not as right or wrong)."))
            .put(metric("rate_limited", "HTTP 429 responses", outcomes.sumOf { it.rateLimited }.toDouble(), "count", definition = "Rate-limit responses seen across all items (recorded since 2026-10-21 runs; earlier runs did not record this)."))
        return JSONObject().put("id", run.id).put("title", run.title).put("description", run.description).put("category", "routing")
            .put("source_type", run.sourceType).put("sources", JSONArray(listOf("eval/results/${run.file}", "eval/utterances.json")))
            .put("run_date", run.runDate).put("sample_size", outcomes.size).put("status", "measured").put("notes", run.notes ?: JSONObject.NULL)
            .put("metrics", metrics)
    }

    private fun pev(): JSONObject {
        val rows = File("../eval/results/pev__2026-10-21.jsonl").readLines().filter(String::isNotBlank).map(::JSONObject)
        require(rows.isNotEmpty()) { "empty PEV results" }
        fun b(o: JSONObject, k: String): Boolean? = if (o.isNull(k)) null else o.getBoolean(k)
        val plans = rows.filter { b(it, "stepsOk") != null }
        val ran = rows.filter { b(it, "orderOk") != null }
        val succeedExpected = rows.filter { it.optString("expected") == "SUCCEEDED" }
        val recovery = rows.filter { b(it, "recovery") != null }
        val guarded = rows.filter { it.getBoolean("guarded") }
        val metrics = JSONArray()
            .put(ratio("final_state_match", "Final state as expected", rows.count { it.opt("expected").toString() == it.opt("actual").toString() }, rows.size, "scenarios",
                "Scenarios whose final execution state equals the state written down before the first run."))
            .put(ratio("plan_validity", "Plan validity", plans.count { b(it, "stepsOk") == true }, plans.size, "scenarios with a parse outcome",
                "Parsed step tools equal the expected list, or the text is correctly not a plan."))
            .put(ratio("step_order", "Steps executed in plan order", ran.count { b(it, "orderOk") == true }, ran.size, "scenarios that ran steps",
                "The executed steps are a prefix of the planned order."))
            .put(ratio("verified_success", "Verified success where success was expected", succeedExpected.count { it.optString("actual") == "SUCCEEDED" }, succeedExpected.size, "scenarios expected to succeed",
                "Runs that ended SUCCEEDED (every step's postcondition observed)."))
            .put(ratio("steps_verified", "Executed steps verified", rows.sumOf { it.getInt("verified") }, rows.sumOf { it.getInt("executed") }, "executed steps",
                "Steps whose postcondition was observed / steps that were attempted. Failed, unverifiable, and uncertain steps are in the denominator on purpose."))
            .put(metric("unauthorized_effects", "Unauthorized effects", rows.sumOf { it.getInt("unauthorized") }.toDouble(), "count",
                definition = "Effects created without valid approval, in ${guarded.size} scenarios built to test it (denied, never answered, revoked, expired)."))
            .put(metric("duplicate_effects", "Duplicate effects", rows.sumOf { it.getInt("duplicates") }.toDouble(), "count",
                definition = "Effects beyond one per executed step (a reminder or timer created twice)."))
            .put(ratio("recovery", "Crash recovery reached the expected state", recovery.count { b(it, "recovery") == true }, recovery.size, "crash scenarios",
                "After a simulated crash right after a side effect, a new executor ends in the expected state without repeating the effect."))
        return JSONObject().put("id", "pev-2026-10-21").put("title", "Plan–execute–verify evaluation")
            .put("description", "18 scenarios: multi-step requests, approval denied/unanswered/revoked/expired, tool failures, failed and unverifiable postconditions, crashes, non-plans, a missing time.")
            .put("category", "pev").put("source_type", "SIMULATED").put("sources", JSONArray(listOf("eval/results/pev__2026-10-21.jsonl", "eval/pev_scenarios.json")))
            .put("run_date", "2026-10-21").put("sample_size", rows.size).put("status", "measured")
            .put("notes", "Real parser, executor, and tool classes over in-memory reminder/timer infrastructure with injected faults; not a phone measurement. Small hand-written set: no statistical claim.")
            .put("metrics", metrics)
    }

    private fun build(commit: String?, date: String?): JSONObject {
        val static = JSONObject(File("../eval/research/static_evidence.json").readText()).getJSONArray("experiments")
        val experiments = JSONArray()
        runs.forEach { experiments.put(routing(it)) }
        experiments.put(pev())
        for (i in 0 until static.length()) experiments.put(static.getJSONObject(i))
        return JSONObject().put("schema", "jarvis.research.evidence").put("schema_version", 1)
            .put("generated_from_commit", commit ?: JSONObject.NULL).put("generated_on", date ?: JSONObject.NULL)
            .put("experiments", experiments)
    }

    private fun gitCommit(): String? = runCatching {
        ProcessBuilder("git", "rev-parse", "--short", "HEAD").directory(File("..")).start().inputStream.bufferedReader().readText().trim().ifBlank { null }
    }.getOrNull()

    @Test
    fun theDashboardAssetMatchesTheRepositoryArtifacts() {
        if (System.getenv("JARVIS_EVIDENCE_WRITE") == "1") {
            asset.parentFile.mkdirs()
            asset.writeText(build(gitCommit(), java.time.LocalDate.now().toString()).toString(2) + "\n")
        }
        val committed = JSONObject(asset.readText())
        val fresh = build(committed.optString("generated_from_commit").ifBlank { null }, committed.optString("generated_on").ifBlank { null })
        assertEquals("evidence.json is stale: run with JARVIS_EVIDENCE_WRITE=1", fresh.toString(2), committed.toString(2))
        val parsed = EvidenceParser.parse(asset.readText())
        assertTrue(parsed.experiments.size >= runs.size + 1)
    }
}
