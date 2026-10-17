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
