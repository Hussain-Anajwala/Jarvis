package com.jarvis.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0: a finished timer alerts once, audibly when Android allows it, and JARVIS says so when it doesn't. */
class TimerAlertTest {
    private val t0 = 1_800_000_000_000L

    @Test
    fun onlyTheFirstDeliveryOfARunningTimerRings() {
        val (list, timer) = TimerBook.start(emptyList(), "", 10, t0)
        assertTrue(TimerBook.startsRinging(list, timer.id))
        val ringing = TimerBook.transition(list, timer.id, JarvisTimer.State.RINGING, t0 + 10_000)
        // A duplicate broadcast, a retry, or recovery re-firing: no second alert.
        assertFalse(TimerBook.startsRinging(ringing, timer.id))
        // Cancelled before it ended: the late alarm never alerts.
        val cancelled = TimerBook.transition(list, timer.id, JarvisTimer.State.CANCELLED, t0 + 5_000)
        assertFalse(TimerBook.startsRinging(cancelled, timer.id))
        assertFalse(TimerBook.startsRinging(list, 999)) // unknown id: nothing
    }

    @Test
    fun severalTimersKeepTheirOwnIdentities() {
        val (a, first) = TimerBook.start(emptyList(), "tea", 60, t0)
        val (b, second) = TimerBook.start(a, "pasta", 600, t0 + 1)
        val afterFirst = TimerBook.transition(b, first.id, JarvisTimer.State.RINGING, t0 + 60_000)
        assertTrue(TimerBook.startsRinging(afterFirst, second.id))
        assertEquals(JarvisTimer.State.RUNNING, afterFirst.first { it.id == second.id }.state)
        assertTrue(JarvisTimerEngine.notificationId(first.id) != JarvisTimerEngine.notificationId(second.id))
        // The countdown and the alert never share an id (the shared id let the countdown's timeout cancel the alert).
        assertTrue(JarvisTimerEngine.countdownId(first.id) != JarvisTimerEngine.notificationId(first.id))
        val recorded = TimerBook.recordAlert(afterFirst, first.id, "alert shown with sound requested")
        assertEquals("alert shown with sound requested", JarvisTimer.listFromJson(JarvisTimer.listToJson(recorded)).first { it.id == first.id }.alert)
    }

    private fun problems(
        notificationsOn: Boolean = true, importance: Int? = 4, hasSound: Boolean = true, soundWanted: Boolean = true,
        volume: Int? = 5, dnd: Boolean = false, exact: Boolean = true
    ) = TimerAlerts.problems(notificationsOn, importance, hasSound, soundWanted, volume, dnd, exact)

    @Test
    fun whatStopsTheAlertBeingHeardIsNamed() {
        assertEquals(emptyList<TimerAlerts.Problem>(), problems())
        assertEquals(listOf(TimerAlerts.Problem.NOTIFICATIONS_OFF), problems(notificationsOn = false))
        assertEquals(listOf(TimerAlerts.Problem.CHANNEL_OFF), problems(importance = 2))
        assertEquals(listOf(TimerAlerts.Problem.CHANNEL_SILENT), problems(hasSound = false))
        assertEquals(listOf(TimerAlerts.Problem.SOUND_SETTING_OFF), problems(soundWanted = false, hasSound = false))
        assertEquals(listOf(TimerAlerts.Problem.ALARM_VOLUME_ZERO), problems(volume = 0))
        assertEquals(listOf(TimerAlerts.Problem.DND_BLOCKS_ALARMS), problems(dnd = true))
        assertEquals(listOf(TimerAlerts.Problem.INEXACT), problems(exact = false))
        assertFalse(TimerAlerts.Problem.INEXACT.audible)
    }

    @Test
    fun theRecordSaysWhatAndroidAcceptedNotWhatWasHeard() {
        assertEquals("alert shown with sound requested", TimerAlerts.outcome(true, emptyList(), sound = true))
        assertEquals("alert not shown: notifications for JARVIS are off", TimerAlerts.outcome(false, listOf(TimerAlerts.Problem.NOTIFICATIONS_OFF), true))
        assertEquals("alert shown without sound: the phone's alarm volume is 0", TimerAlerts.outcome(true, listOf(TimerAlerts.Problem.ALARM_VOLUME_ZERO), true))
        assertEquals("jarvis.timer.alert.sv", TimerAlerts.channelId(sound = true, vibrate = true))
        assertEquals("jarvis.timer.alert.n", TimerAlerts.channelId(sound = false, vibrate = false))
    }

    @Test
    fun theStartReplyNeverPromisesASoundAndroidWillBlock() {
        val zone = ZoneId.of("Asia/Kolkata")
        val start = ZonedDateTime.of(2026, 10, 21, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
        val timer = JarvisTimer(1, "", 600, start, JarvisTimer.State.RUNNING)
        val ok = TimerReplies.started(JarvisTimerEngine.Started(timer, false, true, emptyList()), true, zone)
        assertFalse(ok.contains("won't hear"))
        val dnd = TimerReplies.started(JarvisTimerEngine.Started(timer, false, true, listOf(TimerAlerts.Problem.DND_BLOCKS_ALARMS)), true, zone)
        assertTrue(dnd, dnd.contains("You won't hear it when it finishes: Do Not Disturb is blocking alarms."))
    }
}
