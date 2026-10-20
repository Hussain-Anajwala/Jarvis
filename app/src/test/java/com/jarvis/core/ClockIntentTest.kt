package com.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 0 items 3.6 and 3.7: set, cancel, dismiss, and snooze are separate, and stopping never creates. */
class ClockIntentTest {
    @Test
    fun turningARingingAlarmOffIsADismissalInEveryPhrasing() {
        listOf(
            "turn the alarm off", "alarm off", "stop the alarm", "shut up alarm", "alarm band karo", "alarm band kar do",
            "the alarm is ringing, turn it off", "dismiss the alarm that's going off"
        ).forEach { assertEquals(it, ClockCommand.DismissRingingAlarm, ClockCommandParser.parse(it)) }
    }

    @Test
    fun snoozeIsItsOwnActionWithAnOptionalDuration() {
        assertEquals(ClockCommand.SnoozeAlarm(null), ClockCommandParser.parse("snooze"))
        assertEquals(ClockCommand.SnoozeAlarm(null), ClockCommandParser.parse("snooze the alarm"))
        assertEquals(ClockCommand.SnoozeAlarm(10), ClockCommandParser.parse("snooze the alarm for 10 minutes"))
        assertEquals(ClockCommand.SnoozeAlarm(5), ClockCommandParser.parse("snooze 5 more minutes"))
        assertEquals(ClockPlan.SnoozeAlarm(10), ClockPlanner.plan(ClockCommand.SnoozeAlarm(10), emptyList(), 0L))
    }

    @Test
    fun questionsAndCreationAreNotCancellations() {
        listOf(
            "when does my alarm go off", "what time will the alarm go off", "set an alarm for 7 am",
            "set a timer for 5 minutes", "is my alarm on", "set an alarm to remind me to switch off the geyser"
        ).forEach { assertNull(it, ClockCommandParser.parse(it)) }
        assertEquals(ClockCommand.CancelAlarm(7, 0, latest = false), ClockCommandParser.parse("turn off my 7 am alarm"))
        assertEquals(ClockCommand.CancelTimer(false), ClockCommandParser.parse("timer hatao please, the pasta one"))
    }

    @Test
    fun aModelsSetAlarmIsBlockedWhenTheUserAskedToStop() {
        listOf("turn the alarm off", "stop the alarm", "snooze it", "cancel my timer", "alarm band karo", "switch off the alarm")
            .forEach { assertTrue(it, ClockIntentGuard.blocksCreation(it)) }
        listOf("set an alarm for 7", "wake me at 6", "start a 5 minute timer", "set an alarm to stop studying at 9")
            .forEach { assertFalse(it, ClockIntentGuard.blocksCreation(it)) }
    }

    private fun running(id: Int, seconds: Int, startedAt: Long) = JarvisTimer(id, "", seconds, startedAt, JarvisTimer.State.RUNNING)

    @Test
    fun jarvisTimersAreCancelledByIdAndSeveralAskWhichOne() {
        val one = running(1, 300, 0L)
        assertEquals(ClockPlan.StopJarvisTimer(one), ClockPlanner.plan(ClockCommand.CancelTimer(false), emptyList(), 1_000L, listOf(one)))
        val two = running(2, 600, 10_000L)
        val choose = ClockPlanner.plan(ClockCommand.CancelTimer(false), emptyList(), 20_000L, listOf(one, two))
        assertTrue(choose is ClockPlan.ChooseJarvisTimer)
        assertEquals(listOf(2, 1), (choose as ClockPlan.ChooseJarvisTimer).timers.map { it.id })
        // "the timer I just set" is the newest.
        assertEquals(ClockPlan.StopJarvisTimer(two), ClockPlanner.plan(ClockCommand.CancelTimer(true), emptyList(), 20_000L, listOf(one, two)))
        // A cancelled timer is never a target again; with nothing left, Clock's fallback is explained.
        val cancelled = TimerBook.transition(listOf(one), 1, JarvisTimer.State.CANCELLED, 2_000L)
        assertTrue(ClockPlanner.plan(ClockCommand.CancelTimer(false), emptyList(), 3_000L, cancelled) is ClockPlan.ShowTimers)
        // "stop the timer" while one rings silences that one.
        val ringing = TimerBook.transition(listOf(one), 1, JarvisTimer.State.RINGING, 300_000L)
        assertEquals(1, (ClockPlanner.plan(ClockCommand.DismissRingingTimer, emptyList(), 300_500L, ringing) as ClockPlan.StopJarvisTimer).timer.id)
    }

    @Test
    fun timerStateChangesAreOneWayAndSurviveARestart() {
        val (list, timer) = TimerBook.start(emptyList(), "pasta", 60, now = 0L)
        assertEquals(60_000L, timer.endsAt)
        val cancelled = TimerBook.transition(list, timer.id, JarvisTimer.State.CANCELLED, 1_000L)
        // Once cancelled it can't ring.
        assertEquals(JarvisTimer.State.CANCELLED, TimerBook.transition(cancelled, timer.id, JarvisTimer.State.RINGING, 60_000L).single().state)
        assertEquals(cancelled, JarvisTimer.listFromJson(JarvisTimer.listToJson(cancelled)))

        val future = running(1, 600, 0L)
        val justDue = running(2, 60, 0L)
        val long = running(3, 60, -3_600_000L)
        val recovery = TimerBook.recover(listOf(future, justDue, long), now = 90_000L)
        assertEquals(listOf(1, 2), recovery.reschedule.map { it.id })
        assertEquals(listOf(3), recovery.missed.map { it.id })
        assertEquals(JarvisTimer.State.MISSED, recovery.timers.first { it.id == 3 }.state)
    }
}
