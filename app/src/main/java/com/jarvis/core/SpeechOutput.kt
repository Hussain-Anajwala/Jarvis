package com.jarvis.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A completed assistant answer. Speech is driven only by these events — never by whatever text happens to be on
 * screen — so the user's own words, placeholders ("Summarizing…"), and history are never spoken.
 * [privateContent] marks answers built from messages, email, or memories: they are never sent to a cloud voice.
 */
data class AssistantReply(val id: Long, val text: String, val privateContent: Boolean = false)

/** Displayed text plus the stream of completed answers. Progress text is shown but never becomes an answer. */
class ReplyChannel(initial: String, private val onAnswer: (AssistantReply) -> Unit) {
    var display: String = initial
        private set
    private var nextId = 1L

    fun progress(text: String) {
        display = text
    }

    fun answer(text: String, privateContent: Boolean = false): AssistantReply {
        display = text
        val reply = AssistantReply(nextId++, text, privateContent)
        onAnswer(reply)
        return reply
    }
}

enum class SpeechProvider { SYSTEM, ELEVENLABS }

data class SpeechSettings(
    val autoSpeak: Boolean = false,
    val provider: SpeechProvider = SpeechProvider.SYSTEM,
    val voiceId: String = ElevenLabsTts.DEFAULT_VOICE_ID,
    val rate: Float = 1.0f
)

sealed interface SpeechResult {
    data object Completed : SpeechResult
    data class Failed(val reason: String) : SpeechResult
}

/** One way to turn text into audio. speak() suspends until playback ends; cancelling the caller stops it. */
interface SpeechEngine {
    val label: String
    val available: Boolean
    suspend fun speak(text: String, settings: SpeechSettings): SpeechResult
    fun stop()
}

enum class SpeechPhase { IDLE, PREPARING, SPEAKING, STOPPED, FAILED }

data class SpeechStatus(val phase: SpeechPhase = SpeechPhase.IDLE, val engine: String? = null, val detail: String? = null)

/**
 * Owns spoken output for the whole app (held by the ViewModel, not by a screen, so recomposition or navigation
 * never replays an answer). Speaks each answer id at most once, newest wins, Stop cancels everything.
 */
class SpeechOutputController(
    private val scope: CoroutineScope,
    private val system: SpeechEngine,
    private val cloud: SpeechEngine?,
    private val settings: () -> SpeechSettings,
    /** Diagnostics only: answer id, length, and engine — never the spoken text. */
    private val log: (String) -> Unit = {}
) {
    private val _status = MutableStateFlow(SpeechStatus())
    val status: StateFlow<SpeechStatus> = _status
    private var lastHandledId = 0L
    private var job: Job? = null

    /** Called for every completed answer; earlier or repeated ids are ignored. */
    fun onAssistantReply(reply: AssistantReply) {
        if (reply.id <= lastHandledId) return
        lastHandledId = reply.id
        val current = settings()
        if (!current.autoSpeak || reply.text.isBlank()) {
            log("answer ${reply.id} not spoken (auto-speak off or empty)")
            return
        }
        speak(reply, current)
    }

    /** Explicit "read this answer" from the user, even when automatic speech is off. */
    fun replay(reply: AssistantReply) = speak(reply, settings())

    private fun speak(reply: AssistantReply, current: SpeechSettings) {
        cancelPlayback()
        val text = VoicePolicy.speakable(reply.text)
        val useCloud = current.provider == SpeechProvider.ELEVENLABS && cloud?.available == true && !reply.privateContent
        val primary = if (useCloud) cloud!! else system
        val note = if (current.provider == SpeechProvider.ELEVENLABS && reply.privateContent) {
            "Private answer read with the phone voice; it is never sent to ElevenLabs"
        } else null
        _status.value = SpeechStatus(SpeechPhase.PREPARING, primary.label, note)
        log("speaking answer ${reply.id} (${text.length} chars) with ${primary.label}")
        job = scope.launch {
            _status.value = SpeechStatus(SpeechPhase.SPEAKING, primary.label, note)
            when (val result = primary.speak(text, current)) {
                SpeechResult.Completed -> _status.value = SpeechStatus(SpeechPhase.IDLE, primary.label, note)
                is SpeechResult.Failed -> {
                    if (primary !== system && system.available) {
                        // Cloud voice failed (network, quota, key): say it with the phone's voice instead.
                        val why = "${primary.label} unavailable: ${result.reason}"
                        _status.value = SpeechStatus(SpeechPhase.SPEAKING, system.label, why)
                        val fallback = system.speak(text, current)
                        _status.value = if (fallback is SpeechResult.Failed) {
                            SpeechStatus(SpeechPhase.FAILED, system.label, fallback.reason)
                        } else {
                            SpeechStatus(SpeechPhase.IDLE, system.label, why)
                        }
                    } else {
                        _status.value = SpeechStatus(SpeechPhase.FAILED, primary.label, result.reason)
                    }
                }
            }
        }
    }

    /** Stop button, Speak-replies off, starting voice input, or a new request. */
    fun stop() {
        log("stop")
        cancelPlayback()
        _status.value = SpeechStatus(SpeechPhase.STOPPED)
    }

    /** True while audio is being prepared or played (the wake-word listener must stay paused). */
    val isBusy: Boolean get() = _status.value.phase == SpeechPhase.PREPARING || _status.value.phase == SpeechPhase.SPEAKING

    private fun cancelPlayback() {
        job?.cancel()
        job = null
        system.stop()
        cloud?.stop()
    }
}
