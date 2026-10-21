package com.jarvis.eval

import com.jarvis.core.ToolCheck
import com.jarvis.core.ToolRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8B regression guard: every tool call recorded in the 2026-10-21 runs that the scorer marked correct (tool and
 * labelled arguments) must pass the registry's typed check — the registry may not reject real, correct calls.
 */
class RegistryCompatibilityTest {
    @Test
    fun correctRecordedToolCallsPassTheRegistry() {
        val dataset = EvalDataset.load(File("../eval/utterances.json")).associateBy { it.id }
        val files = File("../eval/results").listFiles { f -> f.name.endsWith("__2026-10-21.jsonl") && !f.name.startsWith("pev") && !f.name.startsWith("intents") && !f.name.startsWith("memory") }!!
        var checked = 0
        val rejected = mutableListOf<String>()
        for (file in files) for (o in EvalResultStore(file).load().values) {
            val item = dataset[o.itemId] ?: continue
            val tool = o.toolName ?: continue
            val score = EvalScorer.score(item, o)
            if (score.argsCorrect != true) continue
            checked++
            val result = ToolRegistry.check(tool, o.args)
            if (result !is ToolCheck.Ok) rejected += "${file.name}/${o.itemId} $tool ${o.args}: $result"
        }
        assertTrue("checked $checked calls", checked > 100)
        assertEquals(emptyList<String>(), rejected)
    }
}
