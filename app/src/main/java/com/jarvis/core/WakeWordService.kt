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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.FloatBuffer

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
 * audio never leaves the phone and is not stored. It releases the microphone whenever JARVIS speaks or the speech
 * recognizer captures a command, so it cannot trigger on JARVIS's own voice or fight over the mic.
 * Android only lets a microphone service start while JARVIS is in the foreground (after reboot it must be reopened).
 */
class WakeWordService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            worker = Thread(::listenLoop, "jarvis-wake-word").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        WakeWordEvents.setListening(false)
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
            val detector = WakeWordDetector(timed, quietRms = WakeWordDetector.DEFAULT_QUIET_RMS)
            val trigger = WakeWordTrigger()
            val chunk = ShortArray(WakeWordDetector.CHUNK)
            while (running) {
                if (MicArbiter.micBusy) {
                    WakeWordEvents.setListening(false)
                    Thread.sleep(200)
                    trigger.suppressFor(1_500)
                    continue
                }
                val record = openRecorder() ?: run { Thread.sleep(1_000); null } ?: continue
                WakeWordEvents.setListening(true)
                try {
                    record.startRecording()
                    while (running && !MicArbiter.micBusy) {
                        var filled = 0
                        while (filled < chunk.size && running) {
                            val read = record.read(chunk, filled, chunk.size - filled)
                            if (read <= 0) break
                            filled += read
                        }
                        if (filled < chunk.size) break
                        // Every 20 s: model timing and the 80 ms level spread, to check battery cost and the quiet gate.
                        levels[steps % REPORT_STEPS] = WakeWordDetector.rms(chunk)
                        if (++steps % REPORT_STEPS == 0) Log.d(TAG, timed.report(REPORT_STEPS, levels))
                        if (trigger.onScore(detector.process(chunk))) {
                            Log.d(TAG, "wake word detected")
                            WakeWordEvents.detected()
                            onDetectedInBackground()
                            trigger.suppressFor(2_000)
                        }
                    }
                } finally {
                    WakeWordEvents.setListening(false)
                    runCatching { record.stop() }
                    record.release()
                }
            }
        }
    }

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

    /** If no JARVIS screen is showing, offer a one-tap way in (Android blocks opening activities from the background). */
    private fun onDetectedInBackground() {
        if (AppVisibility.visible) return
        val manager = getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            this, 1,
            Intent().setClassName(this, "com.jarvis.MainActivity").putExtra(EXTRA_START_LISTENING, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.notify(
            HEARD_NOTIFICATION_ID,
            Notification.Builder(this, HEARD_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Hey JARVIS — tap to talk")
                .setContentText("JARVIS heard the wake word. Tap to open and speak your request.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(15_000)
                .build()
        )
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Wake word listening", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(HEARD_CHANNEL, "Wake word heard", NotificationManager.IMPORTANCE_HIGH))
        val stop = PendingIntent.getService(this, 0, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent().setClassName(this, "com.jarvis.MainActivity"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Listening for “Hey JARVIS”")
            .setContentText("Microphone on, processed only on this phone. Tap Stop to turn it off.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "JarvisWakeWord"
        private const val REPORT_STEPS = 250
        private const val CHANNEL = "jarvis.wakeword"
        private const val HEARD_CHANNEL = "jarvis.wakeword.heard"
        private const val NOTIFICATION_ID = 7001
        private const val HEARD_NOTIFICATION_ID = 7002
        const val ACTION_STOP = "com.jarvis.wakeword.STOP"
        const val EXTRA_START_LISTENING = "com.jarvis.START_LISTENING"

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
