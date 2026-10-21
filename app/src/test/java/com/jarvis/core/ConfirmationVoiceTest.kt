package com.jarvis.core

import com.jarvis.data.Memory
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0: short spoken answers are accepted, a failed or unclear capture keeps the same proposal, and nothing runs without a yes. */
class ConfirmationVoiceTest {
    private val t = 1_800_000_000_000L
    private val memory = Memory("m1", "my gym is in Dadar", null, "conversation", 1, null, true, "fact", "", 5)
    private val pending = PendingConfirmation.ForgetMemory(memory, t)
    private fun answer(text: String, at: Long = t + 1_000) = Confirmations.answer(pending, LanguageNormalizer.normalize(text), at)

    @Test
    fun oneWordAnswersInEnglishHinglishAndHindi() {
        listOf("yes", "Yes.", "haan", "Han", "ha", "haan ji", "हाँ", "हां", "yeah sure", "ok", "ji haan").forEach {
            assertEquals(it, Confirmations.Answer.APPROVE, answer(it))
        }
        listOf("no", "No.", "nahi", "nahin", "नहीं", "nope", "nahi rehne do", "ji nahi").forEach {
            assertEquals(it, Confirmations.Answer.REJECT, answer(it))
        }
    }

    @Test
    fun anUnclearOrEmptyCaptureKeepsTheSameProposal() {
        listOf("", "  ", "hmm", "uh", "what?", "yes but tomorrow").forEach {
            val got = answer(it)
            assertTrue("$it → $got", got == Confirmations.Answer.UNCLEAR || (it == "what?" && got == Confirmations.Answer.NOT_AN_ANSWER))
        }
        // Several retries: still the same memory, and the original deadline (no refresh).
        repeat(3) { assertEquals(Confirmations.Answer.UNCLEAR, answer("hmm", t + 60_000L * (it + 1))) }
        assertEquals(Confirmations.Answer.APPROVE, answer("haan", t + 4 * 60_000))
        assertEquals(Confirmations.Answer.EXPIRED, answer("haan", t + Confirmations.TTL_MS + 1))
        assertTrue(Confirmations.retryPrompt(pending).contains("\"my gym is in Dadar\""))
    }

    @Test
    fun aClearNewRequestDropsItAndANoAlwaysRejects() {
        assertEquals(Confirmations.Answer.NOT_AN_ANSWER, answer("what's the weather today"))
        assertEquals(Confirmations.Answer.NOT_AN_ANSWER, answer("set a timer for 5 minutes"))
        assertEquals(Confirmations.Answer.NOT_AN_ANSWER, answer("Cancel the timer"))
        // A late no is still a no (harmless); only a late yes is refused.
        assertEquals(Confirmations.Answer.REJECT, answer("no", t + Confirmations.TTL_MS + 1))
    }

    @Test
    fun oneRecognitionSessionEndsExactlyOnce() {
        val s = RecognitionSession()
        s.partial(listOf("ha"))
        s.partial(listOf("haan"))
        assertEquals(RecognitionSession.End.Heard("yes"), s.results(listOf("yes")))
        assertNull(s.error(7)) // a late error after the result
        assertNull(s.results(listOf("yes"))) // a duplicate result
        // A short word ending in "no match": the partial is the answer.
        val short = RecognitionSession().apply { partial(listOf("haan")) }
        assertEquals(RecognitionSession.End.Heard("haan"), short.error(7))
        // Nothing heard: a failure, never an empty answer.
        assertEquals(RecognitionSession.End.Failed(7), RecognitionSession().results(listOf("", " ")))
        assertEquals(RecognitionSession.End.Failed(2), RecognitionSession().apply { partial(listOf("yes")) }.error(2))
        // Stopped by the user: nothing afterwards.
        assertNull(RecognitionSession().apply { cancel() }.results(listOf("yes")))
    }

    @Test
    fun aFailedCaptureWhileWaitingSaysTheRequestIsStillThere() {
        val msg = VoicePolicy.confirmRetry(VoicePolicy.errorMessage(7), listeningAgain = true)
        assertEquals("I didn't catch your answer. The request is still waiting — say yes or no.", msg)
        assertTrue(VoicePolicy.confirmRetry(VoicePolicy.errorMessage(2), listeningAgain = false).startsWith("Speech recognition needs a network connection right now."))
    }

    @Test
    fun aMeetingCorrectionChangesTheProposalButNeverApprovesIt() {
        val now = ZonedDateTime.of(2026, 10, 21, 14, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        val step = PlanStep("meeting.create", mapOf("title" to "Project review", "date" to "2026-10-22", "hour" to "10", "minute" to "0", "location" to "VJTI"))
        val five = Confirmations.meetingCorrection(step, "no, make it 5 PM", now) as Confirmations.Correction.Changed
        assertEquals(listOf("2026-10-22", "17", "0"), listOf(five.step.parameters["date"], five.step.parameters["hour"], five.step.parameters["minute"]))
        val friday = Confirmations.meetingCorrection(step, "make it Friday", now) as Confirmations.Correction.Changed
        assertEquals("2026-10-23", friday.step.parameters["date"])
        assertEquals("10", friday.step.parameters["hour"])
        assertEquals(Confirmations.Correction.NeedsAmPm, Confirmations.meetingCorrection(step, "nahi, 6 baje", now))
        assertNull(Confirmations.meetingCorrection(step, "no", now))
    }
}
