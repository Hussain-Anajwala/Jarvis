package com.jarvis.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderChainTest {
    @Test
    fun triesProvidersInOrderAndReturnsFirstSuccessfulResponse() = runBlocking {
        val called = mutableListOf<String>()
        val chain = ResilientReasoningProvider(
            providers = listOf(
                ConfiguredReasoningProvider("groq", FakeProvider("groq", false, called)),
                ConfiguredReasoningProvider("gemini-primary", FakeProvider("gemini-primary", true, called))
            ),
            clock = { 10_000L }
        )

        val result = chain.reason(ReasoningRequest("hello", emptyList()))

        assertEquals(listOf("groq", "gemini-primary"), called)
        assertEquals("gemini-primary", result.engineName)
        assertEquals(listOf("groq", "gemini-primary"), result.attempts.map { it.providerName })
    }

    @Test
    fun remembersProviderCooldownAndSkipsUntilItExpires() = runBlocking {
        val called = mutableListOf<String>()
        val cooldowns = MemoryProviderCooldownStore()
        val providers = listOf(
            ConfiguredReasoningProvider("groq", FakeProvider("groq", false, called)),
            ConfiguredReasoningProvider("gemini-primary", FakeProvider("gemini-primary", true, called))
        )
        var now = 10_000L
        val chain = ResilientReasoningProvider(providers, cooldownStore = cooldowns, clock = { now })
        val request = ReasoningRequest("hello", emptyList())

        chain.reason(request)
        chain.reason(request)

        assertEquals(listOf("groq", "gemini-primary", "gemini-primary"), called)
        assertTrue(cooldowns.readUntil("groq") > now)
        now = cooldowns.readUntil("groq") + 1
        chain.reason(request)
        assertEquals(2, called.count { it == "groq" })
    }

    @Test
    fun providerExceptionFailsOverInsteadOfCrashing() = runBlocking {
        val chain = ResilientReasoningProvider(
            providers = listOf(
                ConfiguredReasoningProvider("broken", object : ReasoningProvider {
                    override suspend fun reason(request: ReasoningRequest): ReasoningResponse =
                        throw ArrayIndexOutOfBoundsException("bug")
                }),
                ConfiguredReasoningProvider("healthy", object : ReasoningProvider {
                    override suspend fun reason(request: ReasoningRequest) =
                        ReasoningResponse("ok", engineName = "healthy", modelName = "m")
                })
            )
        )
        val result = chain.reason(ReasoningRequest("hello", emptyList()))
        assertEquals("healthy", result.engineName)
        assertEquals("broken", result.attempts.first().providerName)
    }

    @Test
    fun sharedSchemaTranslatesSameRequiredFieldsForBothProviders() {
        val tool = "meeting.create"
        // title is optional (2026-10-21): with no purpose said JARVIS asks instead of inventing "Meeting at <place>".
        val expected = listOf("hour", "minute", "location", "date")

        assertEquals(expected, ReasoningToolSchema.forNames(listOf(tool)).single().requiredFields)
        assertEquals(
            expected,
            ReasoningToolSchema.toGeminiJson(listOf(tool))
                .getJSONObject(0).getJSONObject("parameters").getJSONArray("required")
                .let { array -> (0 until array.length()).map(array::getString) }
        )
        assertEquals(
            expected,
            ReasoningToolSchema.toOpenAiJson(listOf(tool))
                .getJSONObject(0).getJSONObject("function")
                .getJSONObject("parameters").getJSONArray("required")
                .let { array -> (0 until array.length()).map(array::getString) }
        )
        // Both providers still receive the optional title property.
        assertTrue(ReasoningToolSchema.toGeminiJson(listOf(tool)).getJSONObject(0).getJSONObject("parameters").getJSONObject("properties").has("title"))
        assertTrue(ReasoningToolSchema.toOpenAiJson(listOf(tool)).getJSONObject(0).getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").has("title"))
    }

    private class FakeProvider(
        private val id: String,
        private val succeeds: Boolean,
        private val calls: MutableList<String>
    ) : ReasoningProvider {
        override suspend fun reason(request: ReasoningRequest): ReasoningResponse {
            calls += id
            return if (succeeds) {
                ReasoningResponse("ok", engineName = id, modelName = "model-$id", attempts = listOf(
                    ModelAttempt("model-$id", 200, providerName = id)
                ))
            } else {
                ReasoningResponse(
                    "fallback",
                    engineName = id,
                    failureReason = "HTTP 503 unavailable",
                    modelName = "model-$id",
                    attempts = listOf(ModelAttempt("model-$id", 503, providerName = id))
                )
            }
        }
    }
}
