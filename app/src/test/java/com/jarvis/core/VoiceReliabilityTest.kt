package com.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reliability fix, Priority A: short answers are kept, the hands-free window is explicit and bounded, states are truthful. */
class VoiceReliabilityTest {
    @Test
    fun aShortAnswerHeardAsAPartialIsKept() {
        // "yes" / "haan" ending in ERROR_SPEECH_TIMEOUT or ERROR_NO_MATCH after a partial result.
        assertEquals("yes", VoicePolicy.salvage(7, "yes "))
        assertEquals("haan", VoicePolicy.salvage(6, "haan"))
        assertEquals("haan", VoicePolicy.salvage(null, "haan"))
        // Nothing heard, or a failure that is not "no match": never a request.
        assertNull(VoicePolicy.salvage(7, null))
        assertNull(VoicePolicy.salvage(7, "  "))
        assertNull(VoicePolicy.salvage(2, "yes")) // network error
        assertNull(VoicePolicy.salvage(5, "yes")) // cancelled by Stop
    }

    @Test
    fun keepListeningOnlyWhenTurnedOnOnScreenAndAfterTheNewestAnswer() {
        assertTrue(TurnTaking.keepListeningDue(enabled = true, voiceConversation = true, speaking = false, newestAnswer = true, visible = true))
        assertFalse(TurnTaking.keepListeningDue(false, true, false, true, true)) // mode off (the default)
        assertFalse(HandsFree().enabled)
        assertFalse(TurnTaking.keepListeningDue(true, false, false, true, true)) // typed conversation
        assertFalse(TurnTaking.keepListeningDue(true, true, true, true, true)) // still speaking: half duplex
        assertFalse(TurnTaking.keepListeningDue(true, true, false, false, true)) // an older answer finished
        assertFalse(TurnTaking.keepListeningDue(true, true, false, true, false)) // not on screen
    }

    @Test
    fun theVoicePanelSaysWhatIsHappening() {
        fun resolve(micLive: Boolean = false, followUp: Boolean = false, keep: Boolean = false, confirming: Boolean = false, notice: VoiceNotice? = null) =
            VoiceIndicators.resolve(micLive, false, false, followUp, false, false, true, notice, 1_000, keep, confirming)
        assertEquals(VoiceIndicator.LISTENING, resolve(micLive = true))
        assertEquals(VoiceIndicator.FOLLOW_UP, resolve(micLive = true, followUp = true))
        assertEquals(VoiceIndicator.KEEP_LISTENING, resolve(micLive = true, followUp = true, keep = true))
        assertEquals(VoiceIndicator.CONFIRM, resolve(micLive = true, followUp = true, confirming = true))
        assertEquals(VoiceIndicator.ENDED, resolve(notice = VoiceNotice(VoiceIndicator.ENDED, 500)))
        assertEquals(VoiceIndicator.NO_MIC, resolve(notice = VoiceNotice(VoiceIndicator.NO_MIC, 500)))
        assertNull(resolve(notice = VoiceNotice(VoiceIndicator.ENDED, 1_000 - VoiceIndicators.NOTICE_MS - 1)))
    }

    @Test
    fun aStuckMicrophoneClaimCanBeReleased() {
        MicArbiter.setSpeaking(true)
        MicArbiter.setRecognizing(true)
        assertTrue(MicArbiter.micBusy)
        MicArbiter.release()
        assertFalse(MicArbiter.micBusy)
    }
}
