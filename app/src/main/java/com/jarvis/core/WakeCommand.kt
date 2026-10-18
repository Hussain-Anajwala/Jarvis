package com.jarvis.core

/** What one spoken request produced. */
sealed interface CaptureResult {
    data class Transcript(val text: String) : CaptureResult
    /** The user said nothing (or nothing recognisable) before the recogniser gave up. */
    data object NoSpeech : CaptureResult
    data class Failed(val reason: String) : CaptureResult
}

/** Captures one spoken request. [start] must report exactly one result, on the main thread. */
interface CommandRecognizer {
    fun start(onResult: (CaptureResult) -> Unit)
    fun cancel()
}

/** Runs a task later on the main thread; the returned function cancels it. Faked in tests. */
fun interface DelayedRunner {
    fun runAfter(delayMs: Long, task: () -> Unit): () -> Unit
}

enum class CapturePhase { IDLE, LISTENING }

/** Short audible/haptic hints that JARVIS is (or is no longer) listening for a request. */
enum class CaptureCue { LISTENING, HEARD, FAILED }

/**
 * Everything that happens between hearing "Hey JARVIS" and having the user's request as text:
 * claim the microphone from the wake-word listener, signal, capture one request, release the microphone, and
 * hand the text to whoever can run it.
 *
 * Why the service captures the request itself: Android does not let an app open its own screen from the
 * background (a notification tap is the only route), but a microphone foreground service may keep recording. So the
 * request is captured here, wherever the user is, and delivered to a live JARVIS screen model if one exists.
 *
 * Duplicate wake-ups while a capture is running are ignored, a watchdog guarantees the microphone is always handed
 * back, and results that arrive after the watchdog fired are dropped.
 */
class WakeCommandCoordinator(
    private val recognizer: CommandRecognizer,
    private val runner: DelayedRunner,
    /** Claims or releases the microphone for recognition (the wake-word listener pauses while claimed). */
    private val claimMic: (Boolean) -> Unit,
    private val cue: (CaptureCue) -> Unit,
    /** Offers the request to a live JARVIS; true means it was taken and will be run. */
    private val deliver: (String) -> Boolean,
    /** The request could not be delivered ([text] set) or could not be captured at all ([text] null). */
    private val undelivered: (text: String?, reason: String?) -> Unit,
    private val setPhase: (CapturePhase) -> Unit = {},
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** Time for the wake-word listener to release the microphone and for the cue to finish. */
    private val settleMs: Long = DEFAULT_SETTLE_MS
) {
    private var phase = CapturePhase.IDLE
    private var generation = 0
    private var cancelWatchdog: (() -> Unit)? = null
    private var cancelStart: (() -> Unit)? = null

    val capturing: Boolean @Synchronized get() = phase == CapturePhase.LISTENING

    /** Returns false (and does nothing) if a request is already being captured. */
    @Synchronized
    fun onWakeWord(): Boolean {
        if (phase != CapturePhase.IDLE) return false
        phase = CapturePhase.LISTENING
        val mine = ++generation
        setPhase(CapturePhase.LISTENING)
        claimMic(true)
        cue(CaptureCue.LISTENING)
        cancelStart = runner.runAfter(settleMs) { startRecognizer(mine) }
        cancelWatchdog = runner.runAfter(timeoutMs) { timeOut(mine) }
        return true
    }

    /** Stops an unfinished capture (service stopping, wake word turned off) and returns the microphone. */
    @Synchronized
    fun cancel() {
        if (phase == CapturePhase.IDLE) return
        generation++
        runCatching { recognizer.cancel() }
        reset()
    }

    private fun startRecognizer(mine: Int) {
        synchronized(this) { if (mine != generation || phase != CapturePhase.LISTENING) return }
        try {
            recognizer.start { result -> finish(mine, result) }
        } catch (error: RuntimeException) {
            finish(mine, CaptureResult.Failed("could not start speech recognition (${error.javaClass.simpleName})"))
        }
    }

    private fun timeOut(mine: Int) {
        synchronized(this) { if (mine != generation || phase != CapturePhase.LISTENING) return }
        runCatching { recognizer.cancel() }
        finish(mine, CaptureResult.NoSpeech)
    }

    private fun finish(mine: Int, result: CaptureResult) {
        synchronized(this) {
            if (mine != generation || phase != CapturePhase.LISTENING) return
            generation++
            reset()
        }
        when (result) {
            is CaptureResult.Transcript -> {
                val text = result.text.trim()
                if (text.isEmpty()) {
                    cue(CaptureCue.FAILED)
                    return
                }
                cue(CaptureCue.HEARD)
                if (!deliver(text)) undelivered(text, null)
            }
            CaptureResult.NoSpeech -> cue(CaptureCue.FAILED)
            is CaptureResult.Failed -> {
                cue(CaptureCue.FAILED)
                undelivered(null, result.reason)
            }
        }
    }

    private fun reset() {
        cancelWatchdog?.invoke()
        cancelStart?.invoke()
        cancelWatchdog = null
        cancelStart = null
        phase = CapturePhase.IDLE
        claimMic(false)
        setPhase(CapturePhase.IDLE)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 12_000L
        const val DEFAULT_SETTLE_MS = 450L
    }
}
