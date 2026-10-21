package com.jarvis.core

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanParserTest {
    private val now = ZonedDateTime.of(2026, 10, 21, 14, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    @Test
    fun aMultiStepRequestBecomesOrderedTypedSteps() {
        val parsed = PlanParser.parse("Remind me to drink water at 7 pm and start a 25 minute timer, then remind me to review notes at 9 pm", now)!!
        assertEquals(listOf("reminder.schedule", "timer.start", "reminder.schedule"), parsed.steps.map { it.tool })
        assertEquals("drink water", parsed.steps[0].args["title"])
        assertEquals(now.with(LocalTime.of(19, 0)).toInstant().toEpochMilli().toString(), parsed.steps[0].args["at"])
        assertEquals("1500", parsed.steps[1].args["seconds"])
        assertEquals("review notes", parsed.steps[2].args["title"])
        assertEquals(listOf("reminders.cancel_all", "timer.start"),
            PlanParser.parse("cancel all my reminders and set a timer for 10 minutes", now)!!.steps.map { it.tool })
    }

    @Test
    fun aMissingTimeIsAskedForBeforeAnythingIsPlanned() {
        val parsed = PlanParser.parse("remind me to stretch and start a 5 minute timer", now)!!
        assertTrue(parsed.missing!!.question.startsWith("When should I remind you to stretch"))
        val filled = PlanParser.clause(parsed.missing!!.text + " in 30 minutes", now) as PlanParser.Clause.Step
        assertEquals(now.plusMinutes(30).toInstant().toEpochMilli().toString(), filled.step.args["at"])
    }

    @Test
    fun singleActionsAndUnsupportedClausesAreNotPlans() {
        listOf(
            "remind me to buy bread and milk at 6 pm",           // one reminder
            "remind me at 6 pm to call mom",                     // one clause
            "set a 5 minute timer and send Asha a message",      // messaging is not a plan tool
            "what's the weather and set a 5 minute timer"
        ).forEach { assertNull(it, PlanParser.parse(it, now)) }
    }
}
