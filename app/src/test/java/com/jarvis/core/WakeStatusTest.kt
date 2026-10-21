package com.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0 wake word: the chip says what is really happening, Android's silence is noticed, and trials are counted fairly. */
class WakeStatusTest {
    @Test
    fun theChipNeverClaimsListeningItIsNotDoing() {
        assertEquals(WakeStatus.OFF, WakeStatus.resolve(enabled = false, running = true, listening = true, silenced = false))
        assertEquals(WakeStatus.NOT_RUNNING, WakeStatus.resolve(true, running = false, listening = false, silenced = false))
        assertEquals(WakeStatus.LISTENING, WakeStatus.resolve(true, true, listening = true, silenced = false))
        assertEquals(WakeStatus.SILENCED, WakeStatus.resolve(true, true, listening = true, silenced = true))
        assertEquals(WakeStatus.PAUSED, WakeStatus.resolve(true, true, listening = false, silenced = false))
    }

    @Test
    fun twoSecondsOfDigitalSilenceMeansTheMicIsWithheld() {
        val watch = SilenceWatch(chunksToFlag = 25)
        val zero = ShortArray(1280)
        val quietRoom = ShortArray(1280) { if (it % 7 == 0) 3 else -2 }
        repeat(24) { assertFalse(watch.observe(zero)) }
        assertTrue(watch.observe(zero))
        assertFalse(watch.observe(quietRoom)) // any real room noise clears it
    }

    @Test
    fun trialsCountEachDetectionOnce() {
        val t = 1_000_000L
        fun d(at: Long, on: Boolean) = WakeWordEvents.Detection(t + at, 0.8f, on)
        val log = listOf(d(-5_000, true), d(1_000, true), d(2_000, true), d(3_000, false), d(10_000, true), d(11_000, true))
        val r = WakeTrial.summarize(log, since = t, attemptsOn = 3, attemptsOff = 2, quiet = listOf((t + 9_000)..(t + 12_000)))
        assertEquals(2, r.detectedOn) // the detection before the session is ignored; two in quiet are not hits
        assertEquals(1, r.missesOn)
        assertEquals(1, r.detectedOff)
        assertEquals(1, r.missesOff)
        assertEquals(2, r.falseActivations)
        // More detections than attempts: the extra ones are false activations, never extra hits.
        val extra = WakeTrial.summarize(log, since = t, attemptsOn = 1, attemptsOff = 0, quiet = emptyList())
        assertEquals(1, extra.detectedOn)
        assertEquals(3 + 1, extra.falseActivations)
    }
}
