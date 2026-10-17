package com.jarvis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.jarvis.core.VoicePolicy
import java.util.Locale

/**
 * Push-to-talk speech input. The microphone is used only between an explicit tap and the end of one
 * utterance (or Stop); nothing records in the background. Must be used on the main thread.
 */
class VoiceInput(
    context: Context,
    private val onListening: (Boolean) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun start() {
        if (!isAvailable) {
            onError(VoicePolicy.UNAVAILABLE)
            return
        }
        stop()
        val created = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onListening(true)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = onListening(false)
            override fun onError(error: Int) {
                onListening(false)
                onError(VoicePolicy.errorMessage(error))
                release()
            }
            override fun onResults(results: Bundle?) {
                onListening(false)
                val text = VoicePolicy.bestTranscript(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                if (text == null) onError(VoicePolicy.errorMessage(7)) else onTranscript(text)
                release()
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        created.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        )
    }

    fun stop() {
        recognizer?.let {
            it.cancel()
            onListening(false)
        }
        release()
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }
}

/** Reads replies aloud when the user turns "Speak replies" on; silently does nothing if TTS is unavailable. */
class Speaker(context: Context) {
    @Volatile private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }

    val isReady: Boolean get() = ready

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        tts.speak(VoicePolicy.speakable(text), TextToSpeech.QUEUE_FLUSH, null, "jarvis-reply")
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
