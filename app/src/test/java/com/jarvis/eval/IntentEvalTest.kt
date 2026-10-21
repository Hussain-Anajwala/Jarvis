package com.jarvis.eval

import com.jarvis.core.Interpretation
import com.jarvis.core.OnDeviceIntents
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 9A on-device intent evaluation (English + Hinglish) over eval/intents/intents.json, run through the same
 * OnDeviceIntents chain the app uses. Deterministic; no network. `JARVIS_INTENT_WRITE=<label>` writes
 * eval/results/intents__<label>.jsonl and eval/reports/intents__<label>.md; a normal run checks the current code still
 * produces the committed "reliability" results (regression protection; phase9 is kept as history) and writes nothing.
 */
class IntentEvalTest {
    private val now = ZonedDateTime.of(2026, 10, 21, 14, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    data class Item(val id: String, val split: String, val lang: String, val text: String, val pending: String?, val intent: String, val args: JSONObject)
    data class Scored(val item: Item, val got: Interpretation, val intentOk: Boolean, val argsOk: Boolean?)

    private fun load(): List<Item> {
        val items = JSONObject(File("../eval/intents/intents.json").readText()).getJSONArray("items")
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            val ctx = o.getJSONObject("context")
            val exp = o.getJSONObject("expected")
            Item(o.getString("id"), o.getString("split"), o.getString("lang"), o.getString("text"),
                if (ctx.isNull("pending")) null else ctx.getString("pending"), exp.getString("intent"), exp.getJSONObject("args"))
        }
    }

    private fun score(item: Item): Scored {
        val got = kotlinx.coroutines.runBlocking { OnDeviceIntents.classify(item.text, now, item.pending) }
        val intentOk = got.intent == item.intent
        if (item.args.length() == 0) return Scored(item, got, intentOk, null)
        val argsOk = intentOk && item.args.keys().asSequence().all { k ->
            val want = item.args.get(k).toString()
            when (k) {
                "title_contains" -> got.args["title"]?.lowercase()?.contains(want.lowercase()) == true
                else -> got.args[k] == want
            }
        }
        return Scored(item, got, intentOk, argsOk)
    }

    private fun line(s: Scored) = JSONObject().put("id", s.item.id).put("split", s.item.split).put("lang", s.item.lang)
        .put("expected", s.item.intent).put("got", s.got.intent).put("args", JSONObject(s.got.args as Map<*, *>))
        .put("intentOk", s.intentOk).put("argsOk", s.argsOk ?: JSONObject.NULL).toString()

    private fun table(rows: List<Scored>): String {
        fun cell(sub: List<Scored>): String {
            val withArgs = sub.filter { it.argsOk != null }
            return "${sub.count { it.intentOk }}/${sub.size} | ${withArgs.count { it.argsOk == true }}/${withArgs.size}"
        }
        val groups = listOf("dev" to "en", "dev" to "hinglish", "heldout" to "en", "heldout" to "hinglish")
        return (listOf("| Split | Language | Intent correct | Args correct (items with labelled args) |", "|---|---|---|---|") +
            groups.map { (sp, lang) -> "| $sp | $lang | ${cell(rows.filter { it.item.split == sp && it.item.lang == lang })} |" } +
            listOf("| dev | all | ${cell(rows.filter { it.item.split == "dev" })} |", "| heldout | all | ${cell(rows.filter { it.item.split == "heldout" })} |")).joinToString("\n")
    }

    @Test
    fun onDeviceIntents() {
        val rows = load().map(::score)
        val label = System.getenv("JARVIS_INTENT_WRITE")
        if (!label.isNullOrBlank()) {
            File("../eval/results/intents__$label.jsonl").writeText(rows.joinToString("") { line(it) + "\n" })
            val misses = rows.filter { !it.intentOk || it.argsOk == false }.joinToString("\n") {
                "| ${it.item.id} | ${it.item.split} | ${it.item.text} | ${it.item.intent} | ${it.got.intent} ${it.got.args} |"
            }
            File("../eval/reports/intents__$label.md").writeText(listOf(
                "# On-device intent evaluation: $label", "",
                "- Dataset: eval/intents/intents.json v1 (${rows.size} items: ${rows.count { it.item.split == "dev" }} dev, ${rows.count { it.item.split == "heldout" }} held-out)",
                "- Chain: OnDeviceIntents.classify (the app's on-device order); clock Wed 21 Oct 2026 14:00 IST; no network.", "",
                table(rows), "", "## Misses", "| Item | Split | Text | Expected | Got |", "|---|---|---|---|---|", misses, ""
            ).joinToString("\n"))
            println(table(rows))
            return
        }
        val committed = File("../eval/results/intents__reliability.jsonl")
        if (committed.exists()) {
            assertEquals("on-device intent results changed; re-run with JARVIS_INTENT_WRITE=reliability if intended",
                committed.readLines().filter(String::isNotBlank), rows.map(::line))
        }
    }
}
