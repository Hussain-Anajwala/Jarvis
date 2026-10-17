package com.jarvis.eval

import com.jarvis.BuildConfig
import com.jarvis.core.GeminiReasoningProvider
import com.jarvis.core.GroqReasoningProvider
import com.jarvis.core.OfflineRuleProvider
import com.jarvis.core.ReasoningProvider
import com.jarvis.core.ReasoningRequest
import com.jarvis.core.ReasoningResponse
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
        val (provider, configuredModel, defaultThrottle) = when (engine) {
            "offline" -> Triple(OfflineRuleProvider(), "offline-rule", 0L)
            "groq" -> Triple(groq(), BuildConfig.GROQ_MODEL, 2_500L)
            "gemini" -> Triple(gemini(BuildConfig.GEMINI_API_KEY, "gemini-primary"), BuildConfig.GEMINI_MODEL, 16_000L)
            "gemini2" -> Triple(gemini(BuildConfig.GEMINI_API_KEY_2, "gemini-secondary"), BuildConfig.GEMINI_MODEL, 16_000L)
            "hybrid" -> Triple(HybridProvider(groq()), "local+" + BuildConfig.GROQ_MODEL, 2_500L)
            else -> error("Unknown JARVIS_EVAL_ENGINE")
        }
        assumeTrue("$engine key not configured", engine == "offline" || when (engine) {
            "gemini" -> BuildConfig.GEMINI_API_KEY.isNotBlank()
            "gemini2" -> BuildConfig.GEMINI_API_KEY_2.isNotBlank()
            else -> BuildConfig.GROQ_API_KEY.isNotBlank()
        })
        val all = EvalDataset.load(File("../eval/utterances.json"))
        val items = EvalDataset.select(
            all,
            System.getenv("JARVIS_EVAL_IDS")?.split(',')?.map(String::trim)?.filter(String::isNotBlank)?.toSet(),
            System.getenv("JARVIS_EVAL_CATEGORIES")?.split(',')?.map(String::trim)?.filter(String::isNotBlank)?.toSet(),
            System.getenv("JARVIS_EVAL_PER_CATEGORY")?.toIntOrNull(),
            System.getenv("JARVIS_EVAL_LIMIT")?.toIntOrNull()
        )
        val throttle = System.getenv("JARVIS_EVAL_THROTTLE_MS")?.toLongOrNull() ?: defaultThrottle
        val store = EvalResultStore(File("../eval/results", EvalCacheKey.fileName(engine!!, configuredModel)))
        val outcomes = EvalRunner(engine, configuredModel, provider, store, throttle).run(items)
        val report = EvalReport.markdown(engine, outcomes.map { it.model }.toSet(), items, outcomes)
        File("../eval/reports").mkdirs()
        File("../eval/reports", store.file.nameWithoutExtension + ".md").writeText(report)
        println(report)
    }

    private fun groq() = GroqReasoningProvider(
        apiKey = BuildConfig.GROQ_API_KEY,
        configuredModelName = BuildConfig.GROQ_MODEL,
        warningLogger = { _, _ -> }
    )

    private fun gemini(apiKey: String, slot: String) = GeminiReasoningProvider(
        apiKey = apiKey,
        configuredModelName = BuildConfig.GEMINI_MODEL,
        providerName = slot,
        warningLogger = { _, _ -> },
        infoLogger = {}
    )

    /** Mirrors JarvisCore: recognised phone/message commands stay on the local parser. */
    private class HybridProvider(private val cloud: ReasoningProvider) : ReasoningProvider {
        override suspend fun reason(request: ReasoningRequest): ReasoningResponse =
            if (ToolRouter.usesLocalPhoneParser(request.utterance)) OfflineRuleProvider().reason(request)
            else cloud.reason(request)
    }
}
