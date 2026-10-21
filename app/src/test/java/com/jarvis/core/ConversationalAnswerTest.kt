package com.jarvis.core

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 9A: yes/no only relative to a waiting proposal, and corrections that change it instead of adding to it. */
class ConversationalAnswerTest {
    private val now = ZonedDateTime.of(2026, 10, 21, 14, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    @Test
    fun yesAndNoAreReadOnlyAgainstAWaitingProposal() = runBlocking {
        assertEquals("approval.yes", OnDeviceIntents.classify("haan, theek hai", now, "plan_approval").intent)
        assertEquals("approval.no", OnDeviceIntents.classify("nahi, haan mat karo", now, "plan_approval").intent)
        assertEquals("approval.unclear", OnDeviceIntents.classify("hmm", now, "plan_approval").intent)
        for (bare in listOf("yes", "haan", "ok", "no", "nahi")) assertEquals(bare, "approval.nothing_pending", OnDeviceIntents.classify(bare, now, null).intent)
        // A full request that starts with "ok" is still a request.
        org.junit.Assert.assertNotEquals("approval.nothing_pending", OnDeviceIntents.classify("ok remind me to stretch at 6 pm", now, null).intent)
    }

    @Test
    fun aTimeCorrectionChangesTheOneReminderAndNeverAddsAStep() {
        val steps = listOf(
            ProposedStep("reminder.schedule", mapOf("title" to "pack", "at" to "${now.with(LocalTime.of(19, 0)).toInstant().toEpochMilli()}", "zone" to "Asia/Kolkata")),
            ProposedStep("timer.start", mapOf("seconds" to "600"))
        )
        val fixed = PlanCorrection.apply(steps, "no, make it 8 pm", now)!!
        assertEquals(2, fixed.size)
        assertEquals(now.with(LocalTime.of(20, 0)).toInstant().toEpochMilli().toString(), fixed[0].args["at"])
        assertEquals(steps[1], fixed[1])
        assertEquals(now.with(LocalTime.of(21, 0)).toInstant().toEpochMilli().toString(), PlanCorrection.apply(steps, "nahi, raat 9 baje", now)!![0].args["at"])
        assertNull("no AM/PM: not applied (JARVIS asks)", PlanCorrection.apply(steps, "nahi, 8 baje", now))
        assertNull("no time at all: a plain no", PlanCorrection.apply(steps, "no", now))
        assertNull("two reminders: which one is unclear", PlanCorrection.apply(steps + steps[0].copy(args = steps[0].args + ("title" to "read")), "make it 8 pm", now))
    }
}
