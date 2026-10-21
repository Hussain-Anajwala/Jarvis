package com.jarvis.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.R
import java.nio.FloatBuffer
import java.util.Locale

/** ONNX Runtime implementation of the three openWakeWord models bundled in assets/wakeword. */
class OnnxWakeWordModels(context: Context) : WakeWordModels, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // XNNPACK runs the convolutions noticeably faster on ARM; fall back to the default CPU provider if absent.
        runCatching { addXnnpack(mapOf("intra_op_num_threads" to "1")) }
    }
    private val mel = session(context, "melspectrogram.onnx")
    private val emb = session(context, "embedding_model.onnx")
    private val cls = session(context, "hey_jarvis_v0.1.onnx")

    private fun session(context: Context, name: String): OrtSession =
        env.createSession(context.assets.open("wakeword/$name").use { it.readBytes() }, options)

    override fun melspectrogram(samples: FloatArray): Array<FloatArray> =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { input ->
            mel.run(mapOf(mel.inputNames.first() to input)).use { result ->
                // [time, 1, frames, 32] → frames × 32
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<Array<Array<FloatArray>>>
                out[0][0]
            }
        }

    override fun embedding(window: Array<FloatArray>): FloatArray {
        val flat = FloatArray(window.size * WakeWordDetector.MEL_BINS)
        window.forEachIndexed { i, frame -> System.arraycopy(frame, 0, flat, i * WakeWordDetector.MEL_BINS, WakeWordDetector.MEL_BINS) }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, window.size.toLong(), WakeWordDetector.MEL_BINS.toLong(), 1)).use { input ->
            emb.run(mapOf(emb.inputNames.first() to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result[0].value as Array<Array<Array<FloatArray>>>)[0][0][0]
            }
        }
    }

    override fun score(features: Array<FloatArray>): Float {
        val flat = FloatArray(features.size * 96)
        features.forEachIndexed { i, f -> System.arraycopy(f, 0, flat, i * 96, 96) }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, features.size.toLong(), 96)).use { input ->
            cls.run(mapOf(cls.inputNames.first() to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result[0].value as Array<FloatArray>)[0][0]
            }
        }
    }

    override fun close() {
        mel.close(); emb.close(); cls.close(); options.close()
    }
}

/** Wraps the models to measure average inference time per call (timings only, never audio). */
private class TimedWakeWordModels(private val inner: WakeWordModels) : WakeWordModels {
    private val totals = LongArray(3)
    private val calls = IntArray(3)
    private inline fun <T> timed(stage: Int, block: () -> T): T {
        val start = System.nanoTime()
        return block().also { totals[stage] += System.nanoTime() - start; calls[stage]++ }
    }
    override fun melspectrogram(samples: FloatArray) = timed(0) { inner.melspectrogram(samples) }
    override fun embedding(window: Array<FloatArray>) = timed(1) { inner.embedding(window) }
    override fun score(features: Array<FloatArray>): Float = timed(2) { inner.score(features) }

    /** Summary since the last call; resets the counters. */
    fun report(steps: Int, levels: FloatArray): String {
        fun avg(i: Int) = if (calls[i] == 0) 0.0 else totals[i] / 1e6 / calls[i]
        val sorted = levels.sorted()
        val busyMs = totals.sum() / 1e6
        return "ms/call mel=%.2f emb=%.2f cls=%.2f; classifier ran %d/%d steps; model time %.1f%% of audio; rms p10=%.0f p50=%.0f p90=%.0f".format(
            avg(0), avg(1), avg(2), calls[2], steps, 100 * busyMs / (steps * 80.0),
            sorted[sorted.size / 10], sorted[sorted.size / 2], sorted[sorted.size * 9 / 10]
        ).also { totals.fill(0); calls.fill(0) }
    }
}

object WakeWordSettings {
    private const val PREFS = "wake_word"
    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun setEnabled(context: Context, value: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
}

/**
 * Opt-in "Hey JARVIS" listener. A microphone foreground service with a persistent notification while it listens;
 * audio never leaves the phone (the wake phrase is matched on-device) and is not stored.
 *
 * When the phrase is heard the service gives the microphone to the speech recogniser, captures the next spoken
 * request, and hands the text to a live JARVIS screen model (which runs it and speaks the answer). If none is alive
 * it posts a private notification instead; tapping it runs the request. Android never lets an app open its own
 * screen from the background, which is why the request is captured here rather than by launching an activity.
 *
 * The microphone is released whenever JARVIS speaks or the recogniser captures, so the listener cannot trigger on
 * JARVIS's own voice or fight over the microphone. Android only lets a microphone service start while JARVIS is
 * visible (after a restart, JARVIS must be opened once).
 */
class WakeWordService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var coordinator: WakeCommandCoordinator
    private var recognizer: SpeechCommandRecognizer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val speech = SpeechCommandRecognizer(this)
        recognizer = speech
        val cues = CaptureCues(this)
        coordinator = WakeCommandCoordinator(
            recognizer = speech,
            runner = { delay, task ->
                val job = Runnable(task)
                main.postDelayed(job, delay)
                val cancel: () -> Unit = { main.removeCallbacks(job) }
                cancel
            },
            claimMic = MicArbiter::setRecognizing,
            cue = { cue ->
                Log.d(TAG, "cue $cue")
                cues.play(cue)
                if (cue == CaptureCue.FAILED) WakeWordEvents.captureFailed()
            },
            deliver = { text ->
                val taken = WakeWordEvents.commandSink?.invoke(text) == true
                Log.d(TAG, "request captured (${text.length} chars), taken by a live JARVIS: $taken") // never the text itself
                taken
            },
            undelivered = { text, reason ->
                Log.d(TAG, "request not run directly: captured=${text != null}, reason=${reason ?: "none"}")
                offerInNotification(text, reason)
            },
            setPhase = { phase ->
                WakeWordEvents.setCapture(phase)
                updateNotification(phase)
            },
            canStart = { !MicArbiter.speaking.value }
        )
        // A question JARVIS asked can be answered by voice even while JARVIS is not on screen.
        WakeWordEvents.followUpCapture = { coordinator.onWakeWord() }
        WakeWordEvents.cancelCapture = { main.post { coordinator.cancel() } }
    }

    /** The persistent notification says what the microphone is doing right now. */
    private fun updateNotification(phase: CapturePhase) {
        if (!running) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(capturing = phase == CapturePhase.LISTENING))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_CAPTURE) {
            coordinator.cancel()
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) WakeWordSettings.setEnabled(this, false)
        if (intent?.action == ACTION_STOP || !WakeWordSettings.enabled(this) ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), type) else startForeground(NOTIFICATION_ID, notification())
        } catch (error: RuntimeException) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background.
            Log.w(TAG, "Wake word could not start in the foreground: ${error.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            WakeWordEvents.setServiceRunning(true)
            worker = Thread(::listenLoop, "jarvis-wake-word").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        WakeWordEvents.followUpCapture = null
        WakeWordEvents.cancelCapture = null
        WakeWordEvents.setMicLive(false)
        worker?.interrupt()
        // Never leave the microphone claimed or a recogniser open when the service goes away.
        coordinator.cancel()
        recognizer?.cancel()
        MicArbiter.setRecognizing(false)
        WakeWordEvents.setListening(false)
        WakeWordEvents.setServiceRunning(false)
        super.onDestroy()
    }

    private fun listenLoop() {
        val models = try {
            OnnxWakeWordModels(applicationContext)
        } catch (error: Exception) {
            Log.w(TAG, "Wake-word models failed to load: ${error.javaClass.simpleName}")
            stopSelf()
            return
        }
        models.use {
            val timed = TimedWakeWordModels(models)
            val levels = FloatArray(REPORT_STEPS)
            var steps = 0
            // Built once: the gate's noise-floor estimate and the model state survive pauses (reset() is cheap).
            val detector = WakeWordDetector(timed, gate = NoiseGate())
            var paused = false
            val trigger = WakeWordTrigger()
            val chunk = ShortArray(WakeWordDetector.CHUNK)
            var backoffMs = 0L
            var busySince = 0L
            while (running) {
                if (MicArbiter.micBusy) {
                    val now = System.currentTimeMillis()
                    if (busySince == 0L) busySince = now
                    // No answer or capture lasts this long: an owner that never let go (e.g. a screen closed mid-speech).
                    if (now - busySince > MicArbiter.STUCK_MS) {
                        Log.w(TAG, "microphone claim held for ${(now - busySince) / 1000} s; releasing it")
                        MicArbiter.release()
                        busySince = 0L
                        continue
                    }
                    WakeWordEvents.setListening(false)
                    if (!paused) {
                        // Stale audio from before the pause must not count; start clean afterwards.
                        detector.reset()
                        paused = true
                    }
                    Thread.sleep(100)
                    // Ignore the echo tail of JARVIS's own voice for a moment after the microphone comes back.
                    trigger.suppressFor(ECHO_GUARD_MS)
                    continue
                }
                paused = false
                busySince = 0L
                val record = openRecorder()
                if (record == null) {
                    backoffMs = nextBackoff(backoffMs)
                    Thread.sleep(backoffMs)
                    continue
                }
                WakeWordEvents.setListening(true)
                var healthy = false
                try {
                    record.startRecording()
                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("not recording")
                    while (running && !MicArbiter.micBusy) {
                        var filled = 0
                        while (filled < chunk.size && running) {
                            val read = record.read(chunk, filled, chunk.size - filled)
                            if (read <= 0) break
                            filled += read
                        }
                        if (filled < chunk.size) break
                        healthy = true
                        backoffMs = 0
                        // Every 20 s: model timing and the 80 ms level spread, to check battery cost and the quiet gate.
                        levels[steps % REPORT_STEPS] = WakeWordDetector.rms(chunk)
                        if (++steps % REPORT_STEPS == 0) Log.d(TAG, timed.report(REPORT_STEPS, levels))
                        if (trigger.onScore(detector.process(chunk))) {
                            Log.d(TAG, "wake word detected")
                            WakeWordEvents.detected()
                            // Claims the microphone at once (this loop then releases it) and captures the request.
                            if (coordinator.onWakeWord()) trigger.suppressFor(2_000)
                        }
                    }
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Microphone unavailable: ${error.javaClass.simpleName}")
                } finally {
                    WakeWordEvents.setListening(false)
                    runCatching { record.stop() }
                    record.release()
                }
                // The microphone was taken away or reads failed (another app, a call): wait instead of spinning.
                if (!healthy && running && !MicArbiter.micBusy) {
                    backoffMs = nextBackoff(backoffMs)
                    Thread.sleep(backoffMs)
                }
            }
        }
    }

    private fun nextBackoff(current: Long) = if (current == 0L) 500L else minOf(current * 2, 10_000L)

    @Suppress("MissingPermission")
    private fun openRecorder(): AudioRecord? = try {
        val min = AudioRecord.getMinBufferSize(WakeWordDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWordDetector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, WakeWordDetector.CHUNK * 4)
        ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    } catch (_: Exception) {
        null
    }

    /**
     * The request could not be handed to a live JARVIS ([text]), or nothing could be captured ([text] == null).
     * Android blocks opening an activity from here, so the user's tap on a notification is the compliant way in.
     */
    private fun offerInNotification(text: String?, reason: String?) {
        // A visible JARVIS needs no notification for "nothing captured": the cue already told the user.
        if (text == null && AppVisibility.visible) return
        val manager = getSystemService(NotificationManager::class.java)
        ensureChannels(manager)
        val intent = Intent().setClassName(this, "com.jarvis.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (text != null) intent.putExtra(EXTRA_COMMAND, text) else intent.putExtra(EXTRA_START_LISTENING, true)
        val open = PendingIntent.getActivity(this, if (text != null) 2 else 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val publicVersion = Notification.Builder(this, HEARD_CHANNEL).setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("JARVIS").setContentText("Tap to continue").build()
        val builder = Notification.Builder(this, HEARD_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
        if (text != null) {
            // The request is the user's own words; it is shown only on the unlocked shade and never logged.
            builder.setContentTitle("Hey JARVIS — tap to run").setContentText("“$text”")
        } else {
            builder.setContentTitle("Hey JARVIS — tap to talk")
                .setContentText(reason?.let { "I couldn't listen ($it). Tap to open JARVIS and try again." } ?: "Tap to open JARVIS and speak your request.")
        }
        manager.notify(HEARD_NOTIFICATION_ID, builder.build())
    }

    private fun ensureChannels(manager: NotificationManager) {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Wake word listening", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(HEARD_CHANNEL, "Wake word requests", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun notification(capturing: Boolean = false): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        ensureChannels(manager)
        val stop = PendingIntent.getService(this, 0, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 3, Intent(this, WakeWordService::class.java).setAction(ACTION_CANCEL_CAPTURE), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent().setClassName(this, "com.jarvis.MainActivity"), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        return if (capturing) {
            builder.setContentTitle("JARVIS is listening for your request")
                .setContentText("Speak now. Tap Cancel to stop.")
                .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
                .build()
        } else {
            builder.setContentTitle("Listening for “Hey JARVIS”")
                .setContentText("Microphone on, processed only on this phone. Tap Stop to turn it off.")
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
                .build()
        }
    }

    companion object {
        private const val TAG = "JarvisWakeWord"
        private const val REPORT_STEPS = 250
        /** Detections this soon after JARVIS spoke or listened are treated as echo (was 1.5 s; not yet measured acoustically). */
        private const val ECHO_GUARD_MS = 800L
        private const val CHANNEL = "jarvis.wakeword"
        private const val HEARD_CHANNEL = "jarvis.wakeword.heard"
        private const val NOTIFICATION_ID = 7001
        private const val HEARD_NOTIFICATION_ID = 7002
        const val ACTION_STOP = "com.jarvis.wakeword.STOP"
        const val ACTION_CANCEL_CAPTURE = "com.jarvis.wakeword.CANCEL_CAPTURE"
        const val EXTRA_START_LISTENING = "com.jarvis.START_LISTENING"
        const val EXTRA_COMMAND = "com.jarvis.WAKE_COMMAND"

        /** Call only while JARVIS is in the foreground (Android's rule for microphone services). */
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}

/** Whether a JARVIS screen is currently visible (set by MainActivity onStart/onStop). */
object AppVisibility {
    @Volatile var visible: Boolean = false
}

/** [CommandRecognizer] backed by Android's SpeechRecognizer, used from the service on the main thread. */
class SpeechCommandRecognizer(context: Context) : CommandRecognizer {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    override fun start(onResult: (CaptureResult) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onResult(CaptureResult.Failed("speech recognition is not available on this phone"))
            return
        }
        cancel()
        val created = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = created
        var peakDb = -100f
        var startedAt = android.os.SystemClock.elapsedRealtime()
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                startedAt = android.os.SystemClock.elapsedRealtime()
                WakeWordEvents.setMicLive(true)
                Log.d("JarvisWakeWord", "recognizer ready for speech")
            }
            override fun onBeginningOfSpeech() { Log.d("JarvisWakeWord", "recognizer heard speech begin") }
            override fun onRmsChanged(rmsdB: Float) {
                if (rmsdB > peakDb) peakDb = rmsdB
                WakeWordEvents.setLevel(rmsdB)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { WakeWordEvents.setMicLive(false) } // the recogniser is now processing
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) {
                Log.d("JarvisWakeWord", "recognizer error code $error after ${android.os.SystemClock.elapsedRealtime() - startedAt} ms, peak input %.1f dB".format(peakDb))
                release()
                onResult(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> CaptureResult.NoSpeech
                        else -> CaptureResult.Failed(VoicePolicy.errorMessage(error))
                    }
                )
            }
            override fun onResults(results: Bundle?) {
                release()
                val text = VoicePolicy.bestTranscript(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                onResult(if (text == null) CaptureResult.NoSpeech else CaptureResult.Transcript(text))
            }
        })
        created.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        )
    }

    override fun cancel() {
        recognizer?.let { runCatching { it.cancel() } }
        release()
    }

    private fun release() {
        WakeWordEvents.setMicLive(false)
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }
}

/** Brief tones: a short beep when JARVIS starts listening, a chirp when it heard you, a low tone on failure. */
class CaptureCues(context: Context) {
    private val audio = context.applicationContext.getSystemService(android.media.AudioManager::class.java)

    fun play(cue: CaptureCue) {
        if (audio?.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) return // silent / vibrate stays silent
        val tone = when (cue) {
            CaptureCue.LISTENING -> android.media.ToneGenerator.TONE_PROP_BEEP
            CaptureCue.HEARD -> android.media.ToneGenerator.TONE_PROP_ACK
            CaptureCue.FAILED -> android.media.ToneGenerator.TONE_PROP_NACK
        }
        try {
            val generator = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 60)
            generator.startTone(tone, 150)
            Handler(Looper.getMainLooper()).postDelayed({ generator.release() }, 600)
        } catch (_: RuntimeException) {
            // ToneGenerator can be unavailable while the audio system is busy; the cue is optional.
        }
    }
}
