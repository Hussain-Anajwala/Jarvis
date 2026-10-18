package com.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeCommandTest {
    /** Manual clock: tasks run only when time is advanced, so timing rules can be tested exactly. */
    private class FakeRunner : DelayedRunner {
        var now = 0L
        private class Task(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        override fun runAfter(delayMs: Long, task: () -> Unit): () -> Unit {
            val t = Task(now + delayMs, task)
            tasks += t
            return { t.cancelled = true }
        }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                now = next.at
                tasks.remove(next)
                next.run()
            }
            now = end
        }
    }

    private class FakeRecognizer : CommandRecognizer {
        var starts = 0
        var cancels = 0
        var failOnStart: RuntimeException? = null
        private var callback: ((CaptureResult) -> Unit)? = null
        override fun start(onResult: (CaptureResult) -> Unit) {
            failOnStart?.let { throw it }
            starts++
            callback = onResult
        }
        override fun cancel() { cancels++ }
        fun say(result: CaptureResult) { callback?.invoke(result) }
    }

    private class Harness(deliverTo: ((String) -> Boolean)? = null) {
        val runner = FakeRunner()
        val recognizer = FakeRecognizer()
        val micClaims = mutableListOf<Boolean>()
        val cues = mutableListOf<CaptureCue>()
        val delivered = mutableListOf<String>()
        val undelivered = mutableListOf<Pair<String?, String?>>()
        val phases = mutableListOf<CapturePhase>()
        val coordinator = WakeCommandCoordinator(
            recognizer, runner, { micClaims += it }, { cues += it },
            deliver = deliverTo ?: { delivered += it; true },
            undelivered = { text, reason -> undelivered += text to reason },
            setPhase = { phases += it }
        )
        /** The state the microphone arbiter would be in: claimed from the first claim(true) until claim(false). */
        val micClaimed: Boolean get() = micClaims.lastOrNull() == true
    }

    @Test
    fun aSpokenRequestIsCapturedDeliveredAndTheMicrophoneIsHandedBack() {
        val h = Harness()
        assertTrue(h.coordinator.onWakeWord())
        assertTrue("the wake-word listener must give up the microphone immediately", h.micClaimed)
        assertEquals(listOf(CaptureCue.LISTENING), h.cues)
        assertEquals("recognition waits for the beep to end", 0, h.recognizer.starts)
        h.runner.advance(WakeCommandCoordinator.DEFAULT_SETTLE_MS)
        assertEquals(1, h.recognizer.starts)
        h.recognizer.say(CaptureResult.Transcript("  what is the weather  "))
        assertEquals(listOf("what is the weather"), h.delivered)
        assertEquals(listOf(CaptureCue.LISTENING, CaptureCue.HEARD), h.cues)
        assertFalse(h.micClaimed)
        assertFalse(h.coordinator.capturing)
        assertEquals(listOf(CapturePhase.LISTENING, CapturePhase.IDLE), h.phases)
        assertTrue(h.undelivered.isEmpty())
    }

    @Test
    fun duplicateWakeUpsDuringACaptureAreIgnoredAndASecondCaptureWorksAfterwards() {
        val h = Harness()
        assertTrue(h.coordinator.onWakeWord())
        assertFalse(h.coordinator.onWakeWord())
        assertFalse(h.coordinator.onWakeWord())
        h.runner.advance(WakeCommandCoordinator.DEFAULT_SETTLE_MS)
        assertEquals(1, h.recognizer.starts)
        h.recognizer.say(CaptureResult.Transcript("one"))
        assertTrue(h.coordinator.onWakeWord())
        h.runner.advance(WakeCommandCoordinator.DEFAULT_SETTLE_MS)
        h.recognizer.say(CaptureResult.Transcript("two"))
        assertEquals(listOf("one", "two"), h.delivered)
        assertEquals("every claim is matched by a release", h.micClaims.count { it }, h.micClaims.count { !it })
    }

    @Test
    fun silenceEndsTheCaptureWithoutDeliveringAndReleasesTheMicrophone() {
        val h = Harness()
        h.coordinator.onWakeWord()
        h.runner.advance(WakeCommandCoordinator.DEFAULT_SETTLE_MS)
        h.recognizer.say(CaptureResult.NoSpeech)
        assertTrue(h.delivered.isEmpty())
        assertTrue(h.undelivered.isEmpty())
        assertEquals(CaptureCue.FAILED, h.cues.last())
        assertFalse(h.micClaimed)
    }

    @Test
    fun aRecognizerThatNeverAnswersIsStoppedByTheWatchdogAndALateResultIsDropped() {
        val h = Harness()
        h.coordinator.onWakeWord()
        h.runner.advance(WakeCommandCoordinator.DEFAULT_SETTLE_MS)
        h.runner.advance(WakeCommandCoordinator.DEFAULT_TIMEOUT_MS)
        assertEquals("the watchdog cancels the recognizer", 1, h.recognizer.cancels)
        assertFalse("the microphone is never left claimed", h.micClaimed)
        assertFalse(h.coordinator.capturing)
        h.recognizer.say(CaptureResult.Transcript("too late"))
        assertTrue("a result after the watchdog is not run", h.delivered.isEmpty())
        assertTrue(h.undelivered.isEmpty())
    }

    @Test
    fun aRequestNobodyCanTakeIsOfferedToTheNotificationFallback() {
        val h = Harness(deliverTo = { false })
        h.coordinator.onWakeWord()
        h.runner.advance(WakeCommandCoordinator.DEFAULT_SETTLE_MS)
        h.recognizer.say(CaptureResult.Transcript("set a timer for five minutes"))
        assertEquals(listOf<Pair<String?, String?>>("set a timer for five minutes" to null), h.undelivered)
        assertFalse(h.micClaimed)
    }

    @Test
    fun aRecognizerFailureIsReportedWithItsReasonAndNeverLeavesTheMicrophoneClaimed() {
        val h = Harness()
        h.recognizer.failOnStart = IllegalStateException("boom")
        h.coordinator.onWakeWord()
        h.runner.advance(WakeCommandCoordinator.DEFAULT_SETTLE_MS)
        assertEquals(1, h.undelivered.size)
        assertNull(h.undelivered.single().first)
        assertTrue(h.undelivered.single().second!!.contains("IllegalStateException"))
        assertFalse(h.micClaimed)
        // …and the next wake-up works.
        h.recognizer.failOnStart = null
        assertTrue(h.coordinator.onWakeWord())
    }

    @Test
    fun cancellingMidCaptureReturnsTheMicrophoneAndSilencesTheTimers() {
        val h = Harness()
        h.coordinator.onWakeWord()
        h.coordinator.cancel()
        assertFalse(h.micClaimed)
        assertEquals(1, h.recognizer.cancels)
        h.runner.advance(WakeCommandCoordinator.DEFAULT_TIMEOUT_MS * 2)
        assertEquals("no recognizer start and no watchdog after cancel", 0, h.recognizer.starts)
        assertEquals(1, h.recognizer.cancels)
        h.coordinator.cancel() // idempotent
        assertEquals(1, h.recognizer.cancels)
    }

    @Test
    fun anEmptyTranscriptIsNotDelivered() {
        val h = Harness()
        h.coordinator.onWakeWord()
        h.runner.advance(WakeCommandCoordinator.DEFAULT_SETTLE_MS)
        h.recognizer.say(CaptureResult.Transcript("   "))
        assertTrue(h.delivered.isEmpty())
        assertTrue(h.undelivered.isEmpty())
        assertFalse(h.micClaimed)
    }

    @Test
    fun aQuestionInAVoiceConversationIsAnsweredByTheRightListenerAndNeverLoopsForever() {
        fun route(voice: Boolean = true, asked: Int = 0, mic: Boolean = true, visible: Boolean = true, service: Boolean = false) =
            FollowUpPolicy.route(voice, asked, mic, visible, service)
        assertEquals(FollowUpRoute.ON_SCREEN, route())
        assertEquals("typed requests never open the microphone", FollowUpRoute.NONE, route(voice = false))
        assertEquals(FollowUpRoute.NO_PERMISSION, route(mic = false))
        assertEquals(FollowUpRoute.WAKE_SERVICE, route(visible = false, service = true))
        assertEquals(FollowUpRoute.NOTIFICATION, route(visible = false, service = false))
        assertEquals("bounded: no fifth question in a row", FollowUpRoute.NONE, route(asked = FollowUpPolicy.MAX_FOLLOW_UPS))
    }

    @Test
    fun theMicrophoneArbiterStaysClaimedWhileCapturingEvenIfJarvisIsNotSpeaking() {
        MicArbiter.setSpeaking(false)
        MicArbiter.setRecognizing(false)
        val h = Harness()
        val real = WakeCommandCoordinator(
            h.recognizer, h.runner, MicArbiter::setRecognizing, { }, { true }, { _, _ -> }
        )
        real.onWakeWord()
        assertTrue(MicArbiter.micBusy)
        h.runner.advance(WakeCommandCoordinator.DEFAULT_SETTLE_MS)
        h.recognizer.say(CaptureResult.Transcript("hello"))
        assertFalse(MicArbiter.micBusy)
    }
}
