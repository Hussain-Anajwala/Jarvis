package com.jarvis.eval

import com.jarvis.BuildConfig
import com.jarvis.core.GeminiReasoningProvider
import com.jarvis.core.GroqReasoningProvider
import com.jarvis.core.OfflineRuleProvider
import com.jarvis.core.PrivacyGuardProvider
import com.jarvis.core.ReasoningProvider
import com.jarvis.core.ReasoningRequest
import com.jarvis.core.ReasoningResponse
import com.jarvis.core.AdaptiveRouter
import com.jarvis.core.RouteTarget
import com.jarvis.core.ToolRouter
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the evaluation against a real engine. Skipped unless JARVIS_EVAL_ENGINE is set, so normal
 * `testDebugUnitTest` runs never call a network API. Example (Git Bash):
 *   JARVIS_EVAL_ENGINE=groq JARVIS_EVAL_THROTTLE_MS=2500 gradle :app:testDebugUnitTest --tests com.jarvis.eval.LiveEvalTest
 * Variables: JARVIS_EVAL_ENGINE = offline | groq | gemini | gemini2 | hybrid (local phone parser first, then Groq,
 * as the app routes requests); JARVIS_EVAL_PER_CATEGORY, JARVIS_EVAL_LIMIT, JARVIS_EVAL_IDS (comma list),
 * JARVIS_EVAL_CATEGORIES, JARVIS_EVAL_THROTTLE_MS. Results: JSONL files in eval/results; reports in eval/reports.
 * API keys come from BuildConfig (local.properties) and are never written to results.
 */
class LiveEvalTest {
    @Test
    fun runConfiguredEvaluation() = runBlocking {
        val engine = System.getenv("JARVIS_EVAL_ENGINE")?.trim()?.lowercase()
        assumeTrue("JARVIS_EVAL_ENGINE not set; live evaluation skipped", !engine.isNullOrBlank())
        val datasetFile = File("../eval/utterances.json")
        val all = EvalDataset.load(datasetFile)
        val replayFile = System.getenv("JARVIS_EVAL_REPLAY")?.let { File("../eval/results", it) }
        val recorded = replayFile?.let { EvalResultStore(it).load() }.orEmpty()
        val byUtterance = all.filter { it.id in recorded }.associateBy { it.turns.last().text to it.turns.dropLast(1).map(EvalTurn::text) }
        val replay = replayFile?.let {
            ReplayProvider(recorded) { request -> byUtterance[request.utterance to request.history.map { turn -> turn.text }]?.id }
        }
        val (provider, configuredModel, defaultThrottle) = when (engine) {
            "offline" -> Triple(OfflineRuleProvider(), "offline-rule", 0L)
            "groq" -> Triple(groq(), BuildConfig.GROQ_MODEL, 2_500L)
            "gemini" -> Triple(gemini(BuildConfig.GEMINI_API_KEY, "gemini-primary"), BuildConfig.GEMINI_MODEL, 16_000L)
            "gemini2" -> Triple(gemini(BuildConfig.GEMINI_API_KEY_2, "gemini-secondary"), BuildConfig.GEMINI_MODEL, 16_000L)
            "hybrid" -> Triple(HybridProvider(groq()), "local+" + BuildConfig.GROQ_MODEL, 2_500L)
            "adaptive" -> Triple(AdaptiveProvider(groq()), "adaptive+" + BuildConfig.GROQ_MODEL, 2_500L)
            // Adaptive routing over a recorded fixed-cloud run (JARVIS_EVAL_REPLAY = its results file): no API calls.
            "adaptive-replay" -> Triple(AdaptiveProvider(replay!!), "adaptive+replay", 0L)
            else -> error("Unknown JARVIS_EVAL_ENGINE")
        }
        assumeTrue("$engine key not configured", engine == "offline" || engine == "adaptive-replay" || when (engine) {
            "gemini" -> BuildConfig.GEMINI_API_KEY.isNotBlank()
            "gemini2" -> BuildConfig.GEMINI_API_KEY_2.isNotBlank()
            else -> BuildConfig.GROQ_API_KEY.isNotBlank()
        })
        val items = EvalDataset.select(
            all,
            System.getenv("JARVIS_EVAL_IDS")?.split(',')?.map(String::trim)?.filter(String::isNotBlank)?.toSet(),
            System.getenv("JARVIS_EVAL_CATEGORIES")?.split(',')?.map(String::trim)?.filter(String::isNotBlank)?.toSet(),
            System.getenv("JARVIS_EVAL_PER_CATEGORY")?.toIntOrNull(),
            System.getenv("JARVIS_EVAL_LIMIT")?.toIntOrNull()
        )
        val throttle = System.getenv("JARVIS_EVAL_THROTTLE_MS")?.toLongOrNull() ?: defaultThrottle
        val runTag = System.getenv("JARVIS_EVAL_RUN")
        val store = EvalResultStore(File("../eval/results", EvalCacheKey.fileName(engine!!, configuredModel, runTag)))
        val runner = EvalRunner(engine, configuredModel, provider, store, throttle, recordedLatency = { recorded[it]?.latencyMs })
        val outcomes = runner.run(items)
        val header = linkedMapOf(
            "Dataset" to EvalDataset.fingerprint(datasetFile),
            "Configuration" to "engine=$engine, configured model=$configuredModel, throttle=${throttle} ms" +
                (System.getenv("JARVIS_EVAL_CATEGORIES")?.let { ", categories=$it" } ?: ""),
            "Run tag" to (runTag ?: "none (cached items reused)"),
            "Replayed cloud answers" to (replayFile?.name?.let { "$it (not new measurements; latency as recorded)" } ?: "none")
        )
        val report = EvalReport.markdown(engine, outcomes.map { it.model }.toSet(), items, outcomes, header, runner.notRun, runner.stopReason)
        File("../eval/reports").mkdirs()
        File("../eval/reports", store.file.nameWithoutExtension + ".md").writeText(report)
        println(report)
    }

    /** Live runs pass the same privacy guard as the app (since 2026-10-21): a secret-looking item never reaches Groq. */
    private fun groq() = PrivacyGuardProvider(GroqReasoningProvider(
        apiKey = BuildConfig.GROQ_API_KEY,
        configuredModelName = BuildConfig.GROQ_MODEL,
        warningLogger = { _, _ -> }
    ))

    private fun gemini(apiKey: String, slot: String) = PrivacyGuardProvider(GeminiReasoningProvider(
        apiKey = apiKey,
        configuredModelName = BuildConfig.GEMINI_MODEL,
        providerName = slot,
        warningLogger = { _, _ -> },
        infoLogger = {}
    ))

    /**
     * Phase 2 adaptive router, as JarvisCore and the screen model route a request: on-device handlers answer with no
     * tool call (labelled "on-device"), local routes use the offline parser, and only the rest call Groq.
     */
    private class AdaptiveProvider(private val cloud: ReasoningProvider) : ReasoningProvider {
        override suspend fun reason(request: ReasoningRequest): ReasoningResponse {
            val offline = OfflineRuleProvider().reason(request)
            val answering = request.history.lastOrNull()?.let { it.role == "model" && it.text.trimEnd().endsWith("?") } == true
            val decision = AdaptiveRouter.decide(request.utterance, offline.steps.firstOrNull(), request.availableTools, true, answering)
            return when (decision.target) {
                RouteTarget.ON_DEVICE -> ReasoningResponse("(handled on the phone: ${decision.rule})", engineName = "on-device", modelName = "handler")
                RouteTarget.LOCAL_PARSER -> offline.copy(engineName = "local", modelName = "offline-rule")
                RouteTarget.CLOUD -> cloud.reason(request)
            }
        }
    }

    /** Mirrors JarvisCore before Phase 2: recognised phone/message commands stay on the local parser. */
    private class HybridProvider(private val cloud: ReasoningProvider) : ReasoningProvider {
        override suspend fun reason(request: ReasoningRequest): ReasoningResponse =
            if (ToolRouter.usesLocalPhoneParser(request.utterance)) OfflineRuleProvider().reason(request)
            else cloud.reason(request)
    }
}
