package com.jarvis.core

import com.jarvis.formatMetric
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Phase 7: evidence parsing, honest labels, and the CSV/JSON export boundary. Synthetic secrets only. */
class ResearchExportTest {
    private val asset = EvidenceParser.parse(File("src/main/assets/research/evidence.json").readText())
    private val now = "2026-10-21T08:00:00Z"

    private fun expectFormatError(json: String) {
        try { EvidenceParser.parse(json); fail("accepted: $json") } catch (_: EvidenceFormatException) { }
    }

    private val minimal = """{"schema":"jarvis.research.evidence","schema_version":1,"experiments":[
        {"id":"x","title":"T","description":"D","category":"pev","source_type":"SIMULATED","sources":["a"],"run_date":null,"sample_size":3,
         "status":"measured","notes":null,"metrics":[{"id":"m","label":"L","value":50.0,"unit":"percent","numerator":1,"denominator":2,"definition":"d"}]}]}"""

    @Test
    fun malformedOrRenamedFieldsFailClearlyAndNothingIsDefaultedToZero() {
        EvidenceParser.parse(minimal)
        expectFormatError("not json")
        expectFormatError(minimal.replace("jarvis.research.evidence", "other"))
        expectFormatError(minimal.replace("\"schema_version\":1", "\"schema_version\":2"))
        expectFormatError(minimal.replace("\"SIMULATED\"", "\"LIVE\""))
        expectFormatError(minimal.replace("\"title\":\"T\",", ""))
        expectFormatError(minimal.replace("\"value\":50.0,", "")) // a missing value must be explicit null
        expectFormatError(minimal.replace("50.0", "60.0")) // disagrees with 1/2
        expectFormatError(minimal.replace("\"numerator\":1,", ""))
        expectFormatError(minimal.replace("\"category\":\"pev\"", "\"category\":\"misc\""))
        val empty = EvidenceParser.parse("""{"schema":"jarvis.research.evidence","schema_version":1,"experiments":[]}""")
        assertTrue(empty.experiments.isEmpty())
        val unmeasured = EvidenceParser.parse(minimal.replace("\"value\":50.0,\"unit\":\"percent\",\"numerator\":1,\"denominator\":2", "\"value\":null,\"unit\":\"percent\""))
        assertNull(unmeasured.experiments.single().metrics.single().value)
        assertEquals("not measured", formatMetric(unmeasured.experiments.single().metrics.single()))
    }

    @Test
    fun theRealEvidenceIsLabelledHonestly() {
        val byId = asset.experiments.associateBy { it.id }
        assertEquals(SourceType.REPLAY, byId.getValue("routing-adaptive-replay-2026-10-21").sourceType)
        assertEquals(23, byId.getValue("routing-adaptive-holdout-2026-10-21").sampleSize)
        assertEquals(SourceType.SIMULATED, byId.getValue("pev-2026-10-21").sourceType)
        assertTrue(byId.keys.filter { it.endsWith("2026-10-18") }.all { byId.getValue(it).sourceType == SourceType.HISTORICAL })
        // Estimates carry text only, never a number that could pass for a measurement.
        asset.experiments.filter { it.sourceType == SourceType.ESTIMATE }.flatMap { it.metrics }.forEach { assertNull(it.id, it.value) }
        // Nothing from fakes, replays, or estimates is labelled as a phone measurement.
        asset.experiments.filter { it.sourceType in setOf(SourceType.DEVICE_OBSERVATION, SourceType.ON_DEVICE_RECORD) }
            .forEach { assertFalse(it.id, it.id.startsWith("pev-") || it.id.startsWith("routing") || it.category == "feasibility") }
        // A skipped live test is not a pass, and Gemini (never run) has no value.
        assertNull(byId.getValue("routing-gemini").metrics.single().value)
        assertEquals("not_run", byId.getValue("routing-gemini").status)
        // Denominators are explicit: PEV verified steps are counted over executed steps (failed/unverifiable included).
        val steps = byId.getValue("pev-2026-10-21").metrics.first { it.id == "steps_verified" }
        assertEquals("executed steps", steps.denominatorLabel)
        assertEquals("75.0% (15/20 executed steps)", formatMetric(steps))
    }

    @Test
    fun csvIsEscapedGuardedAgainstFormulasAndKeepsNumbersExact() {
        assertEquals("\"a,b\"", ResearchExport.cell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", ResearchExport.cell("say \"hi\""))
        assertEquals("\"two\nlines\"", ResearchExport.cell("two\nlines"))
        assertEquals("मराठी ✓ José", ResearchExport.cell("मराठी ✓ José"))
        listOf("=1+1", "+SUM(A1)", "-2+3", "@cmd", "\tx").forEach { assertTrue(it, ResearchExport.cell(it).trimStart('"').startsWith("'")) }
        assertEquals("-12.5", ResearchExport.cell("-12.5")) // a plain negative number is not a formula
        // A 13-digit epoch passes the Luhn check, but numeric cells are never run through text redaction.
        val epoch = 1_792_573_320_000.0
        assertTrue("this epoch is Luhn-valid, so text redaction would hit it", SensitiveData.isSecret(epoch.toLong().toString()))
        assertEquals("[card]", ResearchExport.cell(epoch.toLong().toString()))
        val withEpoch = asset.copy(experiments = listOf(asset.experiments.first().copy(metrics = listOf(
            ResearchMetric("t", "timestamp", epoch, "count", definition = "d"), ResearchMetric("n", "missing", null, "ms", definition = "d")))))
        val rows = ResearchExport.parseCsv(ResearchExport.csv(withEpoch, null, now))
        val header = rows.first()
        assertEquals(ResearchExport.CSV_COLUMNS, header)
        val value = header.indexOf("value")
        assertEquals("1792573320000", rows[1][value])
        assertEquals("missing values are empty, not 0", "", rows[2][value])
    }

    @Test
    fun csvAndJsonRoundTripToTheSameValuesTheDashboardShows() {
        val device = DeviceRecords.from(listOf("privacy" to "local only: request contains a labelled credential (value not recorded)",
            "reasoning" to "route cloud: cloud: needs language understanding", "reasoning" to "route on_device: on-device: email dialogue"),
            listOf("SUCCEEDED", "CANCELLED"), listOf("SUCCEEDED", "SUCCEEDED", "CANCELLED", "CANCELLED"), 1_792_520_000_000)
        val rows = ResearchExport.parseCsv(ResearchExport.csv(asset, device, now))
        val h = rows.first()
        val metricRows = rows.drop(1).filter { it[h.indexOf("experiment_id")] != "device_records" }
        assertEquals(asset.experiments.sumOf { it.metrics.size }, metricRows.size)
        val json = JSONObject(ResearchExport.json(asset, device, now, "0.1"))
        assertEquals(ResearchExport.SCHEMA, json.getString("schema"))
        assertEquals(1, json.getInt("schema_version"))
        val exps = json.getJSONArray("experiments")
        for (i in 0 until exps.length()) {
            val e = exps.getJSONObject(i)
            val source = asset.experiments.first { it.id == e.getString("id") }
            val metrics = e.getJSONArray("metrics")
            for (j in 0 until metrics.length()) {
                val m = metrics.getJSONObject(j)
                val original = source.metrics.first { it.id == m.getString("id") }
                if (original.value == null) assertTrue(m.isNull("value")) else assertEquals(original.value!!, m.getDouble("value"), 1e-9)
                val csvRow = metricRows.first { it[h.indexOf("experiment_id")] == source.id && it[h.indexOf("metric_id")] == original.id }
                assertEquals(ResearchExport.number(original.value), csvRow[h.indexOf("value")])
            }
        }
        val d = json.getJSONObject("device_records")
        assertEquals(1, d.getJSONObject("privacy_decisions").getInt("local_only_secret"))
        assertEquals(1, d.getJSONObject("routing_targets").getInt("cloud"))
        assertEquals(1_792_520_000_000, d.getLong("since"))
        assertTrue(json.isNull("evidence_commit") || json.getString("evidence_commit").isNotBlank())
        assertTrue(JSONObject(ResearchExport.json(asset, null, now, "0.1")).isNull("device_records"))
    }

    @Test
    fun onlyAllowListedFieldsAreExportedAndSecretValuesNeverAppear() {
        val pin = "739204"; val key = "sk_live_1234567890abcdefXYZ"
        val poisoned = asset.copy(experiments = asset.experiments.take(1).map {
            it.copy(notes = "debug: my PIN is $pin", description = "api key $key", metrics = it.metrics.map { m -> m.copy(definition = m.definition + " password: hunter2x9") })
        })
        // Raw trace text with secrets and personal details: only categories may come out.
        val device = DeviceRecords.from(listOf("privacy" to "something new: OTP $pin for Asha +91 98000 00001", "reasoning" to "route cloud: call Asha at asha@example.com"),
            listOf("SUCCEEDED"), listOf("SUCCEEDED"), null)
        assertEquals(mapOf("other" to 1), device.privacyDecisions)
        for (text in listOf(ResearchExport.json(poisoned, device, now, "0.1"), ResearchExport.csv(poisoned, device, now))) {
            listOf(pin, key, "hunter2x9", "asha@example.com", "98000", "Asha").forEach { assertFalse("export contained a protected value", text.contains(it)) }
        }
        val json = JSONObject(ResearchExport.json(poisoned, device, now, "0.1"))
        assertEquals(setOf("schema", "schema_version", "generated_at", "app_version", "evidence_schema_version", "evidence_commit",
            "evidence_generated_on", "contents", "source_types", "experiments", "device_records"), json.keySet())
        val e = json.getJSONArray("experiments").getJSONObject(0)
        assertEquals((ResearchExport.EXPERIMENT_FIELDS + "metrics").toSet(), e.keySet())
        assertEquals(ResearchExport.METRIC_FIELDS.toSet(), e.getJSONArray("metrics").getJSONObject(0).keySet())
        assertEquals(setOf("source_type", "since", "plan_runs_by_status", "plan_steps_by_status", "privacy_decisions", "routing_targets"),
            json.getJSONObject("device_records").keySet())
    }

    @Test
    fun theExportBuilderReadsOnlyTheEvidenceModelAndCounts() {
        // Source scan: the export code never touches DAOs, entities with user text, or the Activity history.
        val source = File("src/main/java/com/jarvis/core/ResearchEvidence.kt").readText()
        listOf("Dao", "AgentTask", "Memory", "NotificationRecord", "DecisionTraceEntry", "PlanStepRecord", "argsJson", "objective").forEach {
            assertFalse("export code references $it", Regex("""\b$it\b""").containsMatchIn(source))
        }
    }
}
