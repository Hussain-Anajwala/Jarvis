package com.jarvis.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val rate: Float = 1.0f,
    /** Also read answers built from message or email content with ElevenLabs (off: those use the phone voice). */
    val cloudForPrivate: Boolean = false
)

sealed interface SpeechResult {
    data object Completed : SpeechResult
    /** [retryable]: a rate limit, server error, or dropped connection that may succeed on a second try. */
    data class Failed(val reason: String, val retryable: Boolean = false) : SpeechResult
}

/** One way to turn text into audio. speak() suspends until playback ends; cancelling the caller stops it. */
interface SpeechEngine {
    val label: String
    val available: Boolean
    suspend fun speak(text: String, settings: SpeechSettings): SpeechResult
    fun stop()
}

enum class SpeechPhase { IDLE, PREPARING, SPEAKING, STOPPED, FAILED }

data class SpeechStatus(
    val phase: SpeechPhase = SpeechPhase.IDLE,
    val engine: String? = null,
    val detail: String? = null,
    /** The answer this status is about, so a finished question can hand over to follow-up listening. */
    val replyId: Long? = null
)

/** Which voice reads an answer, decided once per answer so a response never changes voice part-way. */
object VoiceRouting {
    sealed interface Decision {
        data object Cloud : Decision
        data class Phone(val reason: String?) : Decision
    }

    fun decide(settings: SpeechSettings, cloudAvailable: Boolean, privateContent: Boolean, speakableLength: Int, secret: Boolean = false): Decision = when {
        settings.provider != SpeechProvider.ELEVENLABS -> Decision.Phone(null)
        !cloudAvailable -> Decision.Phone("ElevenLabs isn't set up in this build")
        // A code, PIN, card or ID number is never sent to a cloud voice, whatever the settings say.
        secret -> Decision.Phone("This answer contains a code or number that stays on your phone, so the phone voice read it")
        privateContent && !settings.cloudForPrivate ->
            Decision.Phone("Message or email content is read with the phone voice (Settings → Voice replies can change this)")
        speakableLength > ElevenLabsTts.MAX_CHARS -> Decision.Phone("Too long for one ElevenLabs request, so the whole answer uses the phone voice")
        else -> Decision.Cloud
    }
}

/**
 * The one place answers become audio (held by the ViewModel, so recomposition or navigation never replays an answer).
 * Speaks each answer id at most once; a newer answer or Stop cancels the current one before anything new plays, so
 * speech never overlaps. The voice is chosen once per answer: ElevenLabs audio is fully received before it plays, so a
 * failure (after one retry) switches the *whole* answer to the phone voice, never the second half of it.
 */
class SpeechOutputController(
    private val scope: CoroutineScope,
    private val system: SpeechEngine,
    private val cloud: SpeechEngine?,
    private val settings: () -> SpeechSettings,
    /** Diagnostics only: answer id, length, engine, and failure reason — never the spoken text. */
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: Long = 700
) {
    private val _status = MutableStateFlow(SpeechStatus())
    val status: StateFlow<SpeechStatus> = _status
    private var lastHandledId = 0L
    private var job: Job? = null
    private var speakNextUntil = 0L

    /**
     * A hands-free request ("Hey JARVIS …", push-to-talk) is answered aloud even when automatic speech is off, because
     * the user is not looking at the screen. Applies to the next completed answer only, and only within [withinMs].
     */
    @Synchronized
    fun speakNextAnswerAloud(withinMs: Long = 60_000) {
        speakNextUntil = clock() + withinMs
    }

    @Synchronized
    fun cancelSpeakNext() {
        speakNextUntil = 0
    }

    @Synchronized
    private fun takeSpeakNext(): Boolean = (clock() < speakNextUntil).also { if (it) speakNextUntil = 0 }

    /** Called for every completed answer; earlier or repeated ids are ignored. */
    fun onAssistantReply(reply: AssistantReply) {
        if (reply.id <= lastHandledId) return
        lastHandledId = reply.id
        val current = settings()
        val requestedAloud = takeSpeakNext()
        if ((!current.autoSpeak && !requestedAloud) || reply.text.isBlank()) {
            log("answer ${reply.id} not spoken (auto-speak off or empty)")
            return
        }
        speak(reply, current)
    }

    /** True if [replyId] is being prepared or played right now. */
    fun isSpeaking(replyId: Long): Boolean = _status.value.let {
        it.replyId == replyId && (it.phase == SpeechPhase.PREPARING || it.phase == SpeechPhase.SPEAKING)
    }

    /** Explicit "read this answer" from the user, even when automatic speech is off. */
    fun replay(reply: AssistantReply) = speak(reply, settings())

    private fun speak(reply: AssistantReply, current: SpeechSettings) {
        cancelPlayback()
        val text = SpeechText.forSpeech(VoicePolicy.speakable(reply.text))
        val decision = VoiceRouting.decide(current, cloud?.available == true, reply.privateContent, text.length, SensitiveData.isSecret(reply.text))
        val primary = if (decision is VoiceRouting.Decision.Cloud) cloud!! else system
        val note = (decision as? VoiceRouting.Decision.Phone)?.reason?.takeIf { current.provider == SpeechProvider.ELEVENLABS }
        _status.value = SpeechStatus(SpeechPhase.PREPARING, primary.label, note, reply.id)
        log("speaking answer ${reply.id} (${text.length} chars) with ${primary.label}${note?.let { " — $it" }.orEmpty()}")
        job = scope.launch {
            _status.value = SpeechStatus(SpeechPhase.SPEAKING, primary.label, note, reply.id)
            var result = primary.speak(text, current)
            if (result is SpeechResult.Failed && primary !== system && result.retryable) {
                // A brief rate limit or a dropped connection: one more try before changing voices.
                log("${primary.label} failed for answer ${reply.id}: ${result.reason}; retrying once")
                delay(retryDelayMs)
                result = primary.speak(text, current)
            }
            when (result) {
                SpeechResult.Completed -> {
                    log("answer ${reply.id} finished with ${primary.label}")
                    _status.value = SpeechStatus(SpeechPhase.IDLE, primary.label, note, reply.id)
                }
                is SpeechResult.Failed -> {
                    log("${primary.label} failed for answer ${reply.id}: ${result.reason}")
                    if (primary !== system && system.available) {
                        // Nothing of this answer was played by ElevenLabs, so the phone voice reads all of it.
                        val why = "${primary.label} unavailable (${result.reason}); this answer used the phone voice"
                        _status.value = SpeechStatus(SpeechPhase.SPEAKING, system.label, why, reply.id)
                        val fallback = system.speak(text, current)
                        log("answer ${reply.id} fallback with ${system.label}: ${if (fallback is SpeechResult.Failed) "failed (${fallback.reason})" else "finished"}")
                        _status.value = if (fallback is SpeechResult.Failed) {
                            SpeechStatus(SpeechPhase.FAILED, system.label, fallback.reason, reply.id)
                        } else {
                            SpeechStatus(SpeechPhase.IDLE, system.label, why, reply.id)
                        }
                    } else {
                        _status.value = SpeechStatus(SpeechPhase.FAILED, primary.label, result.reason, reply.id)
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
