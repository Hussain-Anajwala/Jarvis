package com.jarvis.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/** The three openWakeWord networks; implemented with ONNX Runtime on the phone and faked in tests. */
interface WakeWordModels {
    /** int16 sample values as floats → mel frames, each 32 values (before the x/10 + 2 transform). */
    fun melspectrogram(samples: FloatArray): Array<FloatArray>
    /** 76 × 32 mel window → 96-value embedding. */
    fun embedding(window: Array<FloatArray>): FloatArray
    /** Last 16 embeddings → probability that "hey jarvis" was just said. */
    fun score(features: Array<FloatArray>): Float
}

/**
 * Streaming "Hey JARVIS" detector, a direct port of openWakeWord's AudioFeatures/Model streaming path:
 * 1280-sample (80 ms) chunks of 16 kHz audio, mel spectrogram over the chunk plus 480 samples of context,
 * one 76-frame embedding per chunk, and a classifier over the last 16 embeddings. Validated against the
 * Python reference on synthetic audio (see docs/OPEN-SOURCE-RESEARCH.md).
 *
 * Battery: when [quietRms] > 0, chunks quieter than that (after [HANGOVER] chunks of quiet) skip all three models
 * and score 0; only their audio is kept. As soon as sound returns, the skipped chunks are replayed through the mel
 * spectrogram and embedding models, so the classifier sees exactly what it would have seen without the gate.
 */
class WakeWordDetector(private val models: WakeWordModels, seed: Int = 0, private val quietRms: Float = 0f, private val gate: NoiseGate? = null) {
    private val raw = ShortArray(CHUNK + CONTEXT)
    private var rawFilled = 0
    private val mel = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()
    /** Mel inputs of skipped steps, newest last: 16 to re-embed plus enough before them to fill a 76-frame window. */
    private val skipped = ArrayDeque<FloatArray>()
    private var frames = 0
    private var hangover = 0
    var skippedSteps = 0L
        private set
    private val initialFeatures: List<FloatArray>

    init {
        repeat(MEL_WINDOW) { mel.addLast(FloatArray(MEL_BINS) { 1f }) }
        // Like the reference, start from embeddings of low-level noise so early scores are meaningful.
        val random = Random(seed)
        val noise = FloatArray(16_000 * 4) { (random.nextInt(-1000, 1000)).toFloat() }
        val spec = models.melspectrogram(noise).map(::transform)
        var i = 0
        while (i + MEL_WINDOW <= spec.size) {
            features.addLast(models.embedding(spec.subList(i, i + MEL_WINDOW).toTypedArray()))
            i += 8
        }
        while (features.size > FEATURES) features.removeFirst()
        initialFeatures = features.toList()
    }

    /**
     * Back to the freshly-built state without re-running the models: used after JARVIS spoke or listened, so stale
     * audio never scores, at no CPU cost (rebuilding the detector re-embedded 4 s of noise every time).
     */
    fun reset() {
        rawFilled = 0
        mel.clear()
        repeat(MEL_WINDOW) { mel.addLast(FloatArray(MEL_BINS) { 1f }) }
        features.clear()
        features.addAll(initialFeatures)
        skipped.clear()
        frames = 0
        hangover = 0
    }

    /** Feed exactly [CHUNK] samples; returns the wake-word score for this 80 ms step. */
    fun process(chunk: ShortArray): Float {
        require(chunk.size == CHUNK) { "chunk must be $CHUNK samples" }
        // Keep the newest CHUNK + CONTEXT samples.
        if (rawFilled < raw.size) {
            val keep = minOf(rawFilled, raw.size - CHUNK)
            System.arraycopy(raw, rawFilled - keep, raw, 0, keep)
            System.arraycopy(chunk, 0, raw, keep, CHUNK)
            rawFilled = keep + CHUNK
        } else {
            System.arraycopy(raw, CHUNK, raw, 0, CONTEXT)
            System.arraycopy(chunk, 0, raw, CONTEXT, CHUNK)
        }
        val input = FloatArray(rawFilled) { raw[it].toFloat() }
        frames++

        val threshold = gate?.let { g -> rms(chunk).also(g::observe).let { g.threshold } } ?: quietRms
        if (threshold > 0f) {
            if (rms(chunk) >= threshold) hangover = HANGOVER
            else if (hangover > 0) hangover--
            else {
                skipped.addLast(input)
                while (skipped.size > REPLAY_MAX) skipped.removeFirst()
                skippedSteps++
                return 0f
            }
            // Sound is back: replay what the gate skipped (embedding only the 16 steps the classifier will use).
            val count = skipped.size
            skipped.forEachIndexed { k, old ->
                appendMel(old)
                if (k >= count - FEATURES) addEmbedding()
            }
            skipped.clear()
        }
        appendMel(input)
        addEmbedding()
        val score = models.score(features.toList().takeLast(FEATURES).toTypedArray())
        // The reference ignores the first few predictions while buffers fill.
        return if (frames < WARMUP_FRAMES) 0f else score
    }

    private fun appendMel(input: FloatArray) {
        models.melspectrogram(input).forEach { mel.addLast(transform(it)) }
        while (mel.size > MEL_MAX) mel.removeFirst()
    }

    private fun addEmbedding() {
        features.addLast(models.embedding(mel.toList().takeLast(MEL_WINDOW).toTypedArray()))
        while (features.size > FEATURES_MAX) features.removeFirst()
    }

    private fun transform(frame: FloatArray) = FloatArray(frame.size) { frame[it] / 10f + 2f }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 1280
        const val CONTEXT = 480
        const val MEL_BINS = 32
        const val MEL_WINDOW = 76
        const val MEL_MAX = 970
        const val FEATURES = 16
        const val FEATURES_MAX = 120
        const val WARMUP_FRAMES = 5
        /** Keep running the full model for 1.28 s after the last loud chunk (the score peaks just after the phrase). */
        const val HANGOVER = 16
        /** 16 steps to re-embed + 10 earlier steps (80 mel frames) so the oldest one has a full 76-frame window. */
        const val REPLAY_MAX = FEATURES + 10
        /**
         * Production gate, about −42 dBFS. Measured room tone on the test phone was RMS 70–110 (p90 150–230); speech
         * at arm's length is typically −30 to −20 dBFS. One loud chunk anywhere in the phrase is enough, because the
         * skipped embeddings before it are rebuilt.
         */
        const val DEFAULT_QUIET_RMS = 250f

        fun rms(chunk: ShortArray): Float {
            var sum = 0.0
            for (s in chunk) sum += s.toDouble() * s
            return kotlin.math.sqrt(sum / chunk.size).toFloat()
        }
    }
}

/**
 * The quiet gate's threshold follows the room: about 2.5× the noise floor (a slow running estimate of the quieter
 * chunks), kept between [min] and [max]. In a quiet room quiet speech is no longer skipped; in a noisy one the cap keeps
 * the old behaviour. Each chunk only updates two numbers.
 */
class NoiseGate(private val min: Float = 120f, private val max: Float = WakeWordDetector.DEFAULT_QUIET_RMS, start: Float = 100f) {
    var floor: Float = start
        private set
    val threshold: Float get() = (floor * 2.5f).coerceIn(min, max)

    fun observe(rms: Float) {
        // Falls quickly to quieter levels, rises slowly, and ignores speech-level chunks entirely.
        floor = when {
            rms < floor -> floor + (rms - floor) * 0.2f
            rms < threshold -> floor + (rms - floor) * 0.01f
            else -> floor
        }
    }
}

/** Turns per-frame scores into one detection: threshold plus a cooldown so one phrase fires once. */
class WakeWordTrigger(private val threshold: Float = 0.5f, private val cooldownMs: Long = 2_000, private val clock: () -> Long = System::currentTimeMillis) {
    private var lastFired = Long.MIN_VALUE / 2

    fun onScore(score: Float): Boolean {
        if (score < threshold) return false
        val now = clock()
        if (now - lastFired < cooldownMs) return false
        lastFired = now
        return true
    }

    /** After the mic was handed back (JARVIS spoke or listened), ignore echoes for a moment. */
    fun suppressFor(ms: Long) {
        lastFired = clock() - cooldownMs + ms
    }
}

/**
 * One owner of the microphone at a time. The wake-word listener must pause whenever JARVIS is speaking (so it
 * cannot hear and trigger on itself) or the speech recognizer is capturing a command.
 */
object MicArbiter {
    private val _speaking = MutableStateFlow(false)
    private val _recognizing = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    val recognizing: StateFlow<Boolean> = _recognizing
    fun setSpeaking(value: Boolean) { _speaking.value = value }
    fun setRecognizing(value: Boolean) { _recognizing.value = value }
    val micBusy: Boolean get() = _speaking.value || _recognizing.value

    /** Longer than any spoken answer (≤ 600 characters) or one recogniser session. */
    const val STUCK_MS = 120_000L

    fun release() {
        _speaking.value = false
        _recognizing.value = false
    }
}

/**
 * In-process signals between the wake-word service and the UI.
 *
 * After "Hey JARVIS" the service captures the next spoken request itself (see [WakeCommandCoordinator]) and offers the
 * text to [commandSink], which a live JARVIS screen model registers. If nobody can take it, the service posts a
 * notification; tapping it comes back here as [pendingCommand] (the request to run) or [pendingListen] (listen again).
 */
object WakeWordEvents {
    private val _pendingListen = MutableStateFlow(false)
    val pendingListen: StateFlow<Boolean> = _pendingListen
    private val _pendingCommand = MutableStateFlow<String?>(null)
    val pendingCommand: StateFlow<String?> = _pendingCommand
    private val _listening = MutableStateFlow(false)
    /** True while the wake-word service is actively capturing audio (shown in the UI and notification). */
    val listening: StateFlow<Boolean> = _listening
    private val _serviceRunning = MutableStateFlow(false)
    /** True while the wake-word service process-side is alive (even if the microphone is paused). */
    val serviceRunning: StateFlow<Boolean> = _serviceRunning
    private val _capture = MutableStateFlow(CapturePhase.IDLE)
    /** LISTENING while JARVIS captures the request that followed "Hey JARVIS". */
    val capture: StateFlow<CapturePhase> = _capture
    /** Registered by the live screen model; returns true when it took the request and will run it. */
    @Volatile var commandSink: ((String) -> Boolean)? = null
    /** Registered by the running wake-word service: capture one spoken answer (returns false if it can't start). */
    @Volatile var followUpCapture: (() -> Boolean)? = null
    /** Registered by the running wake-word service: abandon a capture in progress and release the microphone. */
    @Volatile var cancelCapture: (() -> Unit)? = null
    private val _micLive = MutableStateFlow(false)
    /** True only while a recogniser is actually ready and listening (not while it is still starting). */
    val micLive: StateFlow<Boolean> = _micLive
    private val _level = MutableStateFlow(0f)
    /** 0-1 microphone level during a service capture, for the waveform. */
    val level: StateFlow<Float> = _level
    fun setMicLive(value: Boolean) { _micLive.value = value; if (!value) _level.value = 0f }
    fun setLevel(rmsDb: Float) { _level.value = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f) }
    private var detectionCount = 0
    val detections: Int get() = detectionCount

    fun detected() { detectionCount++ }
    /** Ask the visible screen to start a normal push-to-talk capture (used by the "tap to talk" notification). */
    fun requestListen() { _pendingListen.value = true }
    /** Returns true once per request. */
    fun consumeListenRequest(): Boolean {
        if (!_pendingListen.value) return false
        _pendingListen.value = false
        return true
    }
    /** A captured request that came back through a notification tap. */
    fun postCommand(text: String) { _pendingCommand.value = text }
    fun consumeCommand(): String? = _pendingCommand.value?.also { _pendingCommand.value = null }
    fun setListening(value: Boolean) { _listening.value = value }
    fun setServiceRunning(value: Boolean) { _serviceRunning.value = value }
    fun setCapture(phase: CapturePhase) { _capture.value = phase }
    private val _notice = MutableStateFlow<VoiceNotice?>(null)
    /** The last background capture that ended without a request (nothing heard, or the recogniser failed). */
    val notice: StateFlow<VoiceNotice?> = _notice
    fun captureFailed(at: Long = System.currentTimeMillis()) { _notice.value = VoiceNotice(VoiceIndicator.ERROR, at) }
}
