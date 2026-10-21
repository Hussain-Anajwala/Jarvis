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
    private val onError: (String) -> Unit,
    /** Microphone level in dB while listening, for the waveform. */
    private val onLevel: (Float) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var window: Runnable? = null
    /** The newest partial result: the answer when a short word ends without a final result. */
    private var partial: String? = null
    private var heardSpeech = false

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    /**
     * One utterance. [noSpeechWindowMs]: for the hands-free window after an answer, stop if no speech has begun by
     * then (reported as [VoicePolicy.WINDOW_ENDED]); null leaves the recogniser's own silence timeout.
     */
    fun start(noSpeechWindowMs: Long? = null) {
        if (!isAvailable) {
            onError(VoicePolicy.UNAVAILABLE)
            return
        }
        stop()
        partial = null
        heardSpeech = false
        val created = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onListening(true)
            override fun onBeginningOfSpeech() {
                heardSpeech = true
                clearWindow()
            }
            override fun onRmsChanged(rmsdB: Float) = onLevel(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = onListening(false)
            override fun onError(error: Int) {
                onListening(false)
                val heard = VoicePolicy.salvage(error, partial)
                if (heard != null) onTranscript(heard) else onError(VoicePolicy.errorMessage(error))
                release()
            }
            override fun onResults(results: Bundle?) {
                onListening(false)
                val text = VoicePolicy.bestTranscript(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                    ?: VoicePolicy.salvage(null, partial)
                if (text == null) onError(VoicePolicy.errorMessage(7)) else onTranscript(text)
                release()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                VoicePolicy.bestTranscript(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))?.let { partial = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        created.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        )
        noSpeechWindowMs?.let { ms ->
            window = Runnable {
                if (!heardSpeech && recognizer === created) {
                    created.cancel()
                    onListening(false)
                    release()
                    onError(VoicePolicy.WINDOW_ENDED)
                }
            }.also { handler.postDelayed(it, ms) }
        }
    }

    private fun clearWindow() {
        window?.let(handler::removeCallbacks)
        window = null
    }

    fun stop() {
        recognizer?.let {
            it.cancel()
            onListening(false)
        }
        release()
    }

    private fun release() {
        clearWindow()
        recognizer?.destroy()
        recognizer = null
    }
}

/**
 * The phone's own TextToSpeech as a [com.jarvis.core.SpeechEngine]: offline-capable fallback and the voice used
 * for private answers. speak() suspends until the utterance finishes, errors, or is stopped.
 */
class SystemTtsEngine(context: Context) : com.jarvis.core.SpeechEngine {
    override val label = "Phone voice"
    @Volatile private var ready = false
    private val waiting = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CancellableContinuation<com.jarvis.core.SpeechResult>>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.also { engine ->
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finish(utteranceId, com.jarvis.core.SpeechResult.Completed)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId, com.jarvis.core.SpeechResult.Failed("phone voice error"))
            override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId, com.jarvis.core.SpeechResult.Failed("phone voice error $errorCode"))
            override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId, com.jarvis.core.SpeechResult.Completed)
        })
    }

    override val available: Boolean get() = ready

    private fun finish(id: String, result: com.jarvis.core.SpeechResult) {
        waiting.remove(id)?.let { if (it.isActive) it.resumeWith(Result.success(result)) }
    }

    override suspend fun speak(text: String, settings: com.jarvis.core.SpeechSettings): com.jarvis.core.SpeechResult {
        if (!ready) return com.jarvis.core.SpeechResult.Failed("phone voice not ready")
        val id = "jarvis-" + java.util.UUID.randomUUID()
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            waiting[id] = continuation
            continuation.invokeOnCancellation {
                waiting.remove(id)
                tts.stop()
            }
            tts.setSpeechRate(settings.rate.coerceIn(0.5f, 2.0f))
            val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC) }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.ERROR) {
                finish(id, com.jarvis.core.SpeechResult.Failed("phone voice rejected the text"))
            }
        }
    }

    override fun stop() {
        if (ready) tts.stop()
        waiting.keys.toList().forEach { finish(it, com.jarvis.core.SpeechResult.Completed) }
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}

/** Streams 16-bit mono PCM to the speaker with transient audio focus (other audio ducks while JARVIS talks). */
class AudioTrackPcmSink(context: Context) : com.jarvis.core.PcmSink {
    private val audioManager = context.applicationContext.getSystemService(android.media.AudioManager::class.java)
    private val attributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    @Volatile private var track: android.media.AudioTrack? = null
    @Volatile private var framesWritten = 0L

    override fun start(sampleRateHz: Int) {
        audioManager?.requestAudioFocus(focus)
        val minBuffer = android.media.AudioTrack.getMinBufferSize(
            sampleRateHz, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        track = android.media.AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setSampleRate(sampleRateHz)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 4, sampleRateHz))
            .setTransferMode(android.media.AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
        framesWritten = 0
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        val written = track?.write(bytes, offset, length) ?: return
        if (written > 0) framesWritten += written / 2
    }

    override fun finish() {
        val current = track ?: return
        // Wait for the buffered audio to play out (bounded), then release.
        val deadline = System.currentTimeMillis() + 30_000
        while (current.playbackHeadPosition.toLong() < framesWritten && System.currentTimeMillis() < deadline && track === current) {
            Thread.sleep(50)
        }
        release()
    }

    override fun stop() = release()

    private fun release() {
        track?.let {
            runCatching { it.pause(); it.flush() }
            it.release()
        }
        track = null
        audioManager?.abandonAudioFocusRequest(focus)
    }
}
