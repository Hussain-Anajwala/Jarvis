package com.jarvis.core

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderCommandsTest {
    // Wednesday 21 October 2026, 14:00 in Mumbai.
    private val now = ZonedDateTime.of(2026, 10, 21, 14, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    private fun ready(text: String) = ReminderDialogue.start(text, now) as ReminderStep.Ready

    @Test
    fun theRequiredPhrasingsBecomeExactReminders() {
        ready("Remind me to stretch in 30 minutes").let {
            assertEquals("stretch", it.title); assertEquals(now.plusMinutes(30), it.at)
        }
        ready("Remind me tomorrow at 9 AM to review my project").let {
            assertEquals("review my project", it.title)
            assertEquals(now.plusDays(1).with(LocalTime.of(9, 0)), it.at)
        }
        assertEquals(now.plusMinutes(30), ready("remind me in half an hour to call mom").at)
        assertEquals(now.with(LocalTime.of(18, 30)), ready("remind me at 6:30 pm to water the plants").at)
        assertEquals(now.plusHours(2), ready("remind me to drink water after 2 hours").at)
        // 9 AM has passed today, so "at 9 am" with no day means tomorrow.
        assertEquals(now.plusDays(1).with(LocalTime.of(9, 0)), ready("remind me at 9 am to pay rent").at)
        assertEquals(now.with(LocalTime.of(20, 0)), ready("remind me tonight at 8 to pack").at)
    }

    @Test
    fun missingDetailsAreAskedForNeverGuessed() {
        val noTime = ReminderDialogue.start("remind me to buy milk", now) as ReminderStep.Ask
        assertEquals(ReminderSlot.TIME, noTime.slot)
        assertEquals(now.plusMinutes(20), (ReminderDialogue.answer(noTime.draft, noTime.slot, "in 20 minutes", now) as ReminderStep.Ready).at)

        val ampm = ReminderDialogue.start("remind me at 6 to call Asha", now) as ReminderStep.Ask
        assertEquals(ReminderSlot.AMPM, ampm.slot)
        assertEquals(18, (ReminderDialogue.answer(ampm.draft, ampm.slot, "evening", now) as ReminderStep.Ready).at.hour)

        val dayOnly = ReminderDialogue.start("remind me tomorrow to submit the form", now) as ReminderStep.Ask
        assertEquals(ReminderSlot.TIME, dayOnly.slot)
        val done = ReminderDialogue.answer(dayOnly.draft, dayOnly.slot, "at 10 am", now) as ReminderStep.Ready
        assertEquals(now.plusDays(1).with(LocalTime.of(10, 0)), done.at)

        val noTitle = ReminderDialogue.start("remind me in 10 minutes", now) as ReminderStep.Ask
        assertEquals(ReminderSlot.TITLE, noTitle.slot)
        assertEquals("stretch", (ReminderDialogue.answer(noTitle.draft, noTitle.slot, "to stretch", now) as ReminderStep.Ready).title)

        val unclear = ReminderDialogue.answer(noTime.draft, ReminderSlot.TIME, "soonish", now) as ReminderStep.Ask
        assertEquals(ReminderSlot.TIME, unclear.slot)
        assertEquals(ReminderStep.Cancelled, ReminderDialogue.answer(noTime.draft, ReminderSlot.TIME, "never mind", now))
    }

    @Test
    fun commandsAreRecognisedAndOtherRequestsAreNot() {
        assertTrue(ReminderCommands.parse("List my pending reminders") is ReminderCommand.List)
        assertTrue(ReminderCommands.parse("what reminders do I have") is ReminderCommand.List)
        assertEquals(ReminderCommand.Cancel(true, ""), ReminderCommands.parse("Cancel the reminder I just created"))
        assertEquals(ReminderCommand.Cancel(false, "stretch"), ReminderCommands.parse("cancel my stretch reminder"))
        assertEquals(ReminderCommand.PauseGoal(""), ReminderCommands.parse("Pause this plan"))
        assertEquals(ReminderCommand.ResumeGoal("study"), ReminderCommands.parse("Resume my study plan"))
        listOf("set an alarm for 7", "cancel my 7 am alarm", "what's the weather", "I have a meeting tomorrow at 10 at college")
            .forEach { assertNull(it, ReminderCommands.parse(it)) }
    }

    @Test
    fun aGoalAsksForDetailsProposesABoundedPlanAndWaitsForYes() {
        // "next Sunday" from Wednesday 21 Oct is Sunday 25 Oct.
        val first = GoalDialogue.begin("Help me prepare for my exam next Sunday", now) as GoalStepResult.Ask
        assertEquals(GoalSlot.TOPICS, first.slot)
        assertEquals(java.time.LocalDate.of(2026, 10, 25), first.draft.due)
        val time = GoalDialogue.answer(first.draft, first.slot, "algebra, physics and chemistry", now) as GoalStepResult.Ask
        assertEquals(GoalSlot.TIME, time.slot)
        val ampm = GoalDialogue.answer(time.draft, time.slot, "at 7", now) as GoalStepResult.Ask
        assertEquals(GoalSlot.AMPM, ampm.slot)
        val proposal = GoalDialogue.answer(ampm.draft, ampm.slot, "evening", now) as GoalStepResult.Ask
        assertEquals(GoalSlot.APPROVE, proposal.slot)
        assertTrue(proposal.question.contains("3 daily study reminder(s) at 7:00 PM"))
        // Changing the time re-proposes; nothing is scheduled before "yes".
        val changed = GoalDialogue.answer(proposal.draft, GoalSlot.APPROVE, "make it 8 pm", now) as GoalStepResult.Ask
        assertTrue(changed.question.contains("8:00 PM"))
        val ready = GoalDialogue.answer(changed.draft, GoalSlot.APPROVE, "yes", now) as GoalStepResult.Ready
        assertEquals(3, ready.sessions.size) // Thu–Sat
        assertEquals(listOf("algebra", "physics", "chemistry"), ready.sessions.map { it.inputs["topic"] })
        assertEquals(GoalStepResult.Cancelled, GoalDialogue.answer(proposal.draft, GoalSlot.APPROVE, "no", now))
    }

    @Test
    fun aGoalAsksForTheDateAndRefusesOneThatIsTooSoon() {
        val noDate = GoalDialogue.begin("help me prepare for my driving test", now) as GoalStepResult.Ask
        assertEquals(GoalSlot.DUE, noDate.slot)
        assertEquals("driving test", noDate.draft.subject)
        assertTrue(GoalDialogue.answer(noDate.draft, GoalSlot.DUE, "tomorrow", now) is GoalStepResult.Explain)
        val far = GoalDialogue.answer(noDate.draft, GoalSlot.DUE, "in 30 days", now) as GoalStepResult.Ask
        val sessions = GoalDialogue.sessions(far.draft.copy(topics = listOf("theory"), time = LocalTime.of(7, 0)), now)
        assertEquals("at most ${GoalDialogue.MAX_SESSIONS} sessions", GoalDialogue.MAX_SESSIONS, sessions.size)
        assertNull(GoalDialogue.begin("what's the weather", now))
    }
}
