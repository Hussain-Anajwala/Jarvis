package com.jarvis.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3 seam: a scripted fake stands in for a neural model; nothing here runs real inference. */
class LocalModelProviderTest {
    private class Scripted(private val output: String, private val fail: Boolean = false) : LocalTextModel {
        override val name = "fake-1b"
        var prompts = mutableListOf<String>()
        override suspend fun complete(prompt: String, maxTokens: Int): String {
            prompts += prompt
            if (fail) throw IllegalStateException("out of memory")
            return output
        }
    }

    private fun run(output: String, fail: Boolean = false) = runBlocking {
        LocalModelReasoningProvider(Scripted(output, fail)).reason(ReasoningRequest("set a timer for ten minutes", JarvisCore.AVAILABLE_TOOLS))
    }

    @Test
    fun aValidJsonToolCallBecomesAPlanStep() {
        val r = run("""Sure! {"tool":"set_timer","args":{"duration_seconds":600}} done""")
        assertEquals("local-model", r.engineName)
        assertEquals(PlanStep("set_timer", mapOf("duration_seconds" to "600")), r.steps.single())
    }

    @Test
    fun anythingInvalidIsNeverExecuted() {
        listOf(
            """{"tool":"call_contact","args":{"name":"Asha"}}""", // not in the compact list
            """{"tool":"set_alarm","args":{"hour":"?","minute":"0"}}""", // fails the schema check
            """{"tool":"set_timer"""", // truncated
            "I think you want a timer.",
            """{"tool":"none"}"""
        ).forEach { assertTrue(it, run(it).steps.isEmpty()) }
    }

    @Test
    fun aModelFailureIsReportedNotHidden() {
        val r = run("", fail = true)
        assertTrue(r.failureReason!!.contains("local model failed"))
        assertTrue(r.steps.isEmpty())
    }

    @Test
    fun thePromptIsCompactAndListsOnlyAllowedTools() {
        val provider = LocalModelReasoningProvider(Scripted("{}"))
        val prompt = provider.prompt("x".repeat(1_000))
        assertTrue("prompt stays small for a phone CPU prefill (${prompt.length} chars)", prompt.length < 1_200)
        assertTrue(prompt.contains("set_timer(duration_seconds)"))
        assertNull(provider.parse("""{"tool":"compose_email","args":{}}"""))
    }
}
