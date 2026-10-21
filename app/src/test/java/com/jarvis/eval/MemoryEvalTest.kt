package com.jarvis.eval

import com.jarvis.core.MemoryRetriever
import com.jarvis.data.Memory
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 8A memory-retrieval evaluation over eval/memory/memory_cases.json (synthetic memories). Deterministic.
 * `JARVIS_MEMORY_WRITE=<label>` writes eval/results/memory__<label>.jsonl and eval/reports/memory__<label>.md; a
 * normal run checks that the current retriever still gives the committed phase8 results.
 */
class MemoryEvalTest {
    private data class Case(val id: String, val split: String, val text: String, val expected: List<String>, val clarify: Boolean)
    private data class Row(val case: Case, val retrieved: List<String>, val clarified: Boolean) {
        val answerable get() = case.expected.isNotEmpty()
        val top1 get() = if (case.clarify) clarified else answerable && !clarified && retrieved.firstOrNull() == case.expected.first()
        val isolated get() = !answerable && retrieved.isEmpty() && !clarified
        val unrelated get() = retrieved.count { it !in case.expected }
    }

    /** The retrieval under test: the ranked memories and whether JARVIS would ask instead of answering. */
    private fun retrieve(text: String, memories: List<Memory>): Pair<List<Memory>, Boolean> =
        when (val answer = MemoryRetriever.lookup(text, memories)) {
            is MemoryRetriever.Lookup.Found -> answer.memories to false
            is MemoryRetriever.Lookup.Conflict -> answer.memories to true
            MemoryRetriever.Lookup.Nothing -> emptyList<Memory>() to false
        }

    @Test
    fun memoryRetrieval() {
        val root = JSONObject(File("../eval/memory/memory_cases.json").readText())
        val ms = root.getJSONArray("memories")
        val memories = (0 until ms.length()).map { i ->
            val o = ms.getJSONObject(i)
            Memory(o.getString("id"), o.getString("text"), null, "test", o.getLong("updatedAt"), null, true, o.getString("type"), o.getString("key"), o.getLong("updatedAt"))
        }
        val qs = root.getJSONArray("queries")
        val rows = (0 until qs.length()).map { i ->
            val o = qs.getJSONObject(i)
            val exp = o.getJSONArray("expected")
            val case = Case(o.getString("id"), o.getString("split"), o.getString("text"), (0 until exp.length()).map(exp::getString), o.optBoolean("clarify"))
            val (got, clarified) = retrieve(case.text, memories)
            Row(case, got.map { it.id }, clarified)
        }
        fun line(r: Row) = JSONObject().put("id", r.case.id).put("split", r.case.split).put("retrieved", JSONArray(r.retrieved))
            .put("clarified", r.clarified).put("top1", r.top1).put("isolated", r.isolated).put("unrelated", r.unrelated).toString()
        val label = System.getenv("JARVIS_MEMORY_WRITE")
        if (!label.isNullOrBlank()) {
            File("../eval/results/memory__$label.jsonl").writeText(rows.joinToString("") { line(it) + "\n" })
            fun summary(split: String): String {
                val s = rows.filter { it.case.split == split }
                val answerable = s.filter { it.answerable }
                val empty = s.filter { !it.answerable }
                return "| $split | ${answerable.count { it.top1 }}/${answerable.size} | ${empty.count { it.isolated }}/${empty.size} | ${s.sumOf { it.unrelated }} | ${s.count { "m11" in it.retrieved }} |"
            }
            File("../eval/reports/memory__$label.md").writeText(listOf(
                "# Memory retrieval evaluation: $label", "",
                "- Cases: eval/memory/memory_cases.json v1 (${memories.size} synthetic memories, ${rows.size} queries: ${rows.count { it.case.split == "dev" }} dev, ${rows.count { it.case.split == "heldout" }} held-out)", "",
                "| Split | Best answer first (or asks when memories conflict) | Nothing retrieved when nothing is relevant | Unrelated memories retrieved | Secret retrieved |",
                "|---|---|---|---|---|", summary("dev"), summary("heldout"), "",
                "| Query | Split | Expected | Retrieved | Asked |", "|---|---|---|---|---|",
                *rows.map { "| ${it.case.id} ${it.case.text} | ${it.case.split} | ${it.case.expected.joinToString()} | ${it.retrieved.joinToString()} | ${it.clarified} |" }.toTypedArray(), ""
            ).joinToString("\n"))
            return
        }
        val committed = File("../eval/results/memory__phase8.jsonl")
        if (committed.exists()) assertEquals("memory results changed; re-run with JARVIS_MEMORY_WRITE=phase8 if intended",
            committed.readLines().filter(String::isNotBlank), rows.map(::line))
    }
}
