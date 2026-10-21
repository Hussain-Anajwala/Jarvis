package com.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 0 items 3.1 and 3.3: half-duplex turns, a cheap detector reset, an adaptive gate, and honest panel states. */
class TurnTakingTest {
    private class Runner : DelayedRunner {
        var now = 0L
        private val tasks = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()
        override fun runAfter(delayMs: Long, task: () -> Unit): () -> Unit {
            val cancelled = booleanArrayOf(false)
            tasks += Triple(now + delayMs, task, cancelled)
            return { cancelled[0] = true }
        }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.filter { !it.third[0] && it.first <= end }.minByOrNull { it.first } ?: break
                tasks.remove(next)
                now = next.first
                next.second()
            }
            now = end
        }
    }

    private class Recognizer : CommandRecognizer {
        var starts = 0
        override fun start(onResult: (CaptureResult) -> Unit) { starts++ }
        override fun cancel() = Unit
    }

    @Test
    fun noCaptureStartsWhileJarvisIsSpeakingAndOneWaitsForSpeechToEnd() {
        var speaking = true
        val runner = Runner()
        val recognizer = Recognizer()
        val coordinator = WakeCommandCoordinator(recognizer, runner, {}, {}, { true }, { _, _ -> }, canStart = { !speaking })
        assertFalse("a follow-up can't start over JARVIS's own voice", coordinator.onWakeWord())

        speaking = false
        assertTrue(coordinator.onWakeWord())
        speaking = true // an answer started playing during the settle time
        runner.advance(WakeCommandCoordinator.DEFAULT_SETTLE_MS * 3)
        assertEquals("the recogniser waits for the answer to finish", 0, recognizer.starts)
        speaking = false
        runner.advance(WakeCommandCoordinator.DEFAULT_SETTLE_MS)
        assertEquals(1, recognizer.starts)
    }

    @Test
    fun aFollowUpIsDueOnlyWhenAQuestionWaitsAndNothingPlays() {
        assertTrue(TurnTaking.followUpDue(awaitingReplyId = 7, voiceConversation = true, speaking = false))
        assertFalse(TurnTaking.followUpDue(7, true, speaking = true))
        assertFalse(TurnTaking.followUpDue(null, true, false))
        assertFalse(TurnTaking.followUpDue(7, voiceConversation = false, speaking = false))
    }

    private class CountingModels : WakeWordModels {
        var calls = 0
        override fun melspectrogram(samples: FloatArray): Array<FloatArray> { calls++; return Array(8) { FloatArray(32) { samples.first() } } }
        override fun embedding(window: Array<FloatArray>): FloatArray { calls++; return FloatArray(96) { window.last()[0] } }
        override fun score(features: Array<FloatArray>): Float = features.sumOf { it[0].toDouble() }.toFloat()
    }

    @Test
    fun resetRestoresTheFreshStateWithoutRerunningTheModels() {
        val models = CountingModels()
        val detector = WakeWordDetector(models)
        val fresh = WakeWordDetector(CountingModels())
        val chunk = ShortArray(WakeWordDetector.CHUNK) { 40 }
        repeat(30) { detector.process(chunk) }
        val before = models.calls
        detector.reset()
        assertEquals("reset runs no model", before, models.calls)
        val loud = ShortArray(WakeWordDetector.CHUNK) { 900 }
        repeat(10) { assertEquals(fresh.process(loud), detector.process(loud), 0.0001f) }
    }

    @Test
    fun theQuietGateFollowsTheRoomWithinItsLimits() {
        val quiet = NoiseGate()
        repeat(200) { quiet.observe(60f) }
        assertEquals(150f, quiet.threshold, 1f) // quiet room: speech at RMS 200 is no longer skipped
        repeat(200) { quiet.observe(3_000f) } // speech never raises the floor
        assertEquals(150f, quiet.threshold, 1f)
        val noisy = NoiseGate()
        repeat(2_000) { noisy.observe(200f) }
        assertEquals(WakeWordDetector.DEFAULT_QUIET_RMS, noisy.threshold, 0.01f)
        val silent = NoiseGate()
        repeat(200) { silent.observe(5f) }
        assertEquals(120f, silent.threshold, 0.01f)
    }

    private fun show(
        micLive: Boolean = false, wake: Boolean = false, starting: Boolean = false, followUp: Boolean = false,
        speaking: Boolean = false, thinking: Boolean = false, voice: Boolean = true, notice: VoiceNotice? = null, now: Long = 0L
    ) = VoiceIndicators.resolve(micLive, wake, starting, followUp, speaking, thinking, voice, notice, now)

    @Test
    fun thePanelShowsOnlyWhatIsReallyHappening() {
        assertEquals(VoiceIndicator.DETECTED, show(wake = true))
        assertEquals(VoiceIndicator.LISTENING, show(wake = true, micLive = true))
        assertEquals(VoiceIndicator.STARTING, show(wake = true, followUp = true))
        assertEquals(VoiceIndicator.FOLLOW_UP, show(micLive = true, followUp = true))
        assertEquals(VoiceIndicator.STARTING, show(starting = true))
        assertEquals(VoiceIndicator.PROCESSING, show(thinking = true))
        assertEquals(VoiceIndicator.SPEAKING, show(speaking = true, thinking = true))
        assertNull("a typed request keeps the composer", show(thinking = true, voice = false))
        val stopped = VoiceNotice(VoiceIndicator.CANCELLED, at = 1_000)
        assertEquals(VoiceIndicator.CANCELLED, show(voice = false, notice = stopped, now = 2_000))
        assertNull("notices expire", show(voice = false, notice = stopped, now = 1_000 + VoiceIndicators.NOTICE_MS))
        assertEquals(VoiceIndicator.ERROR, show(voice = false, notice = VoiceNotice(VoiceIndicator.ERROR, 0), now = 10))
    }
}
