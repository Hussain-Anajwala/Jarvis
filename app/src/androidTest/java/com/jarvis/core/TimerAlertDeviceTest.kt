package com.jarvis.core

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On the phone, the real engine (AlarmManager, NotificationManager) with its own records file: a timer is armed with a
 * countdown notification; when it rings the countdown is gone and one alert is posted on the sound channel; a repeated
 * delivery posts nothing new; Stop removes the alert and the alarm; a timer cancelled early never alerts. The user's
 * timers are untouched (ids start at 901 in a separate file). Proves what Android accepted, not that it was heard.
 */
@RunWith(AndroidJUnit4::class)
class TimerAlertDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "jarvis_timers_device_test"
    private lateinit var engine: JarvisTimerEngine
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putString("timers", JarvisTimer.listToJson(listOf(JarvisTimer(900, "seed", 1, 0, JarvisTimer.State.FINISHED, 1)))).commit()
        engine = JarvisTimerEngine(context, prefsName)
    }

    @After
    fun tearDown() {
        engine.all().filter { it.active }.forEach { engine.stop(it.id) }
        context.deleteSharedPreferences(prefsName)
    }

    private fun active(id: Int) = manager.activeNotifications.firstOrNull { it.id == id }

    @Test
    fun ringsOnceOnTheAlertChannelAndStopsCleanly() {
        val started = engine.startOnce(600, "device test")
        val timer = started.timer
        assertTrue(timer.id > 900)
        assertTrue("alarm set with Android", engine.isArmed(timer.id))
        val notificationsOn = engine.notificationsAllowed()
        if (notificationsOn) assertNotNull("countdown shown", active(JarvisTimerEngine.countdownId(timer.id)))

        engine.ring(timer.id)
        val ringing = engine.all().first { it.id == timer.id }
        assertEquals(JarvisTimer.State.RINGING, ringing.state)
        assertNotNull("alert outcome recorded", ringing.alert)
        if (notificationsOn) {
            val alert = active(JarvisTimerEngine.notificationId(timer.id))
            assertNotNull("alert posted", alert)
            assertEquals(engine.alertChannelId(), alert!!.notification.channelId)
            assertEquals(null, active(JarvisTimerEngine.countdownId(timer.id)))
            val channel = manager.getNotificationChannel(engine.alertChannelId())
            assertTrue("alert channel can make a sound", channel.importance >= NotificationManager.IMPORTANCE_DEFAULT)
            assertEquals(engine.alertSound, channel.sound != null)
            // A repeated delivery (duplicate broadcast, recovery) posts nothing new.
            val postTime = alert.postTime
            Thread.sleep(50)
            engine.ring(timer.id)
            assertEquals(postTime, active(JarvisTimerEngine.notificationId(timer.id))!!.postTime)
        }

        val stopped = engine.stop(timer.id)
        assertTrue(stopped is JarvisTimerEngine.StopResult.Stopped && stopped.wasRinging)
        assertEquals(null, active(JarvisTimerEngine.notificationId(timer.id)))
        assertFalse(engine.isArmed(timer.id))
        assertEquals(JarvisTimer.State.FINISHED, engine.all().first { it.id == timer.id }.state)
    }

    @Test
    fun aTimerCancelledEarlyNeverAlerts() {
        val timer = engine.startOnce(600, "device test").timer
        assertTrue(engine.stop(timer.id) is JarvisTimerEngine.StopResult.Stopped)
        assertFalse(engine.isArmed(timer.id))
        engine.ring(timer.id) // a late alarm after cancellation
        assertEquals(JarvisTimer.State.CANCELLED, engine.all().first { it.id == timer.id }.state)
        assertEquals(null, active(JarvisTimerEngine.notificationId(timer.id)))
        assertEquals(null, active(JarvisTimerEngine.countdownId(timer.id)))
    }
}
