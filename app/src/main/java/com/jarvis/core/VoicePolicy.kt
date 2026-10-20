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

    /** Best non-blank transcript, trimmed; null means nothing usable was heard. */
    fun bestTranscript(candidates: List<String>?): String? =
        candidates?.firstOrNull { it.isNotBlank() }?.trim()?.take(500)

    /** Text read aloud: no list bullets or emoji-like markup, and long replies are shortened. */
    fun speakable(reply: String): String =
        reply.replace("•", "").replace(Regex("\\s+"), " ").trim().let { if (it.length > 600) it.take(600) + "…" else it }
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
}

/** What the voice panel shows. Every state comes from a real signal; nothing is animated without one. */
enum class VoiceIndicator(val label: String, val detail: String) {
    DETECTED("Heard “Hey JARVIS”", "Getting the microphone ready"),
    STARTING("One moment…", "Getting the microphone ready"),
    LISTENING("Listening…", "Speak now"),
    FOLLOW_UP("Listening for your answer…", "Answer the question, or say cancel"),
    PROCESSING("Thinking…", "Working on your request"),
    SPEAKING("Speaking", ""),
    CANCELLED("Stopped", "Not listening"),
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
        now: Long
    ): VoiceIndicator? = when {
        micLive -> if (followUp) VoiceIndicator.FOLLOW_UP else VoiceIndicator.LISTENING
        wakeCapturing && !followUp -> VoiceIndicator.DETECTED
        wakeCapturing || captureStarting -> VoiceIndicator.STARTING
        speaking && voiceConversation -> VoiceIndicator.SPEAKING
        thinking && voiceConversation -> VoiceIndicator.PROCESSING
        notice != null && now - notice.at in 0 until NOTICE_MS -> notice.indicator
        else -> null
    }
}
