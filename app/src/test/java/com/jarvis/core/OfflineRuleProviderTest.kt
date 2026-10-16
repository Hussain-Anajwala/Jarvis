package com.jarvis.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

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
        assertEquals(LocalDate.now().plusDays(1).toString(), step.parameters["date"])
    }

    @Test
    fun dentistVisitWithApproximateTimeAsksOneSpecificClarification() = runBlocking {
        val response = provider.reason(
            ReasoningRequest(
                "put a dentist visit on my calendar friday around 6, clinic near Dadar",
                listOf("meeting.create")
            )
        )

        assertEquals("What time do you mean—6 AM or 6 PM?", response.reply)
        assertTrue(response.steps.isEmpty())
    }

    @Test
    fun clarifiedDentistVisitKeepsTitleVenueAndRequestedWeekday() = runBlocking {
        val original = "put a dentist visit on my calendar friday around 6, clinic near Dadar"
        val question = provider.reason(ReasoningRequest(original, listOf("meeting.create")))
        val response = provider.reason(
            ReasoningRequest(
                utterance = "6 PM",
                availableTools = listOf("meeting.create"),
                history = listOf(
                    ReasoningTurn("user", original),
                    ReasoningTurn("model", question.reply)
                )
            )
        )

        val step = response.steps.single()
        assertEquals("Dentist visit", step.parameters["title"])
        assertEquals("18", step.parameters["hour"])
        assertEquals("0", step.parameters["minute"])
        assertEquals("clinic near Dadar", step.parameters["location"])
        assertEquals(
            LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)).toString(),
            step.parameters["date"]
        )
    }

    @Test
    fun followUpTitleIsUsedInsteadOfDefaultMeetingTitle() = runBlocking {
        val response = provider.reason(
            ReasoningRequest(
                utterance = "Project Review, 10 AM at college",
                availableTools = listOf("meeting.create"),
                history = listOf(
                    ReasoningTurn("user", "I have a meeting tomorrow"),
                    ReasoningTurn("model", "What time and where is the meeting?")
                )
            )
        )

        assertEquals("Project Review", response.steps.single().parameters["title"])
    }
}
