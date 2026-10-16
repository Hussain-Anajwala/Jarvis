package com.jarvis.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRuleProviderTest {
    private val provider = OfflineRuleProvider()

    @Test
    fun incompleteMeetingAsksForTimeAndVenue() = runBlocking {
        val response = provider.reason(
            ReasoningRequest("I have a meeting tomorrow", emptyList())
        )

        assertEquals("What time and where is the meeting?", response.reply)
        assertTrue(response.steps.isEmpty())
        assertEquals("offline", response.engineName)
    }

    @Test
    fun meetingWithVenueOnlyAsksForTime() = runBlocking {
        val response = provider.reason(
            ReasoningRequest("I have a meeting tomorrow at college", emptyList())
        )

        assertEquals("What time is the meeting?", response.reply)
        assertTrue(response.steps.isEmpty())
    }

    @Test
    fun followUpTimeAndVenueCompletesPendingMeeting() = runBlocking {
        val response = provider.reason(
            ReasoningRequest(
                utterance = "10 AM at college",
                availableTools = listOf("meeting.create"),
                history = listOf(
                    ReasoningTurn("user", "I have a meeting tomorrow"),
                    ReasoningTurn("model", "What time and where is the meeting?")
                )
            )
        )

        val step = response.steps.single()
        assertEquals("meeting.create", step.toolName)
        assertEquals("10", step.parameters["hour"])
        assertEquals("0", step.parameters["minute"])
        assertEquals("college", step.parameters["location"])
    }
}
