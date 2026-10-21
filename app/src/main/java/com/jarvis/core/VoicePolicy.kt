package com.jarvis.core

/** What tapping the mic should do. Voice is optional: text input always works. */
sealed interface VoiceStart {
    data object Listen : VoiceStart
    data object RequestPermission : VoiceStart
    data class Unavailable(val message: String) : VoiceStart
}

object VoicePolicy {
    const val UNAVAILABLE =
        "Speech recognition isn't available on this phone. Type your request instead."

    fun onMicTapped(permissionGranted: Boolean, recognitionAvailable: Boolean, permanentlyDenied: Boolean): VoiceStart = when {
        !recognitionAvailable -> VoiceStart.Unavailable(UNAVAILABLE)
        permissionGranted -> VoiceStart.Listen
        permanentlyDenied -> VoiceStart.Unavailable(
            "Microphone permission is off. Allow it in Permissions → Microphone, or type your request."
        )
        else -> VoiceStart.RequestPermission
    }

    fun onPermissionResult(granted: Boolean): VoiceStart =
        if (granted) VoiceStart.Listen
        else VoiceStart.Unavailable("Microphone permission was denied. You can still type your request.")

    /** android.speech.SpeechRecognizer error codes → short, honest messages. */
    fun errorMessage(code: Int): String = when (code) {
        1, 2 -> "Speech recognition needs a network connection right now. Type your request instead." // network timeout / network
        3 -> "The microphone couldn't record audio." // audio
        4 -> "The speech service had a problem. Try again or type your request." // server
        5 -> "Listening was cancelled." // client
        6, 7 -> "I didn't catch that. Tap the mic and try again, or type your request." // speech timeout / no match
        8 -> "The speech service is busy. Try again in a moment." // recognizer busy
        9 -> "Microphone permission is missing. Allow it in Permissions → Microphone." // insufficient permissions
        else -> "Speech recognition stopped (error $code). Type your request instead."
    }

    /** The keep-listening window ended with no speech: a quiet, normal end (not an error). */
    const val WINDOW_ENDED = "No new request, so I stopped listening."
    /** Recognition failed while a yes/no was waiting: the same proposal is still waiting (never treated as no). */
    fun confirmRetry(message: String, listeningAgain: Boolean): String =
        (if (message == errorMessage(6) || message == errorMessage(7)) "I didn't catch your answer." else message.substringBefore(" Type").substringBefore(" Tap")) +
            " The request is still waiting — " + (if (listeningAgain) "say yes or no." else "tap the mic and say yes or no, or type it.")

    const val NO_MIC = "Microphone permission is off, so I can't listen. Type your request, or allow the microphone in Settings → Connections and permissions."

    /**
     * What was heard when the recogniser gives up without a final result. Short answers ("yes", "haan") often end
     * with ERROR_SPEECH_TIMEOUT (6) or ERROR_NO_MATCH (7) after a partial result already held the word; that partial is
     * the answer. Other errors (network, audio, cancelled) never turn a partial into a request.
     */
    fun salvage(errorCode: Int?, partial: String?): String? =
        if (errorCode == null || errorCode == 6 || errorCode == 7) partial?.trim()?.takeIf { it.isNotBlank() }?.take(500) else null

    /** Best non-blank transcript, trimmed; null means nothing usable was heard. */
    fun bestTranscript(candidates: List<String>?): String? =
        candidates?.firstOrNull { it.isNotBlank() }?.trim()?.take(500)

    /** Text read aloud: no list bullets or emoji-like markup, and long replies are shortened. */
    fun speakable(reply: String): String =
        reply.replace("•", "").replace(Regex("\\s+"), " ").trim().let { if (it.length > 600) it.take(600) + "…" else it }
}

/**
 * One recogniser session ends exactly once: the first final result or error wins and later callbacks (an error after
 * results, a repeated result) are ignored, so one utterance can never be submitted twice. The newest partial result
 * is kept for a short answer that ends in "no match" ([VoicePolicy.salvage]).
 */
class RecognitionSession {
    sealed interface End {
        data class Heard(val text: String) : End
        data class Failed(val code: Int) : End
    }

    private var partial: String? = null
    var ended = false
        private set

    fun partial(candidates: List<String>?) {
        if (!ended) VoicePolicy.bestTranscript(candidates)?.let { partial = it }
    }

    fun results(candidates: List<String>?): End? {
        if (ended) return null
        ended = true
        val text = VoicePolicy.bestTranscript(candidates) ?: VoicePolicy.salvage(null, partial)
        return text?.let { End.Heard(it) } ?: End.Failed(7)
    }

    fun error(code: Int): End? {
        if (ended) return null
        ended = true
        return VoicePolicy.salvage(code, partial)?.let { End.Heard(it) } ?: End.Failed(code)
    }

    /** Stopped by the user or the hands-free window: nothing is delivered afterwards. */
    fun cancel() { ended = true }
}

/**
 * Half-duplex turn-taking: JARVIS listens or speaks, never both. Automatic listening (a follow-up answer) starts only
 * when nothing is playing; the user's own tap on the mic may still interrupt speech.
 */
object TurnTaking {
    /** An automatic capture may start now. */
    fun mayListen(speaking: Boolean): Boolean = !speaking

    /** A question is waiting for its spoken answer and the last audio has just finished. */
    fun followUpDue(awaitingReplyId: Long?, voiceConversation: Boolean, speaking: Boolean): Boolean =
        awaitingReplyId != null && voiceConversation && mayListen(speaking)

    /**
     * Hands-free mode: after the newest answer of a voice conversation has finished playing, listen once more —
     * only when the user turned the mode on and JARVIS is on screen (the panel shows that the microphone is on).
     */
    fun keepListeningDue(enabled: Boolean, voiceConversation: Boolean, speaking: Boolean, newestAnswer: Boolean, visible: Boolean): Boolean =
        enabled && voiceConversation && mayListen(speaking) && newestAnswer && visible
}

/** "Keep listening after answers" (Settings → Voice). Explicit, off by default, and bounded by [windowSeconds] of silence. */
data class HandsFree(val enabled: Boolean = false, val windowSeconds: Int = 8) {
    companion object {
        const val MIN_SECONDS = 4
        const val MAX_SECONDS = 20
    }
}

/** What the voice panel shows. Every state comes from a real signal; nothing is animated without one. */
enum class VoiceIndicator(val label: String, val detail: String) {
    DETECTED("Heard “Hey JARVIS”", "Getting the microphone ready"),
    STARTING("One moment…", "Getting the microphone ready"),
    LISTENING("Listening…", "Speak now"),
    FOLLOW_UP("Listening for your answer…", "Answer the question, or say cancel"),
    CONFIRM("Say yes or no", "Waiting for your confirmation"),
    KEEP_LISTENING("Still listening…", "Say another request, or wait and I'll stop"),
    PROCESSING("Thinking…", "Working on your request"),
    SPEAKING("Speaking", ""),
    CANCELLED("Stopped", "Not listening"),
    ENDED("Stopped listening", "Not listening"),
    NO_MIC("Microphone is off", "Not listening — allow the microphone in Settings"),
    ERROR("Didn't catch that", "Not listening")
}

/** A brief end-of-interaction state (stopped, or a capture that failed), shown for [VoiceIndicators.NOTICE_MS]. */
data class VoiceNotice(val indicator: VoiceIndicator, val at: Long, val message: String? = null)

object VoiceIndicators {
    const val NOTICE_MS = 2_500L

    /**
     * [micLive]: a recogniser is ready and recording. [wakeCapturing]: the wake-word service is capturing (from
     * "Hey JARVIS", or a follow-up answer when [followUp]). [captureStarting]: push-to-talk is claiming the mic.
     */
    fun resolve(
        micLive: Boolean,
        wakeCapturing: Boolean,
        captureStarting: Boolean,
        followUp: Boolean,
        speaking: Boolean,
        thinking: Boolean,
        voiceConversation: Boolean,
        notice: VoiceNotice?,
        now: Long,
        /** The follow-up is the hands-free window after an answer, not the answer to a question. */
        keepListening: Boolean = false,
        /** A meeting, memory deletion or plan is waiting for yes / no. */
        confirming: Boolean = false
    ): VoiceIndicator? = when {
        micLive -> when {
            !followUp -> VoiceIndicator.LISTENING
            confirming -> VoiceIndicator.CONFIRM
            keepListening -> VoiceIndicator.KEEP_LISTENING
            else -> VoiceIndicator.FOLLOW_UP
        }
        wakeCapturing && !followUp -> VoiceIndicator.DETECTED
        wakeCapturing || captureStarting -> VoiceIndicator.STARTING
        speaking && voiceConversation -> VoiceIndicator.SPEAKING
        thinking && voiceConversation -> VoiceIndicator.PROCESSING
        notice != null && now - notice.at in 0 until NOTICE_MS -> notice.indicator
        else -> null
    }
}
