package com.jarvis.core

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockAndEmailDialogueTest {
    // ---- alarms and timers ----

    @Test
    fun cancellationPhrasesBecomeClockCommandsAndCreationDoesNot() {
        assertEquals(ClockCommand.CancelAlarm(null, null, latest = true), ClockCommandParser.parse("Cancel the alarm I just set."))
        assertEquals(ClockCommand.CancelAlarm(7, 0, latest = false), ClockCommandParser.parse("Cancel my alarm for 7 AM."))
        assertEquals(ClockCommand.CancelAlarm(19, 30, latest = false), ClockCommandParser.parse("turn off the 7:30 pm alarm"))
        assertEquals(ClockCommand.CancelTimer(latest = false), ClockCommandParser.parse("Cancel the timer."))
        assertEquals(ClockCommand.CancelTimer(latest = true), ClockCommandParser.parse("Cancel the timer I started a few minutes ago."))
        assertEquals(ClockCommand.DismissRingingAlarm, ClockCommandParser.parse("Dismiss the ringing alarm."))
        assertEquals(ClockCommand.DismissRingingAlarm, ClockCommandParser.parse("stop the alarm"))
        assertEquals(ClockCommand.DismissRingingTimer, ClockCommandParser.parse("the timer is going off, stop it"))
        assertNull(ClockCommandParser.parse("Set an alarm for 7 AM to remind me to stop studying"))
        assertNull(ClockCommandParser.parse("Cancel my meeting tomorrow"))
        assertNull(ClockCommandParser.parse("cancel the alarm and the timer")) // both: ask instead of guessing
    }

    private fun at(hour: Int, minute: Int, dayOffset: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 18, hour, minute, 0); set(Calendar.MILLISECOND, 0); add(Calendar.DAY_OF_YEAR, dayOffset)
    }.timeInMillis

    private fun alarm(id: String, hour: Int, minute: Int, setAt: Long) = ClockItem(id, ClockItem.Kind.ALARM, setAt, hour = hour, minute = minute, label = "JARVIS alarm")
    private fun timer(id: String, seconds: Int, startedAt: Long) = ClockItem(id, ClockItem.Kind.TIMER, startedAt, seconds = seconds)

    @Test
    fun theAlarmIJustSetIsTheNewestUpcomingOneAndAStatedTimeWorksForAnyAlarm() {
        val now = at(20, 0)
        val history = listOf(alarm("a", 7, 0, at(19, 0)), alarm("b", 6, 30, at(19, 55)))
        val latest = ClockPlanner.plan(ClockCommand.CancelAlarm(null, null, latest = true), history, now) as ClockPlan.DismissAlarmAt
        assertEquals(6 to 30, latest.hour to latest.minute)
        assertEquals("b", latest.item?.id)
        val byTime = ClockPlanner.plan(ClockCommand.CancelAlarm(7, 0, false), history, now) as ClockPlan.DismissAlarmAt
        assertEquals("a", byTime.item?.id)
        val notMine = ClockPlanner.plan(ClockCommand.CancelAlarm(9, 15, false), history, now) as ClockPlan.DismissAlarmAt
        assertNull("an alarm JARVIS did not set can still be cancelled by its time", notMine.item)
    }

    @Test
    fun twoUpcomingAlarmsAskWhichOneAndAPickContinuesTheCancellation() {
        val now = at(20, 0)
        val history = listOf(alarm("a", 7, 0, at(19, 0)), alarm("b", 6, 30, at(19, 55)), alarm("old", 8, 0, at(7, 0)).copy(cancelledAt = at(7, 5)))
        val plan = ClockPlanner.plan(ClockCommand.CancelAlarm(null, null, latest = false), history, now) as ClockPlan.Choose
        assertEquals(listOf("b", "a"), plan.items.map { it.id })
        assertTrue(plan.question.contains("1: 6:30 AM alarm"))
        assertEquals(1, (ChoiceAnswers.pick("the second one", plan.items.map { it.describe(now) }) as ChoicePick.Option).index)
        val picked = ClockPlanner.planFor(plan.items[1], now) as ClockPlan.DismissAlarmAt
        assertEquals(7 to 0, picked.hour to picked.minute)
    }

    @Test
    fun withoutARecordJarvisAsksForTheTimeInsteadOfGuessing() {
        val plan = ClockPlanner.plan(ClockCommand.CancelAlarm(null, null, latest = true), emptyList(), at(20, 0))
        assertTrue(plan is ClockPlan.Explain)
        assertTrue((plan as ClockPlan.Explain).message.contains("cancel my 7 AM alarm"))
    }

    @Test
    fun aRunningTimerCannotBeCancelledByAnyAppSoClockIsOpenedAndARingingOneIsDismissed() {
        val now = at(20, 0)
        val running = timer("t", 600, now - 120_000)
        val plan = ClockPlanner.plan(ClockCommand.CancelTimer(latest = true), listOf(running), now)
        assertTrue(plan is ClockPlan.ShowTimers)
        assertTrue((plan as ClockPlan.ShowTimers).reason.contains("Android doesn't let other apps cancel a running Clock timer"))
        val finished = timer("f", 60, now - 90_000)
        assertTrue(ClockPlanner.plan(ClockCommand.CancelTimer(latest = true), listOf(finished), now) is ClockPlan.DismissRingingTimer)
        assertTrue(ClockPlanner.plan(ClockCommand.DismissRingingTimer, emptyList(), now) is ClockPlan.DismissRingingTimer)
        val two = ClockPlanner.plan(ClockCommand.CancelTimer(latest = false), listOf(running, timer("u", 300, now - 30_000)), now)
        assertTrue(two is ClockPlan.Choose)
    }

    @Test
    fun clockHistoryRoundTripsAndAOneTimeAlarmRingsAtTheNextOccurrence() {
        val evening = at(21, 0)
        val item = alarm("x", 7, 0, evening)
        assertEquals(at(7, 0, dayOffset = 1), item.alarmTriggerAt())
        val restored = ClockItem.listFromJson(ClockItem.listToJson(listOf(item, timer("t", 90, evening).copy(cancelledAt = evening + 5))))
        assertEquals(listOf(item, timer("t", 90, evening).copy(cancelledAt = evening + 5)), restored)
        assertEquals(emptyList<ClockItem>(), ClockItem.listFromJson("not json"))
        assertEquals("7:00 AM", ClockText.time(7, 0))
        assertEquals("12:30 PM", ClockText.time(12, 30))
    }

    // ---- email drafting ----

    @Test
    fun aLeaveApplicationAsksForDatesThenReasonThenRecipientWithoutInventingAnything() {
        val start = EmailDialogue.start("Write a professional email for a leave application.")!!
        assertEquals(EmailRequest.Kind.LEAVE, start.kind)
        assertNull("\"for a leave application\" names nobody", start.recipient)
        val ask1 = EmailDialogue.next(start) as EmailStep.Ask
        assertEquals(EmailSlot.DATES, ask1.slot)
        assertEquals("Sure. What dates do you need leave for?", ask1.question)
        val ask2 = EmailDialogue.answer(ask1.request, ask1.slot, "From 20 October to 22 October.") as EmailStep.Ask
        assertEquals(EmailSlot.REASON, ask2.slot)
        assertEquals("From 20 October to 22 October", ask2.request.dates)
        val ask3 = EmailDialogue.answer(ask2.request, ask2.slot, "I have a medical appointment.") as EmailStep.Ask
        assertEquals(EmailSlot.RECIPIENT, ask3.slot)
        assertEquals("I have a medical appointment", ask3.request.reason)
        val ready = EmailDialogue.answer(ask3.request, ask3.slot, "my manager") as EmailStep.Ready
        assertEquals("my manager", ready.request.recipient)
        assertTrue(EmailDialogue.isRole("my manager"))
    }

    @Test
    fun detailsGivenUpFrontAreNeverAskedAgain() {
        val start = EmailDialogue.start("Draft a leave application email to Asha Rao for 20 to 22 October because I have a medical appointment")!!
        assertEquals("Asha Rao", start.recipient)
        assertTrue(start.dates!!.contains("20 to 22 October"))
        assertEquals("I have a medical appointment", start.reason)
        assertTrue(EmailDialogue.next(start) is EmailStep.Ready)
        // A single answer can fill two questions at once.
        val asked = EmailDialogue.next(EmailDialogue.start("write a leave request email")!!) as EmailStep.Ask
        val both = EmailDialogue.answer(asked.request, asked.slot, "tomorrow, because my sister is getting married")
        val recipientQuestion = both as EmailStep.Ask
        assertEquals(EmailSlot.RECIPIENT, recipientQuestion.slot)
        assertEquals("my sister is getting married", recipientQuestion.request.reason)
    }

    @Test
    fun anAnswerWithoutDatesIsAskedAgainAndCancelStops() {
        val asked = EmailDialogue.next(EmailDialogue.start("write a leave application")!!) as EmailStep.Ask
        val again = EmailDialogue.answer(asked.request, asked.slot, "I'm not sure yet") as EmailStep.Ask
        assertEquals(EmailSlot.DATES, again.slot)
        assertTrue(again.question.contains("for example"))
        assertNull(again.request.dates)
        assertEquals(EmailStep.Cancelled, EmailDialogue.answer(asked.request, asked.slot, "never mind"))
        val skipped = EmailDialogue.answer(
            EmailRequest(EmailRequest.Kind.LEAVE, dates = "tomorrow", reason = "fever"), EmailSlot.RECIPIENT, "skip"
        ) as EmailStep.Ready
        assertEquals("", skipped.request.recipient)
    }

    @Test
    fun aDurationIsNotADateAndOtherRequestsAreNotEmails() {
        val start = EmailDialogue.start("write a leave application for 2 days")!!
        assertNull("\"for 2 days\" is not a date", start.dates)
        assertNull(EmailDialogue.start("how many unread emails do I have"))
        assertNull(EmailDialogue.start("what's the weather"))
        assertEquals(EmailRequest.Kind.GENERAL, EmailDialogue.start("compose an email to my professor about the project deadline")!!.kind)
    }

    @Test
    fun theFinalLeaveEmailIsCompleteAndUsesOnlyTheUsersFacts() {
        val request = EmailRequest(EmailRequest.Kind.LEAVE, dates = "From 20 October to 22 October", reason = "I have a medical appointment", recipient = "my manager")
        val draft = EmailWriter.leave(request, greetingName = null, senderName = "Hussain", signOff = null)
        assertEquals("Leave application: From 20 October to 22 October", draft.subject)
        assertFalse("no question in the subject", draft.subject.contains("?"))
        assertTrue(draft.body.startsWith("Dear Sir/Madam,"))
        assertTrue(draft.body.contains("I am writing to request leave From 20 October to 22 October as I have a medical appointment."))
        assertTrue(draft.body.trimEnd().endsWith("Sincerely,\nHussain"))
        assertFalse(draft.body.contains("?"))
        assertFalse(Regex("""\[[^\]]+\]""").containsMatchIn(draft.body))
        val named = EmailWriter.leave(request.copy(dates = "20 to 22 October", reason = "a family wedding"), "Asha", null, "Regards,")
        assertTrue(named.body.startsWith("Dear Asha,"))
        assertTrue(named.body.contains("request leave from 20 to 22 October due to a family wedding."))
        assertTrue("no invented sender name", named.body.trimEnd().endsWith("Regards,"))
    }

    @Test
    fun aModelDraftIsRejectedIfItDropsTheDatesOrAsksBack() {
        val request = EmailRequest(EmailRequest.Kind.LEAVE, dates = "20 to 22 October", reason = "medical appointment")
        assertTrue(EmailWriter.acceptable(EmailDraft("Leave request: 20–22 October", "Dear Sir,\nI request leave from 20 to 22 October for a medical appointment."), request))
        assertFalse(EmailWriter.acceptable(EmailDraft("Leave request", "Dear Sir,\nI request leave on [dates] for [reason]."), request))
        assertFalse(EmailWriter.acceptable(EmailDraft("Could you share the dates?", "Please provide the dates and reason."), request))
        assertFalse(EmailWriter.acceptable(EmailDraft("Leave request", "Dear Sir,\nI request leave next week."), request))
        assertTrue(EmailWriter.factsPrompt(request, null, null, null).contains("add no others"))
    }

    @Test
    fun anEmptyRecipientStillHandsOffSafely() {
        val uri = EmailComposer.mailto("", EmailDraft("Leave application", "Dear Sir/Madam,"))!!
        assertTrue(uri.startsWith("mailto:?subject="))
        assertNull(EmailComposer.mailto("a@b.com?bcc=x@y.com", EmailDraft("s", "b")))
    }
}
