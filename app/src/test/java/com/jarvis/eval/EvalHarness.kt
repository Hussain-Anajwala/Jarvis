package com.jarvis.eval

import com.jarvis.core.JarvisCore
import com.jarvis.core.JarvisSystemPrompt
import com.jarvis.core.PlanStep
import com.jarvis.core.ReasoningProvider
import com.jarvis.core.ReasoningRequest
import com.jarvis.core.ReasoningResponse
import com.jarvis.core.ReasoningToolSchema
import com.jarvis.core.ReasoningTurn
import com.jarvis.core.SensitiveData
import java.io.File
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import org.json.JSONArray
import org.json.JSONObject

/*
 * Evaluation harness (docs/EVAL.md). Providers only *propose* tool calls; nothing is executed, so every
 * tool is effectively stubbed. Results are persisted per item so runs are resumable and cached by
 * engine / model / prompt hash.
 */

data class EvalTurn(val role: String, val text: String)
data class EvalExpected(val action: String, val tool: String? = null, val args: Map<String, String> = emptyMap())
data class EvalItem(val id: String, val category: String, val turns: List<EvalTurn>, val expected: EvalExpected)

object EvalDataset {
    fun load(file: File): List<EvalItem> {
        val items = JSONObject(file.readText()).getJSONArray("items")
        return (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val turns = item.getJSONArray("turns")
            val expected = item.getJSONObject("expected")
            val args = expected.optJSONObject("args") ?: JSONObject()
            EvalItem(
                id = item.getString("id"),
                category = item.getString("category"),
                turns = (0 until turns.length()).map { t ->
                    turns.getJSONObject(t).let { EvalTurn(it.getString("role"), it.getString("text")) }
                },
                expected = EvalExpected(
                    action = expected.getString("action"),
                    tool = expected.optString("tool").ifBlank { null },
                    args = args.keys().asSequence().associateWith { args.getString(it) }
                )
            )
        }
    }

    /** "v1 · 134 items · sha 1a2b3c4d5e6f": identifies exactly which labelled dataset a run used. */
    fun fingerprint(file: File): String {
        val text = file.readText()
        val version = JSONObject(text).optInt("version")
        val count = JSONObject(text).getJSONArray("items").length()
        val sha = MessageDigest.getInstance("SHA-256").digest(text.replace("\r\n", "\n").toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }
        return "v$version · $count items · sha $sha"
    }

    /** Deterministic stratified subset: the first [perCategory] items of each category, in file order. */
    fun stratified(items: List<EvalItem>, perCategory: Int): List<EvalItem> =
        items.groupBy { it.category }.values.flatMap { it.take(perCategory) }

    fun select(items: List<EvalItem>, ids: Set<String>?, categories: Set<String>?, perCategory: Int?, limit: Int?): List<EvalItem> {
        var selected = items
        if (!ids.isNullOrEmpty()) selected = selected.filter { it.id in ids }
        if (!categories.isNullOrEmpty()) selected = selected.filter { it.category in categories }
        if (perCategory != null) selected = stratified(selected, perCategory)
        if (limit != null) selected = selected.take(limit)
        return selected
    }
}

data class EvalOutcome(
    val itemId: String,
    val engine: String,
    val model: String,
    val toolName: String?,
    val args: Map<String, String>,
    val reply: String,
    val latencyMs: Long,
    val retries: Int,
    val failure: String?,
    val runDate: String,
    /** HTTP 429 responses seen while answering this item (each one is a rate-limit event). */
    val rateLimited: Int = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("itemId", itemId).put("engine", engine).put("model", model)
        .put("toolName", toolName ?: JSONObject.NULL).put("args", JSONObject(args))
        .put("reply", SensitiveData.forStorage(reply).take(300)).put("latencyMs", latencyMs).put("retries", retries)
        .put("failure", failure ?: JSONObject.NULL).put("runDate", runDate).put("rateLimited", rateLimited)

    companion object {
        fun fromJson(json: JSONObject): EvalOutcome {
            val args = json.optJSONObject("args") ?: JSONObject()
            return EvalOutcome(
                itemId = json.getString("itemId"),
                engine = json.getString("engine"),
                model = json.getString("model"),
                toolName = json.optString("toolName").takeIf { !json.isNull("toolName") && it.isNotBlank() },
                args = args.keys().asSequence().associateWith { args.getString(it) },
                reply = json.optString("reply"),
                latencyMs = json.optLong("latencyMs"),
                retries = json.optInt("retries"),
                failure = json.optString("failure").takeIf { !json.isNull("failure") && it.isNotBlank() },
                runDate = json.getString("runDate"),
                rateLimited = json.optInt("rateLimited")
            )
        }
    }
}

/** Append-only JSONL store: one line per finished item, so an interrupted run resumes where it stopped. */
class EvalResultStore(val file: File) {
    fun load(): Map<String, EvalOutcome> =
        if (!file.exists()) emptyMap()
        else file.readLines().filter(String::isNotBlank)
            .map { EvalOutcome.fromJson(JSONObject(it)) }
            .associateBy { it.itemId }

    fun append(outcome: EvalOutcome) {
        file.parentFile?.mkdirs()
        file.appendText(outcome.toJson().toString() + "\n")
    }
}

object EvalCacheKey {
    /** Changes whenever the system prompt wording (at a fixed date) or the tool schema changes. */
    fun promptHash(): String {
        val fixedDate = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"))
        val material = JarvisSystemPrompt.build(fixedDate) + ReasoningToolSchema.toOpenAiJson(JarvisCore.AVAILABLE_TOOLS)
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }
    }

    /** [runTag] (e.g. a date) starts a fresh measurement instead of reusing an earlier run's cached items. */
    fun fileName(engine: String, configuredModel: String, runTag: String? = null): String =
        "${engine}__${configuredModel.replace(Regex("[^A-Za-z0-9.-]"), "_")}__${promptHash()}" +
            (runTag?.takeIf(String::isNotBlank)?.let { "__" + it.replace(Regex("[^A-Za-z0-9.-]"), "_") } ?: "") + ".jsonl"
}

class EvalRunner(
    private val engine: String,
    private val configuredModel: String,
    private val provider: ReasoningProvider,
    private val store: EvalResultStore,
    private val throttleMs: Long,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = ::println,
    /** Stop the run (keeping every finished item) after this many rate-limited items in a row. */
    private val maxConsecutiveRateLimited: Int = 3,
    /** Pause after a rate-limited item: this long times the number of rate-limited items in a row. */
    private val rateLimitPauseMs: Long = 60_000,
    /** For a replayed answer (engine "replay"), the latency measured when it was originally recorded. */
    private val recordedLatency: (String) -> Long? = { null }
) {
    /** Why the run stopped early, or null if every selected item was attempted. */
    var stopReason: String? = null
        private set
    /** Selected items that were never attempted because the run stopped. */
    val notRun = mutableListOf<String>()

    suspend fun run(items: List<EvalItem>): List<EvalOutcome> {
        val done = store.load()
        val outcomes = mutableListOf<EvalOutcome>()
        var lastCallAt: Long? = null
        var limitedInARow = 0
        for ((index, item) in items.withIndex()) {
            if (stopReason != null) {
                done[item.id]?.takeIf { it.failure == null }?.let(outcomes::add) ?: notRun.add(item.id)
                continue
            }
            val cached = done[item.id]?.takeIf { it.failure == null }
            if (cached != null) {
                outcomes += cached
                continue
            }
            lastCallAt?.let { previous ->
                val wait = previous + throttleMs - clock()
                if (wait > 0) sleeper(wait)
            }
            lastCallAt = clock()
            val outcome = evaluate(item)
            store.append(outcome)
            outcomes += outcome
            log("${item.id}: ${outcome.toolName ?: "(text)"} ${outcome.failure?.let { "FAILURE $it" }.orEmpty()}")
            if (outcome.failure != null && outcome.rateLimited > 0) {
                limitedInARow++
                if (limitedInARow >= maxConsecutiveRateLimited) {
                    stopReason = "stopped after $limitedInARow rate-limited items in a row (item ${item.id}, ${index + 1}/${items.size})"
                    log(stopReason!!)
                } else {
                    sleeper(rateLimitPauseMs * limitedInARow)
                }
            } else {
                limitedInARow = 0
            }
        }
        return outcomes
    }

    private suspend fun evaluate(item: EvalItem): EvalOutcome {
        val history = item.turns.dropLast(1).map { ReasoningTurn(if (it.role == "user") "user" else "model", it.text) }
        val request = ReasoningRequest(item.turns.last().text, JarvisCore.AVAILABLE_TOOLS, history)
        val started = clock()
        val response: ReasoningResponse = try {
            provider.reason(request)
        } catch (error: Exception) {
            return EvalOutcome(item.id, engine, configuredModel, null, emptyMap(), "", clock() - started, 0,
                "exception: ${error.javaClass.simpleName}", today().toString())
        }
        val step: PlanStep? = response.steps.firstOrNull()?.let(ReasoningToolSchema::normalize)
        return EvalOutcome(
            itemId = item.id,
            engine = engine,
            model = "${response.engineName}/${response.modelName ?: "offline-rule"}",
            toolName = step?.toolName,
            args = step?.parameters.orEmpty(),
            reply = response.reply,
            latencyMs = (if (response.engineName == ReplayProvider.ENGINE) recordedLatency(item.id) else null) ?: (clock() - started),
            retries = response.attempts.count { it.httpStatus != null && it.httpStatus != 200 },
            // A cloud engine that fell back to the offline parser did not answer this item.
            failure = response.failureReason?.takeIf { engine != "offline" },
            runDate = today().toString(),
            rateLimited = response.attempts.count { it.httpStatus == 429 }
        )
    }
}

/**
 * Answers from an earlier recorded run, looked up by item, so a routing strategy can be compared on the same cloud
 * answers without calling the API again. Reports label these items "replay"; they are not new measurements.
 */
class ReplayProvider(private val recorded: Map<String, EvalOutcome>, private val itemFor: (ReasoningRequest) -> String?) : ReasoningProvider {
    override suspend fun reason(request: ReasoningRequest): ReasoningResponse {
        val id = itemFor(request) ?: error("no item for this request")
        val outcome = recorded[id] ?: return ReasoningResponse("", engineName = ENGINE, failureReason = "not in the recorded run")
        return ReasoningResponse(
            reply = outcome.reply,
            steps = listOfNotNull(outcome.toolName?.let { PlanStep(it, outcome.args) }),
            engineName = ENGINE,
            modelName = outcome.model,
            failureReason = outcome.failure
        )
    }

    companion object { const val ENGINE = "replay" }
}

data class ItemScore(
    val decisionCorrect: Boolean,
    val argsCorrect: Boolean?,
    val clarified: Boolean,
    val failed: Boolean
)

object EvalScorer {
    fun score(item: EvalItem, outcome: EvalOutcome): ItemScore {
        if (outcome.failure != null) return ItemScore(false, if (item.expected.action == "tool_call") false else null, false, true)
        val clarified = outcome.toolName == null && outcome.reply.contains("?")
        val decision = when (item.expected.action) {
            "tool_call" -> outcome.toolName == item.expected.tool
            "clarify" -> clarified
            else -> outcome.toolName == null
        }
        val args = if (item.expected.action == "tool_call") {
            decision && argsMatch(item.expected.args, outcome.args, LocalDate.parse(outcome.runDate))
        } else null
        return ItemScore(decision, args, clarified, false)
    }

    fun argsMatch(expected: Map<String, String>, actual: Map<String, String>, runDate: LocalDate): Boolean =
        expected.all { (key, want) -> valueMatches(key, want, actual[key] ?: return false, runDate) }

    private fun valueMatches(key: String, want: String, got: String, runDate: LocalDate): Boolean {
        if (key == "date") return dateMatches(want, got, runDate)
        val wantNumber = want.trim().toLongOrNull()
        if (wantNumber != null) return got.trim().toDoubleOrNull()?.toLong() == wantNumber
        val a = normalize(want)
        val b = normalize(got)
        return a.isNotEmpty() && b.isNotEmpty() && (a in b || b in a)
    }

    private fun dateMatches(token: String, got: String, runDate: LocalDate): Boolean {
        val actual = runCatching { LocalDate.parse(got.trim()) }.getOrNull() ?: return false
        return when (val t = token.lowercase()) {
            "today" -> actual == runDate
            "tomorrow" -> actual == runDate.plusDays(1)
            else -> {
                val day = DayOfWeek.valueOf(t.uppercase())
                // Accept today (if it is that weekday) or the next occurrence; the phrase is ambiguous on that day.
                actual == runDate.with(TemporalAdjusters.nextOrSame(day)) || actual == runDate.with(TemporalAdjusters.next(day))
            }
        }
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("the ").removeSuffix(" the")
}

data class EvalMetrics(
    val items: Int,
    val toolAccuracy: Double,
    val argAccuracy: Double,
    val clarifyRate: Double,
    val falseClarifyRate: Double,
    val p50Ms: Long,
    val p95Ms: Long,
    val retries: Int,
    val failures: Int
)

object EvalMetricsCalculator {
    fun compute(items: List<EvalItem>, outcomes: List<EvalOutcome>): EvalMetrics {
        val byId = outcomes.associateBy { it.itemId }
        val scored = items.mapNotNull { item -> byId[item.id]?.let { item to EvalScorer.score(item, it) } }
        val toolItems = scored.filter { it.first.expected.action == "tool_call" }
        val clarifyItems = scored.filter { it.first.expected.action == "clarify" }
        val latencies = scored.mapNotNull { (item, _) -> byId.getValue(item.id).takeIf { it.failure == null }?.latencyMs }.sorted()
        fun ratio(n: Int, d: Int) = if (d == 0) Double.NaN else n.toDouble() / d
        return EvalMetrics(
            items = scored.size,
            toolAccuracy = ratio(scored.count { it.second.decisionCorrect }, scored.size),
            argAccuracy = ratio(toolItems.count { it.second.argsCorrect == true }, toolItems.size),
            clarifyRate = ratio(clarifyItems.count { it.second.clarified }, clarifyItems.size),
            falseClarifyRate = ratio(toolItems.count { it.second.clarified }, toolItems.size),
            p50Ms = percentile(latencies, 0.50),
            p95Ms = percentile(latencies, 0.95),
            retries = scored.sumOf { byId.getValue(it.first.id).retries },
            failures = scored.count { it.second.failed }
        )
    }

    fun percentile(sorted: List<Long>, p: Double): Long =
        if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * p).let { kotlin.math.ceil(it).toInt() }]
}

object EvalReport {
    private fun pct(value: Double) = if (value.isNaN()) "n/a" else "%.1f%%".format(value * 100)

    fun markdown(
        engine: String,
        models: Set<String>,
        items: List<EvalItem>,
        outcomes: List<EvalOutcome>,
        /** Run facts shown first: dataset, configuration, run tag. */
        header: Map<String, String> = emptyMap(),
        notRun: List<String> = emptyList(),
        stopReason: String? = null
    ): String {
        val metrics = EvalMetricsCalculator.compute(items, outcomes)
        val byId = outcomes.associateBy { it.itemId }
        val perCategory = items.groupBy { it.category }.entries.joinToString("\n") { (category, group) ->
            val m = EvalMetricsCalculator.compute(group, outcomes)
            "| $category | ${m.items} | ${pct(m.toolAccuracy)} | ${pct(m.argAccuracy)} | ${m.failures} |"
        }
        val misses = items.mapNotNull { item ->
            val outcome = byId[item.id] ?: return@mapNotNull null
            val score = EvalScorer.score(item, outcome)
            if (score.decisionCorrect && score.argsCorrect != false) null
            else "| ${item.id} | ${item.expected.action}${item.expected.tool?.let { ":$it" }.orEmpty()} | " +
                "${outcome.failure ?: outcome.toolName ?: "text"} | ${outcome.args.entries.joinToString("; ") { "${it.key}=${it.value}" }.replace("|", "/")} |"
        }.joinToString("\n")
        return listOf(
            "# Eval run: $engine",
            "",
            "- Models answering: ${models.joinToString()}",
            "- Prompt hash: ${EvalCacheKey.promptHash()}; run dates: ${outcomes.map { it.runDate }.toSet().joinToString()}",
            *header.map { (k, v) -> "- $k: $v" }.toTypedArray(),
            "- Items selected: ${items.size}; scored (answered or failed): ${metrics.items}; not run: ${notRun.size}" +
                (stopReason?.let { " ($it)" } ?: ""),
            "- Rate-limit (HTTP 429) responses: ${outcomes.sumOf { it.rateLimited }}; items failed after retries: ${metrics.failures}",
            "- Not run (not scored, not counted as wrong): ${notRun.joinToString().ifBlank { "none" }}",
            "",
            "| Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |",
            "|---|---|---|---|---|---|---|---|",
            "| ${pct(metrics.toolAccuracy)} | ${pct(metrics.argAccuracy)} | ${pct(metrics.clarifyRate)} | " +
                "${pct(metrics.falseClarifyRate)} | ${metrics.p50Ms} ms | ${metrics.p95Ms} ms | ${metrics.retries} | ${metrics.failures} |",
            "",
            "| Subset | Items scored | Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 |",
            "|---|---|---|---|---|---|---|---|",
            *listOf("original (non-holdout)" to items.filter { it.category != "holdout" }, "holdout" to items.filter { it.category == "holdout" })
                .filter { it.second.isNotEmpty() }
                .map { (name, subset) ->
                    val m = EvalMetricsCalculator.compute(subset, outcomes)
                    "| $name | ${m.items} | ${pct(m.toolAccuracy)} | ${pct(m.argAccuracy)} | ${pct(m.clarifyRate)} | ${pct(m.falseClarifyRate)} | ${m.p50Ms} ms | ${m.p95Ms} ms |"
                }.toTypedArray(),
            "",
            "| Category | Items | Tool acc. | Arg acc. | Failures |",
            "|---|---|---|---|---|",
            perCategory,
            "",
            "## Misses",
            "| Item | Expected | Got | Args |",
            "|---|---|---|---|",
            misses,
            ""
        ).joinToString("\n")
    }
}
