package com.jarvis.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 7 research evidence: what was measured, how, from which source, and when. The dashboard and both exports are
 * built from this one model, so they cannot disagree. Source types are never merged into a single score.
 */
enum class SourceType(val label: String, val explanation: String) {
    RECORDED_BENCHMARK("Recorded benchmark", "A live evaluation run whose per-item results are stored in eval/results"),
    REPLAY("Replay", "Recomputed from another run's recorded answers; not a new measurement"),
    SIMULATED("Simulated (fake infrastructure)", "Real code over in-memory fakes and injected faults; not a phone measurement"),
    TEST_RUN("Test run", "Automated unit/instrumented test, lint, or build results recorded on a date"),
    DEVICE_OBSERVATION("Device observation", "Checked by hand or by adb on the test phone; small and not repeated"),
    ESTIMATE("Estimate", "A feasibility projection, not measured"),
    HISTORICAL("Historical", "Measured on an earlier version and not re-run against the current code"),
    ON_DEVICE_RECORD("This phone's records", "Counted live from JARVIS's own records on this phone");

    companion object {
        fun parse(value: String): SourceType = entries.firstOrNull { it.name == value }
            ?: throw EvidenceFormatException("unknown source_type \"$value\"")
    }
}

class EvidenceFormatException(message: String) : Exception(message)

data class ResearchMetric(
    val id: String,
    val label: String,
    /** Null when not measured; never replaced by zero. */
    val value: Double?,
    /** percent, count, ms, ratio, or text. */
    val unit: String,
    val numerator: Int? = null,
    val denominator: Int? = null,
    /** What the denominator counts ("executed steps", "items"). */
    val denominatorLabel: String? = null,
    val definition: String,
    /** For text metrics (estimates): the stated value. */
    val text: String? = null
)

data class Experiment(
    val id: String,
    val title: String,
    val description: String,
    /** routing, pev, privacy, tests, device, feasibility. */
    val category: String,
    val sourceType: SourceType,
    val sources: List<String>,
    val runDate: String?,
    val sampleSize: Int?,
    /** measured, partial, not_run, estimate. */
    val status: String,
    val notes: String?,
    val metrics: List<ResearchMetric>
)

data class Evidence(val schemaVersion: Int, val commit: String?, val generatedOn: String?, val experiments: List<Experiment>) {
    companion object {
        const val SCHEMA = "jarvis.research.evidence"
        const val SCHEMA_VERSION = 1
        val CATEGORIES = listOf("routing", "pev", "privacy", "tests", "device", "feasibility")
        val UNITS = setOf("percent", "count", "ms", "ratio", "text")
        val STATUSES = setOf("measured", "partial", "not_run", "estimate")
    }
}

/** Strict reader for assets/research/evidence.json: a missing or renamed field is an error, never a silent default. */
object EvidenceParser {
    fun parse(json: String): Evidence {
        val root = try { JSONObject(json) } catch (e: Exception) { throw EvidenceFormatException("not valid JSON") }
        if (root.optString("schema") != Evidence.SCHEMA) throw EvidenceFormatException("schema must be ${Evidence.SCHEMA}")
        val version = root.optInt("schema_version", -1)
        if (version != Evidence.SCHEMA_VERSION) throw EvidenceFormatException("unsupported schema_version $version")
        val list = root.optJSONArray("experiments") ?: throw EvidenceFormatException("missing experiments")
        val experiments = (0 until list.length()).map { experiment(list.getJSONObject(it)) }
        if (experiments.map { it.id }.toSet().size != experiments.size) throw EvidenceFormatException("duplicate experiment id")
        return Evidence(version, root.optStringOrNull("generated_from_commit"), root.optStringOrNull("generated_on"), experiments)
    }

    private fun experiment(o: JSONObject): Experiment {
        val id = o.req("id")
        val category = o.req("category")
        if (category !in Evidence.CATEGORIES) throw EvidenceFormatException("$id: unknown category $category")
        val status = o.req("status")
        if (status !in Evidence.STATUSES) throw EvidenceFormatException("$id: unknown status $status")
        val metrics = o.optJSONArray("metrics") ?: throw EvidenceFormatException("$id: missing metrics")
        val sources = o.optJSONArray("sources") ?: throw EvidenceFormatException("$id: missing sources")
        return Experiment(
            id, o.req("title"), o.req("description"), category, SourceType.parse(o.req("source_type")),
            (0 until sources.length()).map(sources::getString), o.optStringOrNull("run_date"),
            if (o.isNull("sample_size") || !o.has("sample_size")) null else o.getInt("sample_size"),
            status, o.optStringOrNull("notes"),
            (0 until metrics.length()).map { metric(id, metrics.getJSONObject(it)) }
        )
    }

    private fun metric(owner: String, o: JSONObject): ResearchMetric {
        val id = o.req("id")
        val unit = o.req("unit")
        if (unit !in Evidence.UNITS) throw EvidenceFormatException("$owner/$id: unknown unit $unit")
        if (!o.has("value")) throw EvidenceFormatException("$owner/$id: missing value (use null when not measured)")
        val value = if (o.isNull("value")) null else o.getDouble("value")
        val num = if (o.has("numerator") && !o.isNull("numerator")) o.getInt("numerator") else null
        val den = if (o.has("denominator") && !o.isNull("denominator")) o.getInt("denominator") else null
        if ((num == null) != (den == null)) throw EvidenceFormatException("$owner/$id: numerator and denominator go together")
        if (den != null && den > 0 && unit == "percent" && value != null && kotlin.math.abs(value - 100.0 * num!! / den) > 0.05) {
            throw EvidenceFormatException("$owner/$id: value $value disagrees with $num/$den")
        }
        return ResearchMetric(id, o.req("label"), value, unit, num, den, o.optStringOrNull("denominator_label"), o.req("definition"), o.optStringOrNull("text"))
    }

    private fun JSONObject.req(key: String): String = optStringOrNull(key)?.takeIf { it.isNotBlank() } ?: throw EvidenceFormatException("missing \"$key\"")
}

private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

/**
 * Aggregate counts from this phone's own records. Only categories and counts — never the text of a request, a
 * reminder, a plan argument, or a privacy decision's subject.
 */
data class DeviceRecords(
    val since: Long?,
    val planRunsByStatus: Map<String, Int>,
    val planStepsByStatus: Map<String, Int>,
    val privacyDecisions: Map<String, Int>,
    val routingTargets: Map<String, Int>
) {
    companion object {
        /** Maps raw trace outcomes onto fixed categories; anything unrecognised is counted as "other", never copied. */
        fun privacyCategory(outcome: String): String = when {
            outcome.startsWith("local only: cloud AI is turned off") -> "local_only_cloud_off"
            outcome.startsWith("local only: no confirmation") -> "local_only_no_consent"
            outcome.startsWith("local only: request contains") -> "local_only_secret"
            outcome.startsWith("local only: a secret remained") -> "local_only_fail_closed"
            outcome.startsWith("allowed after minimising") -> "allowed_after_minimising"
            outcome.startsWith("cloud AI turned") -> "setting_changed"
            else -> "other"
        }

        fun routingCategory(outcome: String): String? = Regex("""^route (on_device|local_parser|cloud):""").find(outcome)?.groupValues?.get(1)

        fun from(traces: List<Pair<String, String>>, planRuns: List<String>, planSteps: List<String>, since: Long?): DeviceRecords =
            DeviceRecords(
                since,
                planRuns.groupingBy { it }.eachCount().toSortedMap(),
                planSteps.groupingBy { it }.eachCount().toSortedMap(),
                traces.filter { it.first == "privacy" }.groupingBy { privacyCategory(it.second) }.eachCount().toSortedMap(),
                traces.filter { it.first == "reasoning" }.mapNotNull { routingCategory(it.second) }.groupingBy { it }.eachCount().toSortedMap()
            )
    }
}

/**
 * The export boundary. Only the fields listed in [EXPERIMENT_FIELDS] / [METRIC_FIELDS] / device aggregate keys are
 * written; every string passes [SafeStorage] and, in CSV, a formula guard. Numbers stay numbers (a timestamp or a
 * count is never run through text redaction).
 */
object ResearchExport {
    const val SCHEMA = "jarvis.research.export"
    const val SCHEMA_VERSION = 1
    val EXPERIMENT_FIELDS = listOf("id", "title", "description", "category", "source_type", "source_type_label", "sources", "run_date", "sample_size", "status", "notes")
    val METRIC_FIELDS = listOf("id", "label", "value", "unit", "numerator", "denominator", "denominator_label", "definition", "text")
    val CSV_COLUMNS = listOf(
        "schema_version", "generated_at", "evidence_commit", "experiment_id", "category", "source_type", "run_date", "sample_size", "status",
        "metric_id", "metric_label", "value", "unit", "numerator", "denominator", "denominator_label", "definition", "text", "sources"
    )
    /** What an export contains, shown to the user before saving. */
    const val CONTENTS = "Aggregate evaluation results, experiment metadata (sources, dates, sample sizes), metric definitions, " +
        "and counts from this phone's plan-run, privacy, and routing records. No messages, emails, contacts, memories, " +
        "reminder text, plan arguments, keys, or secrets."

    private fun safe(text: String?) = text?.let(SafeStorage::text)

    fun json(evidence: Evidence, device: DeviceRecords?, generatedAt: String, appVersion: String): String {
        val root = JSONObject()
            .put("schema", SCHEMA).put("schema_version", SCHEMA_VERSION)
            .put("generated_at", generatedAt).put("app_version", safe(appVersion))
            .put("evidence_schema_version", evidence.schemaVersion)
            .put("evidence_commit", evidence.commit ?: JSONObject.NULL)
            .put("evidence_generated_on", evidence.generatedOn ?: JSONObject.NULL)
            .put("contents", CONTENTS)
            .put("source_types", JSONArray().apply {
                SourceType.entries.forEach { put(JSONObject().put("id", it.name).put("label", it.label).put("explanation", it.explanation)) }
            })
        root.put("experiments", JSONArray().apply { evidence.experiments.forEach { put(experimentJson(it)) } })
        root.put("device_records", device?.let(::deviceJson) ?: JSONObject.NULL)
        return root.toString(2)
    }

    private fun experimentJson(e: Experiment): JSONObject = JSONObject()
        .put("id", safe(e.id)).put("title", safe(e.title)).put("description", safe(e.description)).put("category", e.category)
        .put("source_type", e.sourceType.name).put("source_type_label", e.sourceType.label)
        .put("sources", JSONArray(e.sources.map { safe(it) }))
        .put("run_date", e.runDate ?: JSONObject.NULL).put("sample_size", e.sampleSize ?: JSONObject.NULL)
        .put("status", e.status).put("notes", safe(e.notes) ?: JSONObject.NULL)
        .put("metrics", JSONArray().apply {
            e.metrics.forEach { m ->
                put(JSONObject().put("id", safe(m.id)).put("label", safe(m.label)).put("value", m.value ?: JSONObject.NULL).put("unit", m.unit)
                    .put("numerator", m.numerator ?: JSONObject.NULL).put("denominator", m.denominator ?: JSONObject.NULL)
                    .put("denominator_label", safe(m.denominatorLabel) ?: JSONObject.NULL).put("definition", safe(m.definition))
                    .put("text", safe(m.text) ?: JSONObject.NULL))
            }
        })

    private fun deviceJson(d: DeviceRecords): JSONObject = JSONObject()
        .put("source_type", SourceType.ON_DEVICE_RECORD.name)
        .put("since", d.since ?: JSONObject.NULL)
        .put("plan_runs_by_status", JSONObject(d.planRunsByStatus as Map<*, *>))
        .put("plan_steps_by_status", JSONObject(d.planStepsByStatus as Map<*, *>))
        .put("privacy_decisions", JSONObject(d.privacyDecisions as Map<*, *>))
        .put("routing_targets", JSONObject(d.routingTargets as Map<*, *>))

    /** A CSV cell: text is sanitized and formula-guarded; numbers are written exactly as numbers. */
    private sealed interface Cell
    private data class T(val v: String?) : Cell
    private data class N(val v: String) : Cell

    fun csv(evidence: Evidence, device: DeviceRecords?, generatedAt: String): String {
        val rows = mutableListOf<List<Cell>>(CSV_COLUMNS.map { T(it) })
        fun base(id: String, category: String, type: String, date: String?, n: Int?, status: String): List<Cell> =
            listOf(N("$SCHEMA_VERSION"), T(generatedAt), T(evidence.commit), T(id), T(category), T(type), T(date), N(n?.toString().orEmpty()), T(status))
        for (e in evidence.experiments) for (m in e.metrics) {
            rows += base(e.id, e.category, e.sourceType.name, e.runDate, e.sampleSize, e.status) + listOf(
                T(m.id), T(m.label), N(number(m.value)), T(m.unit), N(m.numerator?.toString().orEmpty()), N(m.denominator?.toString().orEmpty()),
                T(m.denominatorLabel), T(m.definition), T(m.text), T(e.sources.joinToString(";"))
            )
        }
        device?.let { d ->
            val since = d.since?.let { java.time.Instant.ofEpochMilli(it).toString() }
            val groups = listOf("plan_runs_by_status" to d.planRunsByStatus, "plan_steps_by_status" to d.planStepsByStatus,
                "privacy_decisions" to d.privacyDecisions, "routing_targets" to d.routingTargets)
            for ((group, counts) in groups) for ((key, count) in counts) {
                rows += base("device_records", "device", SourceType.ON_DEVICE_RECORD.name, since, null, "measured") + listOf(
                    T("$group.$key"), T("$group: $key"), N(count.toString()), T("count"), N(""), N(""), T(null),
                    T("Count of this phone's JARVIS records in this category since the date shown"), T(null), T("this phone's JARVIS database")
                )
            }
        }
        return rows.joinToString("\r\n") { row -> row.joinToString(",") { c -> if (c is N) c.v else cell((c as T).v.orEmpty()) } } + "\r\n"
    }

    /** Unmeasured values are empty cells, never 0. */
    fun number(v: Double?): String = when {
        v == null -> ""
        v == Math.floor(v) && kotlin.math.abs(v) < 1e15 -> v.toLong().toString()
        else -> v.toString()
    }

    /** RFC 4180 quoting, plus a leading apostrophe for text a spreadsheet would run as a formula. */
    fun cell(raw: String): String {
        val sanitized = SafeStorage.text(raw)
        val guarded = if (sanitized.isNotEmpty() && sanitized[0] in "=+-@\t\r" && sanitized.toDoubleOrNull() == null) "'$sanitized" else sanitized
        return if (guarded.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + guarded.replace("\"", "\"\"") + "\"" else guarded
    }

    /** Reads a CSV produced by [csv] (for round-trip tests and validation). */
    fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += cell.toString(); cell.clear() }
                !quoted && c == '\r' -> Unit
                !quoted && c == '\n' -> { row += cell.toString(); cell.clear(); rows += row; row = mutableListOf() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }
}
