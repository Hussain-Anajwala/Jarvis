package com.jarvis.ui

import android.app.Application
import android.util.Log
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.AlarmClock
import com.jarvis.core.CommunicationRequestParser
import com.jarvis.core.MeetingRequestParser
import com.jarvis.core.ContactResolution
import com.jarvis.core.ContactResolver
import com.jarvis.core.CalendarProviderGateway
import com.jarvis.core.ReasoningProviderFactory
import com.jarvis.core.WhatsAppLink
import com.jarvis.core.AndroidPhoneToolRuntime
import com.jarvis.core.PhoneActionCoordinator
import com.jarvis.core.PhoneActionHost
import com.jarvis.core.AndroidLocationSource
import com.jarvis.core.LegacyPersonalFactsMigration
import com.jarvis.core.MemoryClassifier
import com.jarvis.core.MemoryRepository
import com.jarvis.core.OsrmTravelContextAgent
import com.jarvis.core.JarvisNotificationListener
import com.jarvis.core.MessageFilter
import com.jarvis.core.AssistantReply
import com.jarvis.core.ApiCredentials
import com.jarvis.core.AlarmRequestParser
import com.jarvis.core.ChoiceAnswers
import com.jarvis.core.ChoicePick
import com.jarvis.core.ClockCommand
import com.jarvis.core.ClockCommandParser
import com.jarvis.core.ClockPlan
import com.jarvis.core.ClockPlanner
import com.jarvis.core.ContactAccess
import com.jarvis.core.ContactNames
import com.jarvis.core.EmailDialogue
import com.jarvis.core.EmailRequest
import com.jarvis.core.EmailSlot
import com.jarvis.core.EmailStep
import com.jarvis.core.VoicePolicy
import com.jarvis.core.ElevenLabsTts
import com.jarvis.core.ElevenLabsVoiceDirectory
import com.jarvis.core.ElevenLabsVoices
import com.jarvis.core.VoiceCheck
import com.jarvis.core.ReplyChannel
import com.jarvis.core.SecretStore
import com.jarvis.core.SpeechOutputController
import com.jarvis.core.SpeechProvider
import com.jarvis.core.SpeechSettings
import com.jarvis.core.SpeechPhase
import com.jarvis.core.MicArbiter
import com.jarvis.core.WakeWordEvents
import com.jarvis.core.WakeWordService
import com.jarvis.core.WakeWordSettings
import com.jarvis.core.BriefingScheduler
import com.jarvis.core.BriefingService
import com.jarvis.core.BriefingSettings
import com.jarvis.core.BriefingSettingsStore
import android.Manifest
import com.jarvis.core.EmailComposer
import com.jarvis.core.EmailDraft
import com.jarvis.core.EmailReplies
import com.jarvis.core.EmailResolution
import com.jarvis.core.EmailSummary
import com.jarvis.core.GmailClient
import com.jarvis.core.GmailError
import com.jarvis.core.GmailResult
import com.jarvis.core.resolveContactEmail
import com.jarvis.core.PhoneToolExecution
import com.jarvis.core.PhoneToolRequestCodec
import com.jarvis.core.PlanStep
import com.jarvis.core.ResolvedContact
import com.jarvis.BuildConfig
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.JarvisCore
import com.jarvis.core.BackgroundEngine
import com.jarvis.data.AppDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class JarvisViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val MAX_WAKE_COMMAND_CHARS = 500
        private const val TAG = "JarvisCancellation"
        private const val ALARM_TAG = "JarvisAlarm"
    }
    private val db = AppDatabase.create(application)
    private val calendarProvider = CalendarProviderGateway(application)
    private val reasoningProvider = ReasoningProviderFactory.create(application)
    private val phoneToolRuntime = AndroidPhoneToolRuntime(application, BuildConfig.YOUTUBE_API_KEY)
    private val memoryRepository = MemoryRepository(db.memoryDao())
    private val locationSource = AndroidLocationSource(application)
    private val travelAgent = OsrmTravelContextAgent.forDevice(application, locationSource)
    private val privacyPreferences = com.jarvis.core.PrivacyPreferences(application)
    /** Settings → Privacy: off keeps every request on the phone. */
    var cloudAiAllowed by mutableStateOf(privacyPreferences.cloudAllowed)
        private set
    fun updateCloudAiAllowed(allowed: Boolean) {
        privacyPreferences.cloudAllowed = allowed
        cloudAiAllowed = allowed
        viewModelScope.launch { core.recordPhoneToolOutcome("privacy", if (allowed) "cloud AI turned on in Settings" else "cloud AI turned off in Settings: requests stay on the phone") }
    }
    private val core = JarvisCore(
        db,
        provider = reasoningProvider,
        calendarProvider = calendarProvider,
        travel = travelAgent,
        memory = memoryRepository,
        notificationAccess = { JarvisNotificationListener.isAccessEnabled(application) },
        locationSource = locationSource,
        privacy = { privacyPreferences.settings() }
    )
    val memories = db.memoryDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { LegacyPersonalFactsMigration.migrate(application, memoryRepository) }
        // Unreachable standalone reminders left by the old reminder code (never shown, never fired) are removed.
        viewModelScope.launch {
            val removed = db.reminderDao().deleteOrphans()
            if (removed > 0) core.recordPhoneToolOutcome("maintenance.reminders", "removed $removed orphaned reminder(s) that could never be shown or fire")
        }
        // Observed events left unhandled by a crash are handled now (handlers re-check current state first).
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { com.jarvis.core.AppEventBus.create(application).recover() }
        // Multi-step plans interrupted by a restart: reconciled and resumed only when safe (never repeated blindly).
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { com.jarvis.core.PlanExecutors.create(application).recover() }
        // Reminders and plan steps: re-armed, delivered late, or reported missed after the app was stopped.
        // (Not `taskEngine`: that lazy property is declared further down and is not initialised yet here.)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { com.jarvis.core.TaskEngines.create(application).recover() }
        // Timers that were due while the app or phone was down are rescheduled or reported as missed.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { com.jarvis.core.JarvisTimerEngine(application).recover() }
    }

    fun addMemory(text: String) {
        if (com.jarvis.core.SensitiveData.isSecret(text)) {
            reply = com.jarvis.core.SensitiveData.REFUSAL
            return
        }
        val classified = MemoryClassifier.classify(text) ?: return
        viewModelScope.launch { memoryRepository.remember(classified, "added in Memory tab") }
    }

    fun editMemory(id: String, value: String) {
        viewModelScope.launch { memoryRepository.edit(id, value) }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch {
            memoryRepository.delete(id)
            core.recordPhoneToolOutcome("forget_memory", "deleted one memory in Memory tab")
        }
    }

    fun clearMemories() {
        viewModelScope.launch {
            memoryRepository.clearAll()
            core.recordPhoneToolOutcome("forget_memory", "all memories cleared by user")
        }
    }
    private val phoneActions = PhoneActionCoordinator(object : PhoneActionHost {
        override fun isGranted(permission: String): Boolean =
            ContextCompat.checkSelfPermission(getApplication(), permission) == PackageManager.PERMISSION_GRANTED
        override suspend fun resolveContact(name: String): ContactResolution = withContext(Dispatchers.IO) {
            ContactResolver(getApplication<Application>().contentResolver).resolve(name)
        }
        override suspend fun execute(step: PlanStep): PhoneToolExecution = phoneToolRuntime.execute(step)
        override suspend fun callConfirmedContact(contact: ResolvedContact): PhoneToolExecution =
            phoneToolRuntime.callConfirmedContact(contact)
        override suspend fun recordOutcome(toolName: String, outcome: String) =
            core.recordPhoneToolOutcome(toolName, outcome)
    })
    val plans = db.planDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val planItems = db.planItemDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val traces = db.traceDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val reminders = db.reminderDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val events = db.eventDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val storedNotificationCount = db.notificationRecordDao().observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val automationRules = db.automationRuleDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /**
     * What the reply card shows. Assigning [reply] means "a completed assistant answer" (spoken when enabled);
     * placeholders and the user's own transcript go through [progress] and are never spoken.
     */
    private var replyText by mutableStateOf("Try: “I have a meeting tomorrow at 10 AM at college.”")
    var reply: String
        get() = replyText
        private set(value) = answer(value)
    var lastAnswer by mutableStateOf<AssistantReply?>(null)
        private set
    private val replies = ReplyChannel(replyText) { answered ->
        lastAnswer = answered
        speech.onAssistantReply(answered)
    }

    private fun progress(text: String) {
        replies.progress(text)
        replyText = text
    }

    private fun answer(text: String, privateContent: Boolean = false, expectsReply: Boolean = false) {
        replyText = text
        awaitingReplyId = null
        val answered = replies.answer(text, privateContent)
        appendMessage(ChatMessage(answered.id, fromUser = false, text = text))
        thinking = false
        if (expectsReply) awaitingReplyId = answered.id
        // Half duplex: listen only once nothing is playing. A question that isn't read aloud while an earlier answer
        // still plays waits for that answer to end (onAnswerSpoken) instead of cutting it off.
        if (expectsReply && voiceConversation && com.jarvis.core.TurnTaking.mayListen(speech.isBusy)) startFollowUpListening()
    }

    /** Marks the newest answer as a question whose spoken reply should be listened for. */
    private fun markLastAnswerExpectsReply() {
        awaitingReplyId = lastAnswer?.id
    }

    /** This session's conversation, newest last; kept in memory only and capped (never written to storage). */
    var conversation by mutableStateOf<List<ChatMessage>>(emptyList())
        private set
    var thinking by mutableStateOf(false)
        private set
    private var nextUserMessageId = -1L

    private fun appendMessage(message: ChatMessage) {
        conversation = (conversation + message).takeLast(60)
    }
    var engineLabel by mutableStateOf(core.lastEngineLabel)
        private set
    var confirmCancel by mutableStateOf(false)
        private set
    var cancellationDialogText by mutableStateOf("This action will also cancel the linked departure reminder.")
        private set
    var communicationDraft by mutableStateOf<String?>(null)
        private set
    var editingCommunicationDraft by mutableStateOf(false)
        private set
    var choosingCommunicationTarget by mutableStateOf(false)
        private set
    var communicationRecipient by mutableStateOf<String?>(null)
        private set
    var communicationResolutionError by mutableStateOf<String?>(null)
        private set
    var callConfirmationContact by mutableStateOf<ResolvedContact?>(null)
        private set
    var phonePermissionRequest by mutableStateOf<String?>(null)
        private set
    /** Pending "summarize my messages" request: nothing is sent until the user confirms this disclosure. */
    var summaryConfirmation by mutableStateOf<SummaryConfirmation?>(null)
        private set
    private var communicationPhoneNumber: String? = null
    private var pendingCancellationPlanId by mutableStateOf<String?>(null)

    fun submit(typed: String, fromVoice: Boolean = false) {
        if (typed.isBlank()) return
        // Hindi in Devanagari reaches every parser as the Roman Hinglish they read; the chat shows what was said.
        shownUtterance = typed.trim()
        val text = com.jarvis.core.LanguageNormalizer.normalize(typed)
        if (!fromVoice) {
            // Typing ends a hands-free conversation: no automatic listening after this answer.
            voiceConversation = false
            followUpsInConversation = 0
        }
        // An open "which one?" list takes the answer first ("the second one", "cancel").
        choice?.let { pending ->
            when (val pick = ChoiceAnswers.pick(text, pending.labels)) {
                is ChoicePick.Option -> {
                    appendMessage(ChatMessage(nextUserMessageId--, fromUser = true, text = typed.trim()))
                    pickChoice(pick.index)
                    return
                }
                ChoicePick.Cancel -> {
                    appendMessage(ChatMessage(nextUserMessageId--, fromUser = true, text = typed.trim()))
                    dismissChoice()
                    return
                }
                ChoicePick.NotAnAnswer -> clearChoice() // a different request: the list is abandoned
            }
        }
        lastUtterance = text.trim()
        // A multi-step task (an email being drafted) takes the answer to its question; clock commands run locally.
        if (handlePendingDialogue(text)) return
        // A new request makes any answer still being read obsolete.
        speech.stop()
        appendMessage(ChatMessage(nextUserMessageId--, fromUser = true, text = typed.trim()))
        thinking = true
        viewModelScope.launch {
            val result = core.handle(text)
            engineLabel = core.lastEngineLabel
            if (result == "CONFIRM_CANCEL") {
                val activePlan = db.planDao().observeAll().first().firstOrNull { it.status == "active" }
                if (activePlan == null) {
                    reply = "There are no active plans to cancel."
                } else {
                    pendingCancellationPlanId = activePlan.id
                    cancellationDialogText = cancellationImpactText(activePlan.id)
                    confirmCancel = true
                }
            } else if (result.startsWith("ALARM_HANDOFF:")) {
                handoffAlarm(result.removePrefix("ALARM_HANDOFF:"))
            } else if (result.startsWith("CONFIRM_SUMMARY:")) {
                val request = org.json.JSONObject(result.removePrefix("CONFIRM_SUMMARY:"))
                summaryConfirmation = SummaryConfirmation(
                    MessageFilter(request.optString("app").ifBlank { null }, request.optString("contact").ifBlank { null }),
                    request.optInt("count"),
                    cloudProviderDisclosure()
                )
                progress("Waiting for your confirmation before any message text is sent.")
            } else if (result.startsWith("PHONE_TOOL:")) {
                handlePhoneToolRequest(result.removePrefix("PHONE_TOOL:"))
            } else if (result == "RUN_BRIEFING") {
                progress("Preparing your briefing on this device…")
                reply = briefingService.build(briefingSettings).first
                core.recordPhoneToolOutcome("daily_briefing", "assembled on device on request (no model call)")
            } else if (result.startsWith("EMAIL_TOOL:")) {
                handleEmailRequest(result.removePrefix("EMAIL_TOOL:"))
            } else if (result.startsWith("DRAFT_COMMUNICATION:")) {
                val request = result.removePrefix("DRAFT_COMMUNICATION:")
                val separator = request.indexOf('\u0000')
                val recipientName = if (separator >= 0) request.substring(0, separator) else ""
                val body = if (separator >= 0) request.substring(separator + 1) else ""
                communicationResolutionError = null
                if (recipientName.isBlank()) {
                    communicationResolutionError = "I couldn't identify a recipient. Please include a contact name."
                } else {
                    when (val resolution = resolveContact(recipientName)) {
                        is ContactResolution.Found -> showMessageDraft(resolution.contact, body)
                        is ContactResolution.Ambiguous -> offerChoice(
                            title = "Message which contact?",
                            question = ContactAccess.question("message", recipientName, resolution.options, resolution.suggestions),
                            labels = resolution.options.map(ContactAccess::label),
                            // The message the user dictated is kept and used with whoever they pick.
                            onPick = { index -> showMessageDraft(resolution.options[index], body) },
                            onCancel = { reply = "Message cancelled. Nothing was sent." }
                        )
                        ContactResolution.NotFound ->
                            communicationResolutionError = "I couldn't find a contact named $recipientName with a phone number."
                        ContactResolution.QueryFailed ->
                            communicationResolutionError = ContactAccess.READ_FAILED
                    }
                }
            } else {
                // A clarifying question ("What time?") keeps a voice conversation open for the spoken answer.
                // A waiting yes/no is a question even when the prompt ends "… Say yes or no." (it used to end the voice turn).
                answer(result, privateContent = core.lastReplyPrivate, expectsReply = result.trimEnd().endsWith("?") || core.awaitingConfirmation)
            }
            thinking = false
        }
    }

    fun confirmSummary() {
        val pending = summaryConfirmation ?: return
        summaryConfirmation = null
        progress("Summarizing…")
        viewModelScope.launch {
            answer(core.summarizeMessages(pending.filter), privateContent = true)
            engineLabel = core.lastEngineLabel
        }
    }

    fun dismissSummary() {
        if (summaryConfirmation == null) return
        summaryConfirmation = null
        reply = "Summary cancelled. No message text was sent."
    }

    fun clearNotificationData() {
        viewModelScope.launch {
            db.notificationRecordDao().clearAll()
            com.jarvis.core.NotificationCaptureClock.restart(getApplication())
            core.recordPhoneToolOutcome("count_new_messages", "stored notification data cleared by user")
        }
    }

    private fun cloudProviderDisclosure(): String {
        val providers = buildList {
            if (BuildConfig.GROQ_API_KEY.isNotBlank()) add("Groq")
            if (BuildConfig.GEMINI_API_KEY.isNotBlank() || BuildConfig.GEMINI_API_KEY_2.isNotBlank()) add("Google Gemini")
        }
        return if (providers.isEmpty()) "No cloud model is configured, so nothing can be summarized."
        else "Sender names and message text will be sent to ${providers.joinToString(", then ")} (the next one only if the first is unavailable)."
    }

    private suspend fun handlePhoneToolRequest(payload: String) {
        val step = PhoneToolRequestCodec.decode(payload)
        if (step == null) {
            reply = "I couldn't safely process that phone request. Please try again with the required details."
            return
        }
        phoneActions.handle(step)
        syncPhoneActionState()
    }

    private fun syncPhoneActionState() {
        phoneActions.reply?.let { reply = it }
        callConfirmationContact = phoneActions.callConfirmationContact
        phonePermissionRequest = phoneActions.permissionRequest
        phoneActions.callChoices?.let { options ->
            offerChoice(
                title = "Call which contact?",
                question = null, // the coordinator's reply already asked the question
                labels = options.map(ContactAccess::label),
                onPick = { index -> phoneActions.chooseCallContact(index); syncPhoneActionState() },
                onCancel = { phoneActions.dismissCallChoice(); syncPhoneActionState() }
            )
        }
    }

    private fun showMessageDraft(contact: ResolvedContact, body: String) {
        communicationRecipient = contact.displayName
        communicationPhoneNumber = contact.normalizedPhoneNumber
        communicationDraft = body
    }

    // ---- "Which one?" lists (contacts, numbers, alarms, timers) ----

    /** A pending list the user must pick from. The original request waits in [choiceHandler] until they do. */
    data class ChoiceState(val title: String, val labels: List<String>)
    var choice by mutableStateOf<ChoiceState?>(null)
        private set
    private var choiceHandler: (suspend (Int) -> Unit)? = null
    private var choiceCancel: (suspend () -> Unit)? = null

    private fun offerChoice(title: String, question: String?, labels: List<String>, onPick: suspend (Int) -> Unit, onCancel: suspend () -> Unit) {
        choice = ChoiceState(title, labels)
        choiceHandler = onPick
        choiceCancel = onCancel
        question?.let { answer(it, expectsReply = true) } ?: markLastAnswerExpectsReply()
    }

    fun pickChoice(index: Int) {
        val handler = choiceHandler ?: return
        if (index !in (choice?.labels?.indices ?: return)) return
        clearChoice()
        viewModelScope.launch { handler(index) }
    }

    fun dismissChoice() {
        val cancel = choiceCancel ?: return
        clearChoice()
        viewModelScope.launch { cancel() }
    }

    private fun clearChoice() {
        choice = null
        choiceHandler = null
        choiceCancel = null
    }

    fun confirmCallContact() {
        viewModelScope.launch {
            phoneActions.confirmCall()
            syncPhoneActionState()
        }
    }

    fun dismissCallConfirmation() {
        viewModelScope.launch {
            phoneActions.dismissCall()
            syncPhoneActionState()
        }
    }

    fun onPhonePermissionResult(permission: String, granted: Boolean) {
        phonePermissionRequest = null
        viewModelScope.launch {
            phoneActions.onPermissionResult(permission, granted)
            syncPhoneActionState()
        }
    }

    // ---- M6: daily briefing and voice settings ----

    var briefingSettings by mutableStateOf(BriefingSettingsStore.load(application))
        private set
    var lastBriefing by mutableStateOf(BriefingSettingsStore.lastBriefing(application))
        private set
    private val voicePrefs = application.getSharedPreferences("voice", android.content.Context.MODE_PRIVATE)
    private val secrets = SecretStore(application)
    var speechSettings by mutableStateOf(
        SpeechSettings(
            autoSpeak = voicePrefs.getBoolean("speakReplies", false),
            provider = runCatching { SpeechProvider.valueOf(voicePrefs.getString("provider", null) ?: "SYSTEM") }.getOrDefault(SpeechProvider.SYSTEM),
            // A hand-typed ID from an older version is mapped back to a curated voice.
            voiceId = ElevenLabsVoices.resolve(voicePrefs.getString("voiceId", null)).id,
            rate = voicePrefs.getFloat("rate", 1.0f),
            cloudForPrivate = voicePrefs.getBoolean("cloudForPrivate", false)
        )
    )
        private set
    private val credentials = ApiCredentials(secrets)
    var elevenLabsSource by mutableStateOf(credentials.elevenLabsSource())
        private set
    val elevenLabsConfigured: Boolean get() = elevenLabsSource != ApiCredentials.Source.NONE
    /** What the user's ElevenLabs account says about each curated voice; empty until checked. */
    var voiceChecks by mutableStateOf<Map<String, VoiceCheck>>(emptyMap())
        private set
    var voiceCheckRunning by mutableStateOf(false)
        private set
    private val systemVoice = SystemTtsEngine(application)
    private val elevenLabs = ElevenLabsTts(
        apiKey = credentials::elevenLabsKey,
        sinkFactory = { AudioTrackPcmSink(application) },
        log = { Log.d("JarvisSpeech", it) }
    )
    /** The only place answers become audio; owned here so screens never replay or scrape text. */
    val speech = SpeechOutputController(
        viewModelScope, systemVoice, elevenLabs, { speechSettings },
        log = { Log.d("JarvisSpeech", it) }
    )
    val speechStatus = speech.status
    val speakReplies: Boolean get() = speechSettings.autoSpeak

    init {
        // While JARVIS talks, the wake-word listener releases the microphone so it cannot hear itself.
        viewModelScope.launch {
            speech.status.collect { status ->
                MicArbiter.setSpeaking(status.phase == SpeechPhase.PREPARING || status.phase == SpeechPhase.SPEAKING)
                // A finished answer (spoken by either voice) may hand over to follow-up listening.
                if (status.phase == SpeechPhase.IDLE || status.phase == SpeechPhase.FAILED) status.replyId?.let(::onAnswerSpoken)
            }
        }
    }

    /**
     * Requests captured after "Hey JARVIS" arrive here (from the wake-word service, or from a notification tap) and
     * run exactly like typed ones, except that the answer is also read aloud: the user is not looking at the screen.
     */
    private val wakeCommandSink: (String) -> Boolean = ::handleWakeCommand

    init {
        WakeWordEvents.commandSink = wakeCommandSink
        // A background capture that ended (with or without a request) is no longer a follow-up in progress.
        viewModelScope.launch {
            // Wait until the constructor has finished: `listening` is declared further down this class.
            kotlinx.coroutines.yield()
            WakeWordEvents.capture.collect { if (it == com.jarvis.core.CapturePhase.IDLE && !listening && !captureStarting) followUpListening = false }
        }
    }

    /** Counts hands-free requests so the screen can bring the Assistant tab forward when one arrives. */
    var handsFreeRequests by mutableStateOf(0)
        private set

    fun handleWakeCommand(text: String): Boolean {
        val request = text.trim().take(MAX_WAKE_COMMAND_CHARS)
        if (request.isEmpty()) return false
        Log.d("JarvisWakeWord", "running a hands-free request (${request.length} chars)")
        startVoiceTurn()
        handsFreeRequests++
        submit(request, fromVoice = true)
        return true
    }

    var wakeWordEnabled by mutableStateOf(WakeWordSettings.enabled(application))
        private set
    val wakeWordListening = WakeWordEvents.listening
    val wakeWordPendingListen = WakeWordEvents.pendingListen
    val wakePendingCommand = WakeWordEvents.pendingCommand
    val wakeServiceRunning = WakeWordEvents.serviceRunning
    val wakeCapture = WakeWordEvents.capture

    /** Off by default. Turning it on starts a visible microphone foreground service (needs RECORD_AUDIO). */
    fun updateWakeWord(enabled: Boolean) {
        WakeWordSettings.setEnabled(getApplication(), enabled)
        wakeWordEnabled = enabled
        if (enabled) WakeWordService.start(getApplication()) else WakeWordService.stop(getApplication())
        viewModelScope.launch { core.recordPhoneToolOutcome("daily_briefing", if (enabled) "wake word listening turned on" else "wake word listening turned off") }
    }

    /** Claim the microphone before starting speech recognition (the wake-word service lets go of it). */
    fun beginVoiceCapture() {
        speech.stop()
        captureStarting = true
        MicArbiter.setRecognizing(true)
        // If the recognizer never reports ready (it can fail silently), hand the microphone back to the wake word.
        captureWatchdog?.cancel()
        captureWatchdog = viewModelScope.launch {
            kotlinx.coroutines.delay(6_000)
            if (!listening) {
                MicArbiter.setRecognizing(false)
                captureStarting = false
            }
        }
    }
    private var captureWatchdog: kotlinx.coroutines.Job? = null
    var listening by mutableStateOf(false)
        private set
    private val briefingService by lazy {
        BriefingService(application, db, locationSource, travelAgent) {
            // Use the in-session token, or one Google grants silently; never prompts from a briefing.
            if (gmailAuthorizer.cachedToken() != null || com.jarvis.core.BackgroundGmailToken.silent(getApplication()) != null) {
                GmailClient(accessToken = {
                    gmailAuthorizer.cachedToken() ?: com.jarvis.core.BackgroundGmailToken.silent(getApplication())
                }).unreadCount()
            } else null
        }
    }

    fun updateBriefingSettings(settings: BriefingSettings) {
        briefingSettings = settings
        BriefingSettingsStore.save(getApplication(), settings)
        BriefingScheduler.reschedule(getApplication(), settings)
        viewModelScope.launch {
            core.recordPhoneToolOutcome(
                "daily_briefing",
                if (settings.enabled) "scheduled daily at %02d:%02d".format(settings.hour, settings.minute) else "turned off"
            )
        }
    }

    fun previewBriefing() {
        viewModelScope.launch {
            val text = briefingService.build(briefingSettings).first
            lastBriefing = text
            reply = text
        }
    }

    fun updateSpeakReplies(enabled: Boolean) = updateSpeechSettings(speechSettings.copy(autoSpeak = enabled))

    fun updateSpeechSettings(settings: SpeechSettings) {
        speechSettings = settings
        voicePrefs.edit()
            .putBoolean("speakReplies", settings.autoSpeak)
            .putString("provider", settings.provider.name)
            .putString("voiceId", settings.voiceId)
            .putFloat("rate", settings.rate)
            .putBoolean("cloudForPrivate", settings.cloudForPrivate)
            .apply()
        // Turning speech off never leaves an answer playing or queued.
        if (!settings.autoSpeak) speech.stop()
    }

    /** Removes a key an older version saved on the phone (a key set in local.properties is part of the build). */
    fun removeSavedElevenLabsKey() {
        secrets.put(SecretStore.ELEVENLABS_API_KEY, null)
        elevenLabsSource = credentials.elevenLabsSource()
        voiceChecks = emptyMap()
    }

    /** Confirms the curated voices on the user's account (metadata only; no speech credits). */
    fun checkVoices() {
        if (!elevenLabsConfigured || voiceCheckRunning) return
        voiceCheckRunning = true
        viewModelScope.launch {
            voiceChecks = ElevenLabsVoiceDirectory(credentials::elevenLabsKey).check()
            voiceChecks.forEach { (id, check) -> Log.d("JarvisSpeech", "voice check ${ElevenLabsVoices.resolve(id).name}: ${check::class.simpleName}") }
            voiceCheckRunning = false
        }
    }

    fun stopSpeaking() {
        speech.cancelSpeakNext()
        speech.stop()
    }

    /** Plays a fixed sample with the chosen voice; it is shown as a reply so the user sees what was sent. */
    fun testVoice() {
        val sample = "This is JARVIS. Your next meeting is at 10 AM."
        val reply = AssistantReply(Long.MAX_VALUE - System.nanoTime() % 1_000_000, sample)
        speech.replay(reply)
    }

    val googleRoutesConfigured: Boolean get() = credentials.googleRoutesKey() != null
    val mapboxConfigured: Boolean get() = credentials.mapboxToken() != null

    /** "Read it again": the latest completed answer only, never history. */
    fun replayLastAnswer() {
        lastAnswer?.let(speech::replay)
    }

    fun onListeningChanged(active: Boolean) {
        listening = active
        if (active) captureStarting = false else voiceLevel = 0f
        MicArbiter.setRecognizing(active)
        // The microphone and JARVIS's own voice never run together.
        if (active) speech.stop()
    }

    fun onVoiceTranscript(text: String) {
        followUpListening = false
        liveTranscript = null
        confirmRetries = 0
        // Each request heard in the hands-free window opens a new one; the window still ends on silence.
        if (keepListeningCapture) followUpsInConversation = 0
        keepListeningCapture = false
        // The transcript appears as the user's own message; it is never spoken back.
        progress("Heard: “$text”")
        startVoiceTurn()
        submit(text, fromVoice = true)
    }

    fun onVoiceProblem(message: String) {
        val keep = keepListeningCapture
        keepListeningCapture = false
        // The hands-free window ending in silence is a normal end: say "stopped listening", keep the last answer.
        if (keep && (message == VoicePolicy.WINDOW_ENDED || message == VoicePolicy.errorMessage(7))) {
            voiceNotice = com.jarvis.core.VoiceNotice(com.jarvis.core.VoiceIndicator.ENDED, System.currentTimeMillis(), VoicePolicy.WINDOW_ENDED)
            followUpListening = false
            listening = false
            captureStarting = false
            voiceLevel = 0f
            MicArbiter.setRecognizing(false)
            voiceConversation = false
            return
        }
        liveTranscript = null
        // A yes/no is waiting: a failed capture is not an answer. The proposal stays (same deadline) and, in a voice
        // conversation, JARVIS listens again — at most twice per question, so the microphone is never left on.
        if (awaitingYesNo && !message.startsWith("Microphone permission") && message != VoicePolicy.UNAVAILABLE) {
            followUpListening = false
            listening = false
            captureStarting = false
            voiceLevel = 0f
            MicArbiter.setRecognizing(false)
            val again = voiceConversation && confirmRetries < MAX_CONFIRM_RETRIES
            if (again) confirmRetries++ else voiceConversation = false
            voiceNotice = com.jarvis.core.VoiceNotice(com.jarvis.core.VoiceIndicator.ERROR, System.currentTimeMillis(), message)
            answer(VoicePolicy.confirmRetry(message, again), expectsReply = again)
            return
        }
        val indicator = if (message.startsWith("Microphone permission")) com.jarvis.core.VoiceIndicator.NO_MIC else com.jarvis.core.VoiceIndicator.ERROR
        if (listening || captureStarting || voiceConversation || indicator == com.jarvis.core.VoiceIndicator.NO_MIC) {
            voiceNotice = com.jarvis.core.VoiceNotice(indicator, System.currentTimeMillis(), message)
        }
        followUpListening = false
        listening = false
        captureStarting = false
        voiceLevel = 0f
        MicArbiter.setRecognizing(false)
        val wasFollowUp = voiceConversation && followUpsInConversation > 0
        voiceConversation = false
        reply = if (wasFollowUp && message == VoicePolicy.errorMessage(7)) {
            "I didn't hear an answer, so I stopped listening. The request is still open: type the answer or tap the mic."
        } else message
    }

    fun onVoiceLevel(rmsDb: Float) {
        // SpeechRecognizer reports roughly -2 to 10 dB; map it to 0-1 for the waveform.
        voiceLevel = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
    }

    // ---- Voice conversations: speak the answer, and listen again after a question ----

    /** True while the user is talking to JARVIS by voice (push-to-talk, "Hey JARVIS", or a follow-up answer). */
    var voiceConversation by mutableStateOf(false)
        private set
    /** 0-1 microphone level while JARVIS listens, for the waveform. */
    var voiceLevel by mutableStateOf(0f)
        private set
    /** Between the start of a capture and the recogniser being ready: not "Listening" yet. */
    var captureStarting by mutableStateOf(false)
        private set
    /** True from the moment JARVIS starts listening for the answer to its own question until that capture ends. */
    var followUpListening by mutableStateOf(false)
        private set
    /** "Stopped" or "Didn't catch that", shown briefly when a voice interaction ends without a request. */
    var voiceNotice by mutableStateOf<com.jarvis.core.VoiceNotice?>(null)
        private set
    /** Incremented when the visible screen should start listening for a follow-up answer. */
    var autoListenRequests by mutableStateOf(0)
        private set
    private var awaitingReplyId: Long? = null
    private var followUpsInConversation = 0

    private fun startVoiceTurn() {
        voiceConversation = true
        speech.speakNextAnswerAloud()
    }

    /**
     * Called when an answer finished playing: listens for the reply if that answer asked a question, or — with
     * "Keep listening after answers" on and JARVIS on screen — for one more request within the chosen window.
     */
    private fun onAnswerSpoken(replyId: Long) {
        when {
            com.jarvis.core.TurnTaking.followUpDue(awaitingReplyId, voiceConversation, speech.isBusy) -> startFollowUpListening()
            com.jarvis.core.TurnTaking.keepListeningDue(handsFree.enabled, voiceConversation, speech.isBusy, lastAnswer?.id == replyId,
                com.jarvis.core.AppVisibility.visible) -> startFollowUpListening(keepListening = true)
        }
    }

    /** Settings → Voice: listen again after each spoken answer for [HandsFree.windowSeconds]; off by default. */
    var handsFree by mutableStateOf(com.jarvis.core.HandsFree(voicePrefs.getBoolean("handsFree", false), voicePrefs.getInt("handsFreeWindow", 8)))
        private set

    fun updateHandsFree(settings: com.jarvis.core.HandsFree) {
        handsFree = settings.copy(windowSeconds = settings.windowSeconds.coerceIn(com.jarvis.core.HandsFree.MIN_SECONDS, com.jarvis.core.HandsFree.MAX_SECONDS))
        voicePrefs.edit().putBoolean("handsFree", handsFree.enabled).putInt("handsFreeWindow", handsFree.windowSeconds).apply()
        viewModelScope.launch { core.recordPhoneToolOutcome("voice", if (handsFree.enabled) "keep listening after answers on (${handsFree.windowSeconds} s)" else "keep listening after answers off") }
    }

    /** Automatic re-listens after failed captures while a yes/no waits (reset when something is heard). */
    private var confirmRetries = 0
    private val MAX_CONFIRM_RETRIES = 2

    /** The words heard so far in the current capture, shown on the voice panel. */
    var liveTranscript by mutableStateOf<String?>(null)
        private set

    fun onPartialTranscript(text: String?) {
        if (listening) liveTranscript = text
    }

    /** The current capture is the hands-free window (not an answer to a question). */
    var keepListeningCapture by mutableStateOf(false)
        private set

    /** Something is waiting for yes or no (shown as "Say yes or no" while listening). */
    val awaitingYesNo: Boolean get() = core.awaitingConfirmation || pendingPlanApproval != null

    /** How long the next capture waits for speech to begin; only the hands-free window has a limit of its own. */
    fun captureWindowMs(): Long? = if (keepListeningCapture) handsFree.windowSeconds * 1000L else null

    /**
     * After a question is read aloud, listen once for the answer: on screen with the normal recogniser, in the
     * background through the wake-word service (whose microphone foreground service may record while JARVIS is not
     * visible), and otherwise by asking the user to tap a notification. Bounded: one recogniser session each time and
     * at most FollowUpPolicy.MAX_FOLLOW_UPS questions in a row, so the microphone is never left on.
     */
    private fun startFollowUpListening(keepListening: Boolean = false) {
        awaitingReplyId = null
        keepListeningCapture = keepListening
        val route = com.jarvis.core.FollowUpPolicy.route(
            voiceConversation = voiceConversation,
            followUpsSoFar = followUpsInConversation,
            micGranted = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            visible = com.jarvis.core.AppVisibility.visible,
            wakeServiceCanListen = WakeWordEvents.followUpCapture != null
        )
        Log.d("JarvisVoice", "follow-up route: $route")
        if (route != com.jarvis.core.FollowUpRoute.NONE && route != com.jarvis.core.FollowUpRoute.NO_PERMISSION) followUpsInConversation++
        when (route) {
            com.jarvis.core.FollowUpRoute.NONE, com.jarvis.core.FollowUpRoute.NO_PERMISSION -> { voiceConversation = false; keepListeningCapture = false }
            com.jarvis.core.FollowUpRoute.ON_SCREEN -> { followUpListening = true; autoListenRequests++ }
            com.jarvis.core.FollowUpRoute.WAKE_SERVICE -> if (WakeWordEvents.followUpCapture?.invoke() == true) {
                followUpListening = true
            } else {
                voiceConversation = false
                com.jarvis.core.FollowUpNotice.post(getApplication())
            }
            com.jarvis.core.FollowUpRoute.NOTIFICATION -> {
                voiceConversation = false
                com.jarvis.core.FollowUpNotice.post(getApplication())
            }
        }
    }

    /**
     * What the current request is waiting for on screen, if anything. These steps genuinely need the user: Android
     * shows permission prompts only in a visible app, and JARVIS's own safety policy requires an on-screen
     * confirmation before a call and a human tap to send a message or email.
     */
    private fun screenNeeded(): Pair<String, String>? {
        callConfirmationContact?.let { return "Confirm the call" to "Calls always need your confirmation on screen, so I can't call ${it.displayName} hands-free." }
        communicationDraft?.let { return "Review your message" to "Your message to ${communicationRecipient ?: "them"} is ready; you send it yourself." }
        emailDraft?.let { return "Review your email draft" to "Your email draft is ready to review; you send it from your mail app." }
        if (confirmCancel) return "Confirm the cancellation" to "Cancelling a plan needs your confirmation on screen."
        summaryConfirmation?.let { return "Confirm the summary" to "Summarizing messages needs your confirmation on screen." }
        phonePermissionRequest?.let { return "Allow a permission" to "Android asks for this permission only while JARVIS is on screen." }
        gmailConsentIntent?.let { return "Allow Gmail access" to "Google shows the Gmail consent screen only while JARVIS is open." }
        // A list can be answered by voice when JARVIS can listen in the background; otherwise it needs the screen.
        if (choice != null && !(voiceConversation && WakeWordEvents.followUpCapture != null)) return "Pick one" to "I need you to pick one on screen."
        return null
    }

    init {
        viewModelScope.launch {
            // Wait until the constructor has finished: screenNeeded() reads state declared further down this class.
            kotlinx.coroutines.yield()
            androidx.compose.runtime.snapshotFlow { screenNeeded() }
                .distinctUntilChanged { old, new -> old?.first == new?.first }
                .collect { needed ->
                    if (needed != null && !com.jarvis.core.AppVisibility.visible) {
                        Log.d("JarvisVoice", "screen needed in background: ${needed.first}")
                        answer(com.jarvis.core.ForegroundNeeded.post(getApplication(), needed.first, needed.second))
                    }
                }
        }
    }

    /** Stop on the listening panel: end the whole voice interaction now. */
    fun cancelVoiceInteraction() {
        voiceNotice = com.jarvis.core.VoiceNotice(com.jarvis.core.VoiceIndicator.CANCELLED, System.currentTimeMillis())
        followUpListening = false
        keepListeningCapture = false
        voiceConversation = false
        awaitingReplyId = null
        captureStarting = false
        voiceLevel = 0f
        speech.cancelSpeakNext()
        speech.stop()
        WakeWordEvents.cancelCapture?.invoke()
        MicArbiter.setRecognizing(false)
    }

    // ---- M4: Gmail (read-only) and email compose handoff ----

    private val gmailAuthorizer = GmailAuthorizer(application)
    private val gmail = GmailClient(
        accessToken = { gmailAuthorizer.cachedToken() },
        invalidateToken = { gmailAuthorizer.forget(it) }
    )
    /** Google's consent screen to launch; set only when Google says consent is needed. */
    var gmailConsentIntent by mutableStateOf<android.app.PendingIntent?>(null)
        private set
    var gmailConnected by mutableStateOf(false)
        private set
    var emailSummaryCount by mutableStateOf<Int?>(null)
        private set
    var emailDraft by mutableStateOf<EmailDraftState?>(null)
        private set
    private var pendingEmailStep: PlanStep? = null
    private var pendingEmails: List<EmailSummary> = emptyList()

    private suspend fun handleEmailRequest(payload: String) {
        val step = PhoneToolRequestCodec.decode(payload)
        if (step == null) {
            reply = "I couldn't safely process that email request."
            return
        }
        if (step.toolName == "compose_email") startCompose(step) else runGmailTool(step, allowReauthorize = true)
    }

    /** Returns true when a token is available now; otherwise shows consent or an error and returns false. */
    private suspend fun ensureGmailAuthorized(step: PlanStep?): Boolean {
        if (gmailAuthorizer.cachedToken() != null) return true
        return when (val outcome = gmailAuthorizer.authorize()) {
            is GmailAuthOutcome.Granted -> { gmailConnected = true; true }
            is GmailAuthOutcome.NeedsConsent -> {
                pendingEmailStep = step
                gmailConsentIntent = outcome.pendingIntent
                progress("Google will ask you to allow read-only Gmail access. JARVIS never sends email.")
                false
            }
            is GmailAuthOutcome.Failed -> {
                reply = outcome.message
                core.recordPhoneToolOutcome(step?.toolName ?: "email_unread_count", "authorization failed")
                false
            }
        }
    }

    fun connectGmail() {
        viewModelScope.launch {
            if (ensureGmailAuthorized(null)) reply = "Gmail connected with read-only access."
        }
    }

    fun disconnectGmail() {
        viewModelScope.launch {
            val revoked = gmailAuthorizer.disconnect()
            gmailConnected = false
            reply = if (revoked) "Gmail disconnected and Google access revoked."
            else "Gmail disconnected here. Google could not confirm the revoke; you can remove access at myaccount.google.com/permissions."
            core.recordPhoneToolOutcome("email_unread_count", if (revoked) "gmail access revoked" else "gmail disconnected locally")
        }
    }

    fun onGmailConsentResult(data: android.content.Intent?) {
        gmailConsentIntent = null
        val step = pendingEmailStep
        pendingEmailStep = null
        when (val outcome = gmailAuthorizer.handleConsentResult(data)) {
            is GmailAuthOutcome.Granted -> {
                gmailConnected = true
                if (step != null) viewModelScope.launch { runGmailTool(step, allowReauthorize = false) }
                else reply = "Gmail connected with read-only access."
            }
            is GmailAuthOutcome.NeedsConsent -> reply = "Gmail access still needs your approval."
            is GmailAuthOutcome.Failed -> {
                reply = outcome.message
                viewModelScope.launch { core.recordPhoneToolOutcome(step?.toolName ?: "email_unread_count", "consent not granted") }
            }
        }
    }

    private suspend fun runGmailTool(step: PlanStep, allowReauthorize: Boolean) {
        if (!ensureGmailAuthorized(step)) return
        val startOfDay = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()
        val failure: GmailResult.Failure? = when (step.toolName) {
            "email_unread_count" -> when (val result = gmail.unreadCount()) {
                is GmailResult.Success -> { reply = EmailReplies.unread(result.value); null }
                is GmailResult.Failure -> result
            }
            "email_today" -> when (val result = gmail.todaysEmails(startOfDay)) {
                is GmailResult.Success -> { answer(EmailReplies.today(result.value), privateContent = true); null }
                is GmailResult.Failure -> result
            }
            else -> when (val result = gmail.todaysEmails(startOfDay)) {
                is GmailResult.Success -> {
                    if (result.value.isEmpty()) {
                        reply = EmailReplies.today(result.value)
                    } else {
                        pendingEmails = result.value
                        emailSummaryCount = minOf(result.value.size, JarvisCore.SUMMARY_LIMIT)
                        progress("Waiting for your confirmation before any email text is sent.")
                    }
                    null
                }
                is GmailResult.Failure -> result
            }
        }
        if (failure == null) {
            core.recordPhoneToolOutcome(step.toolName, "answered from Gmail (read-only)")
            return
        }
        if (failure.error == GmailError.NOT_AUTHORIZED && allowReauthorize) {
            gmailConnected = false
            runGmailTool(step, allowReauthorize = false)
            return
        }
        reply = failure.detail
        core.recordPhoneToolOutcome(step.toolName, "gmail ${failure.error.name.lowercase()}")
    }

    fun confirmEmailSummary() {
        if (emailSummaryCount == null) return
        emailSummaryCount = null
        val emails = pendingEmails
        pendingEmails = emptyList()
        progress("Summarizing…")
        viewModelScope.launch {
            answer(core.summarizeEmails(emails), privateContent = true)
            engineLabel = core.lastEngineLabel
        }
    }

    fun dismissEmailSummary() {
        if (emailSummaryCount == null) return
        emailSummaryCount = null
        pendingEmails = emptyList()
        reply = "Summary cancelled. No email text was sent."
    }

    fun emailSummaryDisclosure(): String =
        cloudProviderDisclosure().replace("message text", "email subjects and snippets")

    /**
     * A model asked to compose an email. Its arguments are not trusted for facts: the request goes through the same
     * question-by-question dialogue, starting from what the user actually said, and a recipient counts only if the user
     * named them.
     */
    private fun startCompose(step: PlanStep) {
        val said = lastUtterance.orEmpty()
        val named = step.parameters["recipient_name"]?.takeIf { name ->
            name.isNotBlank() && ContactNames.normalize(said).contains(ContactNames.normalize(name))
        }
        val request = (EmailDialogue.start(said) ?: EmailRequest(EmailRequest.Kind.GENERAL)).let { r ->
            if (r.recipient == null && named != null) r.copy(recipient = named) else r
        }
        continueEmail(EmailDialogue.next(request))
    }

    // ---- Multi-step tasks: email drafting and clock commands ----

    private var lastUtterance: String? = null
    private data class PendingEmail(val request: EmailRequest, val asked: EmailSlot)
    private var pendingEmail: PendingEmail? = null
    private data class PendingReminder(val draft: com.jarvis.core.ReminderDraft, val asked: com.jarvis.core.ReminderSlot)
    private var pendingReminder: PendingReminder? = null
    private data class PendingGoal(val draft: com.jarvis.core.GoalDraft, val asked: com.jarvis.core.GoalSlot)
    private var pendingGoal: PendingGoal? = null
    /** Phase 6: a multi-step plan waiting for approval, or for a missing detail in one of its steps. */
    private var pendingPlanApproval: String? = null
    private data class PendingPlanInfo(val clauses: List<com.jarvis.core.PlanParser.Clause>, val original: String)
    private var pendingPlanInfo: PendingPlanInfo? = null
    private val planExecutor by lazy { com.jarvis.core.PlanExecutors.create(getApplication()) }
    val planExecutions = db.planExecutionDao().observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val planSteps = db.planExecutionDao().observeSteps().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** Phase 5 durable reminders and goals (Room + exact alarms). */
    private val taskEngine by lazy { com.jarvis.core.TaskEngines.create(getApplication()) }
    val agentTasks = db.agentTaskDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val goals = db.goalDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Handles the turn if it belongs to a multi-step task or a local clock command; false otherwise. */
    private fun handlePendingDialogue(text: String): Boolean {
        val now = java.time.ZonedDateTime.now()
        // A meeting or memory deletion is waiting for yes/no in JarvisCore: the answer goes there, unless it is clearly
        // a new request, which makes that proposal stale.
        if (core.awaitingConfirmation) {
            if (isNewCommand(text) || com.jarvis.core.ContextualControl.parse(text) != null) core.dropConfirmation() else return false
        }
        pendingPlanApproval?.let { id ->
            if (isNewCommand(text)) pendingPlanApproval = null else {
                userSaid(text)
                answerPlanApproval(id, text)
                return true
            }
        }
        pendingPlanInfo?.let { pending ->
            if (isNewCommand(text)) pendingPlanInfo = null else {
                userSaid(text)
                pendingPlanInfo = null
                if (Regex("""^\s*(cancel|stop|never ?mind|forget it)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    answer("Okay, I dropped that plan.")
                } else {
                    val filled = pending.clauses.map { clause ->
                        if (clause is com.jarvis.core.PlanParser.Clause.Missing && clause === pending.clauses.first { it is com.jarvis.core.PlanParser.Clause.Missing })
                            com.jarvis.core.PlanParser.clause(clause.text + " " + text, now) ?: clause else clause
                    }
                    continuePlan(filled, pending.original)
                }
                return true
            }
        }
        pendingReminder?.let { pending ->
            if (isNewCommand(text)) pendingReminder = null else {
                userSaid(text)
                continueReminder(com.jarvis.core.ReminderDialogue.answer(pending.draft, pending.asked, text, now))
                return true
            }
        }
        pendingGoal?.let { pending ->
            if (isNewCommand(text)) pendingGoal = null else {
                userSaid(text)
                continueGoal(com.jarvis.core.GoalDialogue.answer(pending.draft, pending.asked, text, now))
                return true
            }
        }
        pendingEmail?.let { pending ->
            // A clearly different request abandons the email (e.g. "set a timer for 5 minutes").
            if (isNewCommand(text)) {
                pendingEmail = null
            } else {
                userSaid(text)
                continueEmail(EmailDialogue.answer(pending.request, pending.asked, text))
                return true
            }
        }
        EmailDialogue.start(text)?.let { request ->
            userSaid(text)
            continueEmail(EmailDialogue.next(request))
            return true
        }
        // A bare "yes" / "haan" with nothing waiting is not permission for anything.
        if (com.jarvis.core.ApprovalAnswer.isBare(text) && pendingEmail == null && pendingReminder == null && pendingGoal == null && choice == null &&
            !confirmCancel && callConfirmationContact == null && summaryConfirmation == null
        ) {
            userSaid(text)
            answer(com.jarvis.core.LocalReplies.nothingPending())
            return true
        }
        // A request with several supported actions becomes one plan (checked before single reminders).
        com.jarvis.core.PlanParser.parse(text, now)?.let { parsed ->
            userSaid(text)
            continuePlan(parsed.clauses, text)
            return true
        }
        com.jarvis.core.ReminderCommands.parse(text)?.let { command ->
            userSaid(text)
            handleReminderCommand(command, text)
            return true
        }
        com.jarvis.core.GoalDialogue.begin(text, java.time.ZonedDateTime.now())?.let { step ->
            userSaid(text)
            continueGoal(step)
            return true
        }
        ClockCommandParser.parse(text)?.let { command ->
            userSaid(text)
            thinking = true
            viewModelScope.launch { handleClockCommand(command) }
            return true
        }
        com.jarvis.core.ContextualControl.parse(text)?.let { kind ->
            val speaking = speech.isBusy
            userSaid(text)
            viewModelScope.launch { handleContextualControl(kind, speaking) }
            return true
        }
        return false
    }

    /** "Rok do" / "dobara shuru karo": acts on what is running now, or says why not — never starts something new. */
    private suspend fun handleContextualControl(kind: com.jarvis.core.ContextualControl.Kind, speaking: Boolean) {
        val goals = db.goalDao().all()
        val target = com.jarvis.core.ContextualControl.resolve(kind, speaking, com.jarvis.core.JarvisTimerEngine(getApplication()).active(),
            goals.count { it.status == "active" }, goals.count { it.status == "paused" })
        when (target) {
            com.jarvis.core.ContextualControl.Target.DismissRinging -> handleClockCommand(ClockCommand.DismissRingingTimer)
            com.jarvis.core.ContextualControl.Target.StopSpeech -> answer("Okay, stopped.")
            com.jarvis.core.ContextualControl.Target.CancelTimer -> handleClockCommand(ClockCommand.CancelTimer(latest = false))
            com.jarvis.core.ContextualControl.Target.PauseGoal -> changeGoal("", pause = true)
            com.jarvis.core.ContextualControl.Target.ResumeGoal -> changeGoal("", pause = false)
            is com.jarvis.core.ContextualControl.Target.Say -> answer(target.message)
        }
    }

    /**
     * While JARVIS waits for an answer, almost anything is that answer ("skip" is a valid recipient answer, not a media
     * command). Only an unmistakable new task interrupts; "cancel" ends the dialogue instead.
     */
    private fun isNewCommand(text: String): Boolean =
        ClockCommandParser.parse(text) != null || AlarmRequestParser.isAlarmIntent(text) || EmailDialogue.start(text) != null ||
            com.jarvis.core.ReminderCommands.parse(text) != null || com.jarvis.core.GoalDialogue.begin(text, java.time.ZonedDateTime.now()) != null ||
            com.jarvis.core.PlanParser.parse(text, java.time.ZonedDateTime.now()) != null

    /** The user's words as typed or heard, before Hindi normalisation (what the chat shows). */
    private var shownUtterance: String? = null

    private fun userSaid(text: String) {
        speech.stop()
        appendMessage(ChatMessage(nextUserMessageId--, fromUser = true, text = shownUtterance ?: text.trim()))
    }

    // ---- Phase 6: plan, approve, execute, verify ----

    private fun continuePlan(clauses: List<com.jarvis.core.PlanParser.Clause>, original: String) {
        val missing = clauses.filterIsInstance<com.jarvis.core.PlanParser.Clause.Missing>().firstOrNull()
        if (missing != null) {
            pendingPlanInfo = PendingPlanInfo(clauses, original)
            answer("Before I plan this: ${missing.question}", expectsReply = true)
            return
        }
        val steps = clauses.filterIsInstance<com.jarvis.core.PlanParser.Clause.Step>().map { it.step }
        viewModelScope.launch {
            when (val proposed = planExecutor.propose(original, steps)) {
                is com.jarvis.core.ProposeResult.Rejected -> answer("I can't plan that: ${proposed.reason}.")
                is com.jarvis.core.ProposeResult.Created -> {
                    if (proposed.execution.status == com.jarvis.core.ExecStates.WAITING_APPROVAL) {
                        pendingPlanApproval = proposed.execution.id
                        answer(proposed.text, expectsReply = true)
                    } else {
                        answer(planExecutor.run(proposed.execution.id).text)
                    }
                }
            }
        }
    }

    private fun answerPlanApproval(id: String, text: String) {
        pendingPlanApproval = null
        viewModelScope.launch {
            // Phase 9A: a time correction changes the waiting plan (then asks again); it never adds a second action.
            val steps = db.planExecutionDao().steps(id).map { com.jarvis.core.ProposedStep(it.tool, com.jarvis.core.PlanExecutor.args(it)) }
            val corrected = com.jarvis.core.PlanCorrection.apply(steps, text, java.time.ZonedDateTime.now())
            if (corrected != null && corrected != steps) {
                planExecutor.reject(id)
                val objective = db.planExecutionDao().execution(id)?.objective ?: "corrected plan"
                when (val proposed = planExecutor.propose(objective, corrected)) {
                    is com.jarvis.core.ProposeResult.Rejected -> answer("I can't use that correction: ${proposed.reason}.")
                    is com.jarvis.core.ProposeResult.Created -> {
                        pendingPlanApproval = proposed.execution.id
                        answer("Changed. " + proposed.text, expectsReply = true)
                    }
                }
                return@launch
            }
            if (steps.count { it.tool == "reminder.schedule" } == 1 &&
                com.jarvis.core.ReminderTime.parse(text, java.time.ZonedDateTime.now()) is com.jarvis.core.WhenParse.NeedsAmPm
            ) {
                // "nahi, 8 baje": a correction without AM/PM; keep the plan waiting and ask, never guess.
                pendingPlanApproval = id
                answer("Is that in the morning or the evening? Say, for example, \"make it 8 PM\", or yes / no for the plan as shown.", expectsReply = true)
                return@launch
            }
            when (com.jarvis.core.ApprovalAnswer.parse(text)) {
                com.jarvis.core.ApprovalAnswer.Kind.YES -> {
                    thinking = true
                    if (!planExecutor.approve(id)) answer("That plan changed or is no longer waiting, so I didn't run it.")
                    else answer(planExecutor.run(id).text)
                }
                com.jarvis.core.ApprovalAnswer.Kind.NO -> { planExecutor.reject(id); answer("Okay, I didn't run any of it.") }
                com.jarvis.core.ApprovalAnswer.Kind.UNCLEAR -> {
                    pendingPlanApproval = id
                    answer("Say yes to run exactly those steps, no, or a new time.", expectsReply = true)
                }
            }
        }
    }

    fun cancelPlanExecution(id: String) = viewModelScope.launch {
        if (pendingPlanApproval == id) pendingPlanApproval = null
        if (planExecutor.cancel(id)) answer(planExecutor.report(id).text)
    }

    // ---- Phase 5: reminders and goals ----

    private fun handleReminderCommand(command: com.jarvis.core.ReminderCommand, text: String) {
        when (command) {
            is com.jarvis.core.ReminderCommand.Create -> continueReminder(com.jarvis.core.ReminderDialogue.start(text, java.time.ZonedDateTime.now()))
            is com.jarvis.core.ReminderCommand.List -> viewModelScope.launch {
                answer(com.jarvis.core.TaskReplies.list(db.agentTaskDao().all(), db.goalDao().all(), java.time.ZonedDateTime.now(),
                    db.reminderDao().observeAll().first(), command.scope))
            }
            is com.jarvis.core.ReminderCommand.Cancel -> viewModelScope.launch {
                val active = db.agentTaskDao().all().filter { it.kind == com.jarvis.core.TaskKinds.REMINDER && it.status in com.jarvis.core.TaskStates.ACTIVE }
                val matches = com.jarvis.core.TaskReplies.match(active, command.query, command.latest)
                when {
                    matches.isEmpty() -> answer(if (active.isEmpty()) "You have no pending reminders." else "I couldn't find that reminder. Say \"list my reminders\" to see them.")
                    matches.size == 1 -> cancelReminder(matches.single())
                    else -> offerChoice(
                        title = "Cancel which reminder?",
                        question = "Which reminder should I cancel? " + matches.mapIndexed { i, t -> "${i + 1}: ${com.jarvis.core.TaskReplies.describe(t, java.time.ZonedDateTime.now())}" }.joinToString("; ") + ".",
                        labels = matches.map { com.jarvis.core.TaskReplies.describe(it, java.time.ZonedDateTime.now()) },
                        onPick = { index -> cancelReminder(matches[index]) },
                        onCancel = { answer("Okay, I left your reminders as they are.") }
                    )
                }
            }
            is com.jarvis.core.ReminderCommand.PauseGoal -> changeGoal(command.query, pause = true)
            is com.jarvis.core.ReminderCommand.ResumeGoal -> changeGoal(command.query, pause = false)
        }
    }

    private suspend fun cancelReminder(task: com.jarvis.data.AgentTask) {
        val verified = taskEngine.cancel(task.id)
        core.recordPhoneToolOutcome("reminder.cancel", if (verified) "reminder cancelled; alarm removed" else "reminder cancelled; alarm removal not confirmed")
        answer(com.jarvis.core.LocalReplies.reminderCancelled(task.objective, verified))
    }

    private fun changeGoal(query: String, pause: Boolean) = viewModelScope.launch {
        val wanted = if (pause) "active" else "paused"
        val candidates = db.goalDao().all().filter { it.status == wanted }
        val tokens = query.lowercase().split(' ').filter { it.length > 2 }
        val matches = candidates.filter { goal -> tokens.isEmpty() || tokens.any { goal.title.lowercase().contains(it) } }.ifEmpty { candidates }
        when {
            matches.isEmpty() -> answer(if (pause) "You don't have an active plan to pause. Meeting plans can be cancelled, not paused." else "You don't have a paused plan to resume.")
            matches.size > 1 -> answer("You have ${matches.size} plans: " + matches.joinToString("; ") { it.title } + ". Say which one, for example \"${if (pause) "pause" else "resume"} my ${matches.first().title.removePrefix("Prepare for ")} plan\".")
            else -> {
                val goal = matches.single()
                val changed = if (pause) taskEngine.pauseGoal(goal.id) else taskEngine.resumeGoal(goal.id)
                val skipped = if (pause) 0 else db.agentTaskDao().forGoal(goal.id).count { it.status == com.jarvis.core.TaskStates.MISSED }
                core.recordPhoneToolOutcome("goal", "${if (pause) "paused" else "resumed"} a plan: $changed reminder(s)")
                answer(if (pause) "Paused \"${goal.title}\": $changed reminder(s) won't ring until you resume it."
                else "Resumed \"${goal.title}\": $changed reminder(s) scheduled again" + (if (skipped > 0) "; $skipped whose time passed are skipped." else "."))
            }
        }
    }

    private fun continueReminder(step: com.jarvis.core.ReminderStep) {
        when (step) {
            is com.jarvis.core.ReminderStep.Ask -> {
                pendingReminder = PendingReminder(step.draft, step.slot)
                answer(step.question, expectsReply = true)
            }
            com.jarvis.core.ReminderStep.Cancelled -> { pendingReminder = null; answer("Okay, no reminder.") }
            is com.jarvis.core.ReminderStep.Ready -> {
                pendingReminder = null
                viewModelScope.launch {
                    val now = java.time.ZonedDateTime.now()
                    val result = taskEngine.createReminder(step.title, step.at.toInstant().toEpochMilli(), step.at.zone.id, step.origin)
                    answer(com.jarvis.core.TaskReplies.created(result, step.title, step.at, now))
                    core.recordPhoneToolOutcome("reminder.create", if (result is com.jarvis.core.CreateResult.Scheduled) "reminder stored and alarm set" else "reminder not scheduled")
                }
            }
        }
    }

    private fun continueGoal(step: com.jarvis.core.GoalStepResult) {
        when (step) {
            is com.jarvis.core.GoalStepResult.Ask -> {
                pendingGoal = PendingGoal(step.draft, step.slot)
                answer(step.question, expectsReply = true)
            }
            is com.jarvis.core.GoalStepResult.Explain -> { pendingGoal = null; answer(step.message) }
            com.jarvis.core.GoalStepResult.Cancelled -> { pendingGoal = null; answer("Okay, I didn't create a plan.") }
            is com.jarvis.core.GoalStepResult.Ready -> {
                pendingGoal = null
                viewModelScope.launch {
                    val (goal, results) = taskEngine.createGoal(step.title, step.draft.due?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
                        step.sessions, java.time.ZoneId.systemDefault().id, step.draft.origin)
                    val ok = results.count { it is com.jarvis.core.CreateResult.Scheduled }
                    core.recordPhoneToolOutcome("goal", "plan created with $ok of ${results.size} reminder(s) scheduled")
                    answer("Created \"${goal.title}\": $ok of ${results.size} study reminder(s) scheduled. " +
                        "Tap Done on each reminder when you finish; you can pause, resume, or cancel it in Plans or by saying \"pause my plan\".")
                }
            }
        }
    }

    private fun continueEmail(step: EmailStep) {
        when (step) {
            is EmailStep.Ask -> {
                pendingEmail = PendingEmail(step.request, step.slot)
                answer(step.question, expectsReply = true)
            }
            EmailStep.Cancelled -> {
                pendingEmail = null
                answer("Okay, I won't write that email.")
            }
            is EmailStep.Ready -> {
                pendingEmail = null
                thinking = true
                viewModelScope.launch { draftEmail(step.request) }
            }
        }
    }

    /** Resolves who the email is for (only someone the user named), then writes the complete draft. */
    private suspend fun draftEmail(request: EmailRequest) {
        val recipient = request.recipient.orEmpty().trim()
        val contactsGranted = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val greeting = recipient.takeUnless { it.isEmpty() || "@" in it || EmailDialogue.isRole(it) }
        val given = request.address
        when {
            // The address was asked for and confirmed (or skipped) earlier in this dialogue.
            given != null -> finishEmailDraft(
                request, greeting, recipient.ifEmpty { given }, given,
                if (given.isEmpty()) "The To field is empty for you to fill in." else null
            )
            recipient.isEmpty() -> finishEmailDraft(request, greetingName = null, displayName = "", address = "", note = "The To field is empty for you to fill in.")
            "@" in recipient -> continueEmail(EmailDialogue.answer(request, EmailSlot.ADDRESS, recipient))
            EmailDialogue.isRole(recipient) -> continueEmail(
                EmailDialogue.askAddress(request, "I can't look up an address for your ${recipient.removePrefix("my ")}.")
            )
            !contactsGranted -> continueEmail(
                EmailDialogue.askAddress(request, "Contacts access is off, so I can't look up $recipient's address.")
            )
            else -> when (val found = try {
                withContext(Dispatchers.IO) { resolveContactEmail(getApplication<Application>().contentResolver, recipient) }
            } catch (_: SecurityException) { EmailResolution.QueryFailed }) {
                is EmailResolution.Found -> finishEmailDraft(request, ContactNames.displayName(found.displayName), found.displayName, found.address, null)
                is EmailResolution.Ambiguous -> {
                    thinking = false
                    offerChoice(
                        title = "Email which contact?",
                        question = "Several contacts match $recipient. Which one should the email go to? " +
                            found.options.mapIndexed { i, o -> "${i + 1}: ${o.displayName} · ${o.address}" }.joinToString("; ") + ".",
                        labels = found.options.map { "${it.displayName} · ${it.address}" },
                        onPick = { index -> found.options[index].let { finishEmailDraft(request, ContactNames.displayName(it.displayName), it.displayName, it.address, null) } },
                        onCancel = { answer("Okay, I won't write that email.") }
                    )
                }
                EmailResolution.NotFound -> continueEmail(
                    EmailDialogue.askAddress(request, "I couldn't find an email address for $recipient in your contacts.")
                )
                EmailResolution.QueryFailed -> finishEmailDraft(
                    request, greetingName = recipient, displayName = recipient, address = "", note = ContactAccess.READ_FAILED
                )
            }
        }
    }

    private suspend fun finishEmailDraft(request: EmailRequest, greetingName: String?, displayName: String, address: String, note: String?) {
        thinking = true
        progress("Writing your email…")
        val (draft, source) = core.composeEmail(request, greetingName)
        engineLabel = core.lastEngineLabel
        emailDraft = EmailDraftState(displayName, address, draft.subject, draft.body, source)
        answer(
            "Here's your draft" + (displayName.takeIf { it.isNotBlank() }?.let { " to $it" } ?: "") + ". " +
                (note?.let { "$it " } ?: "") +
                "Review and edit it; I'll open your mail app and you tap Send there."
        )
    }

    private val clockActions by lazy { com.jarvis.core.ClockActions(getApplication()) }

    private suspend fun handleClockCommand(command: ClockCommand) {
        val now = System.currentTimeMillis()
        val timers = com.jarvis.core.JarvisTimerEngine(getApplication()).all()
        when (val plan = ClockPlanner.plan(command, com.jarvis.core.ClockHistoryStore(getApplication()).all(), now, timers)) {
            is ClockPlan.ChooseJarvisTimer -> {
                thinking = false
                offerChoice(
                    title = "Which timer?",
                    question = plan.question,
                    labels = plan.timers.map { it.describe(now) },
                    onPick = { index -> runClockPlan(ClockPlan.StopJarvisTimer(plan.timers[index]), command) },
                    onCancel = { answer("Okay, I left your timers running.") }
                )
            }
            is ClockPlan.Choose -> {
                thinking = false
                offerChoice(
                    title = "Which one?",
                    question = plan.question,
                    labels = plan.items.map { it.describe(now) },
                    onPick = { index -> runClockPlan(ClockPlanner.planFor(plan.items[index], now), command) },
                    onCancel = { answer("Okay, I left your alarms and timers as they are.") }
                )
            }
            else -> runClockPlan(plan, command)
        }
    }

    /** JARVIS's running and ringing timers, for the countdown card; refreshed whenever the timer records change. */
    val activeTimers = kotlinx.coroutines.flow.MutableStateFlow<List<com.jarvis.core.JarvisTimer>>(emptyList())
    private val timerPrefs = application.getSharedPreferences("jarvis_timers", android.content.Context.MODE_PRIVATE)
    private val timerListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshTimers() }

    init {
        timerPrefs.registerOnSharedPreferenceChangeListener(timerListener)
        refreshTimers()
    }

    private fun refreshTimers() {
        activeTimers.value = com.jarvis.core.TimerBook.active(com.jarvis.core.JarvisTimer.listFromJson(timerPrefs.getString("timers", null)))
    }

    /** The card's Cancel / Stop button: the same verified stop as "cancel the timer". */
    fun cancelTimer(id: Int) = viewModelScope.launch {
        val timer = com.jarvis.core.JarvisTimerEngine(getApplication()).all().firstOrNull { it.id == id && it.active } ?: return@launch refreshTimers()
        runClockPlan(ClockPlan.StopJarvisTimer(timer), ClockCommand.CancelTimer(latest = false))
    }

    private suspend fun runClockPlan(plan: ClockPlan, command: ClockCommand) {
        val outcome = when (plan) {
            is ClockPlan.DismissAlarmAt -> clockActions.dismissAlarmAt(plan.hour, plan.minute, plan.item)
            ClockPlan.DismissRingingAlarm -> clockActions.dismissRingingAlarm()
            is ClockPlan.DismissRingingTimer -> clockActions.dismissRingingTimer(plan.item)
            is ClockPlan.ShowTimers -> clockActions.showTimers(plan.item, plan.reason)
            is ClockPlan.Explain -> com.jarvis.core.ClockOutcome.Failed(plan.message)
            is ClockPlan.Choose, is ClockPlan.ChooseJarvisTimer -> com.jarvis.core.ClockOutcome.Failed("Please pick one of the listed items.")
            is ClockPlan.SnoozeAlarm -> clockActions.snoozeAlarm(plan.minutes)
            is ClockPlan.StopJarvisTimer -> clockActions.stopJarvisTimer(plan.timer)
        }
        val tool = if (command is ClockCommand.CancelTimer || command is ClockCommand.DismissRingingTimer) "set_timer" else "set_alarm"
        val action = if (plan is ClockPlan.SnoozeAlarm) "snooze" else "cancellation"
        core.recordPhoneToolOutcome(tool, when (outcome) {
            is com.jarvis.core.ClockOutcome.Done -> "$action confirmed"
            is com.jarvis.core.ClockOutcome.Unconfirmed -> "$action requested; not confirmed"
            is com.jarvis.core.ClockOutcome.Failed -> "$action failed"
        })
        answer(outcome.message)
    }

    // ---- Phase 7: research dashboard and export ----

    sealed interface EvidenceState {
        data object Loading : EvidenceState
        data class Loaded(val evidence: com.jarvis.core.Evidence) : EvidenceState
        data class Failed(val reason: String) : EvidenceState
    }
    var evidenceState by mutableStateOf<EvidenceState>(EvidenceState.Loading)
        private set
    var deviceRecords by mutableStateOf<com.jarvis.core.DeviceRecords?>(null)
        private set
    var showResearch by mutableStateOf(false)
        private set
    var exportMessage by mutableStateOf<String?>(null)
        private set

    fun openResearch() { showResearch = true; refreshResearch() }
    fun closeResearch() { showResearch = false }

    /** Reads the bundled evidence (works offline) and counts this phone's own records; no network, no model. */
    fun refreshResearch() = viewModelScope.launch {
        evidenceState = withContext(Dispatchers.IO) {
            try {
                val text = getApplication<Application>().assets.open("research/evidence.json").bufferedReader().use { it.readText() }
                EvidenceState.Loaded(com.jarvis.core.EvidenceParser.parse(text))
            } catch (e: com.jarvis.core.EvidenceFormatException) {
                EvidenceState.Failed("The evidence file could not be read: ${e.message}")
            } catch (e: java.io.IOException) {
                EvidenceState.Failed("The evidence file is missing from this build.")
            }
        }
        deviceRecords = withContext(Dispatchers.IO) {
            val traces = db.traceDao().decisionOutcomes().map { it.tool to it.outcome }
            com.jarvis.core.DeviceRecords.from(traces, db.planExecutionDao().runStatuses(), db.planExecutionDao().stepStatuses(), db.traceDao().earliestDecision())
        }
    }

    /** Builds the export text; only the allow-listed aggregate fields (ResearchExport). */
    fun exportText(json: Boolean): String? {
        val evidence = (evidenceState as? EvidenceState.Loaded)?.evidence ?: return null
        val now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        return if (json) com.jarvis.core.ResearchExport.json(evidence, deviceRecords, now, BuildConfig.VERSION_NAME)
        else com.jarvis.core.ResearchExport.csv(evidence, deviceRecords, now)
    }

    /** Writes to the file the user chose in the system "save as" screen; nothing is exported in the background. */
    fun writeExport(uri: android.net.Uri?, json: Boolean) = viewModelScope.launch {
        if (uri == null) { exportMessage = "Export cancelled."; return@launch }
        val text = exportText(json) ?: run { exportMessage = "Nothing to export: the evidence file isn't loaded."; return@launch }
        exportMessage = withContext(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: return@withContext "Couldn't open the chosen file."
                "Saved the ${if (json) "JSON" else "CSV"} export (${text.length} characters)."
            } catch (e: Exception) {
                "Export failed (${e.javaClass.simpleName}); nothing was written."
            }
        }
        core.recordPhoneToolOutcome("research.export", if (exportMessage!!.startsWith("Saved")) "research ${if (json) "JSON" else "CSV"} export saved to a file you chose" else "research export not saved")
    }

    /** Plans tab actions for reminders and goal steps. */
    fun cancelTask(id: String) = viewModelScope.launch { db.agentTaskDao().get(id)?.let { cancelReminder(it) } }
    fun markTaskDone(id: String) = viewModelScope.launch { taskEngine.markDone(id) }
    fun pauseGoal(id: String) = viewModelScope.launch { taskEngine.pauseGoal(id) }
    fun resumeGoal(id: String) = viewModelScope.launch { taskEngine.resumeGoal(id) }
    fun cancelGoal(id: String) = viewModelScope.launch { taskEngine.cancelGoal(id) }

    fun updateEmailSubject(subject: String) {
        emailDraft = emailDraft?.copy(subject = subject.take(EmailComposer.MAX_SUBJECT))
    }

    fun updateEmailBody(body: String) {
        emailDraft = emailDraft?.copy(body = body.take(EmailComposer.MAX_BODY))
    }

    fun dismissEmailDraft() {
        if (emailDraft == null) return
        emailDraft = null
        reply = "Email draft discarded. Nothing was sent."
    }

    fun handoffEmailDraft() {
        val draft = emailDraft ?: return
        val uri = EmailComposer.mailto(draft.address, EmailDraft(draft.subject, draft.body))
        if (uri == null) {
            reply = "The email address for ${draft.recipientName} is not a plain address, so JARVIS won't use it. Clear it and type it in your mail app."
            return
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(uri)).apply {
            putExtra(Intent.EXTRA_SUBJECT, draft.subject)
            putExtra(Intent.EXTRA_TEXT, draft.body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            getApplication<Application>().startActivity(intent)
            emailDraft = null
            reply = "Opened your mail app with the draft" + (draft.recipientName.takeIf { it.isNotBlank() }?.let { " to $it" } ?: "") +
                ". Review it and tap Send there; JARVIS did not send anything."
            viewModelScope.launch { core.recordPhoneToolOutcome("compose_email", "handed off to mail app; user sends") }
        } catch (_: ActivityNotFoundException) {
            reply = "No email app is installed to open the draft."
        }
    }

    private suspend fun resolveContact(name: String): ContactResolution =
        try {
            withContext(Dispatchers.IO) {
                ContactResolver(getApplication<Application>().contentResolver).resolve(name)
            }
        } catch (_: SecurityException) {
            ContactResolution.QueryFailed
        }

    private suspend fun cancellationImpactText(planId: String): String {
        val eventItem = db.planItemDao().forPlan(planId).firstOrNull { it.itemType == "calendar_event" }
        val event = eventItem?.refId?.let { db.eventDao().findById(it) }
        return if (event != null && event.androidCalendarEventId > 0L) {
            "This medium-risk action will remove the Calendar event and cancel the linked departure reminder."
        } else {
            val creationTrace = db.traceDao().observeAll().first()
                .firstOrNull { it.planId == planId && it.actionSummary.contains("saved locally only") }
            val localOnlyReason = when {
                creationTrace?.actionSummary?.contains("no calendar account available") == true ->
                    "saved locally only — no calendar account available"
                creationTrace?.actionSummary?.contains("calendar permission unavailable") == true ->
                    "saved locally only — Calendar permission was unavailable"
                else -> "saved locally only — Calendar Provider was unavailable"
            }
            "This medium-risk action will also cancel the linked departure reminder. The meeting was $localOnlyReason."
        }
    }

    fun confirmCancellation() {
        val planId = pendingCancellationPlanId ?: return
        Log.d(TAG, "Confirm cancellation tapped for plan=$planId")
        viewModelScope.launch {
            reply = core.confirmCancel(planId)
            confirmCancel = false
            pendingCancellationPlanId = null
        }
    }

    fun requestCancellation(planId: String) {
        Log.d(TAG, "Cancel button tapped for plan=$planId")
        pendingCancellationPlanId = planId
        confirmCancel = true
        viewModelScope.launch { cancellationDialogText = cancellationImpactText(planId) }
    }

    fun dismissCancellation() {
        Log.d(TAG, "Cancellation dismissed")
        confirmCancel = false
        pendingCancellationPlanId = null
        cancellationDialogText = "This action will also cancel the linked departure reminder."
    }

    private fun handoffAlarm(parameters: String) {
        val separator = parameters.indexOf('\u0000')
        val timeParts = (if (separator >= 0) parameters.substring(0, separator) else parameters)
            .split(':')
        val hour = timeParts.getOrNull(0)?.toIntOrNull()
        val minute = timeParts.getOrNull(1)?.toIntOrNull()
        val label = if (separator >= 0) parameters.substring(separator + 1) else "JARVIS alarm"
        if (hour == null || minute == null) {
            reply = "I couldn't read that alarm time. Please try a time such as 7 AM."
            return
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        Log.i(
            ALARM_TAG,
            "ACTION_SET_ALARM extras: EXTRA_SKIP_UI=${intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true)}, " +
                "EXTRA_HOUR=${intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1)}, " +
                "EXTRA_MINUTES=${intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1)}"
        )
        if (!com.jarvis.core.BackgroundHandoff.canOpenAppsNow()) {
            // Hands-free request while JARVIS is not on screen: Android would drop the launch, so say so.
            reply = com.jarvis.core.BackgroundHandoff.offer(getApplication(), intent, "Clock")
            return
        }
        try {
            getApplication<Application>().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            com.jarvis.core.ClockHistoryStore(getApplication()).recordAlarm(hour, minute, label)
            viewModelScope.launch { core.recordAlarmHandoff(hour, minute) }
            reply = "Opening Clock to confirm an alarm for ${String.format("%d:%02d", if (hour % 12 == 0) 12 else hour % 12, minute)} ${if (hour < 12) "AM" else "PM"}."
        } catch (_: ActivityNotFoundException) {
            reply = "No compatible Clock app is available to set that alarm."
        } catch (_: SecurityException) {
            reply = "Android did not allow opening the Clock app for that alarm."
        }
    }

    fun recheckTravel() {
        progress("Re-checking travel time now…")
        viewModelScope.launch {
            BackgroundEngine.evaluateNow(db, travelAgent)
            reply = "Travel re-check complete. See Decision Trace for live or estimated data."
        }
    }

    fun dismissCommunication() {
        communicationDraft = null
        editingCommunicationDraft = false
        choosingCommunicationTarget = false
        communicationRecipient = null
        communicationPhoneNumber = null
    }

    private enum class CommunicationTarget {
        Messages,
        WhatsApp
    }

    fun beginCommunicationEdit() {
        editingCommunicationDraft = true
        choosingCommunicationTarget = false
    }

    fun updateCommunicationDraft(body: String) {
        communicationDraft = body
    }

    fun beginCommunicationHandoff() {
        editingCommunicationDraft = false
        choosingCommunicationTarget = true
    }

    fun handoffCommunicationToMessages() = handoffCommunication(CommunicationTarget.Messages)

    fun handoffCommunicationToWhatsApp() = handoffCommunication(CommunicationTarget.WhatsApp)

    private fun handoffCommunication(target: CommunicationTarget) {
        val body = communicationDraft ?: return
        val number = communicationPhoneNumber ?: return
        val intent = when (target) {
            CommunicationTarget.Messages -> Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.fromParts("smsto", number, null)
                putExtra("sms_body", body)
            }
            CommunicationTarget.WhatsApp -> {
                val deepLink = WhatsAppLink.create(number, body)
                if (deepLink == null) {
                    communicationResolutionError =
                        "WhatsApp needs this contact's phone number in international format."
                    return
                }
                Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).setPackage("com.whatsapp")
            }
        }
        val context = getApplication<Application>()
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            viewModelScope.launch { core.recordCommunicationHandoff(body) }
            communicationDraft = null
            editingCommunicationDraft = false
            choosingCommunicationTarget = false
            communicationRecipient = null
            communicationPhoneNumber = null
            reply = if (target == CommunicationTarget.WhatsApp) {
                "Draft handed off to WhatsApp. Review and send it there."
            } else {
                "Draft handed off to Messages. Review and send it there."
            }
        } catch (_: ActivityNotFoundException) {
            communicationResolutionError = if (target == CommunicationTarget.WhatsApp) {
                "WhatsApp is not available to open this draft."
            } else {
                "No Messages app is available to open this draft."
            }
        } catch (_: SecurityException) {
            communicationResolutionError = "Android did not allow opening the selected communication app."
        }
    }

    fun dismissCommunicationResolutionError() {
        communicationResolutionError = null
    }

    fun reportContactsPermissionDenied() {
        communicationResolutionError = ContactAccess.DENIED
    }

    fun approveAutomation(id: String) {
        viewModelScope.launch { db.automationRuleDao().updateStatus(id, "approved") }
    }

    fun dismissAutomation(id: String) {
        viewModelScope.launch { db.automationRuleDao().updateStatus(id, "paused") }
    }

    override fun onCleared() {
        timerPrefs.unregisterOnSharedPreferenceChangeListener(timerListener)
        // viewModelScope is already cancelled, so the speech-status collector can't report the end of playback: release
        // the microphone claims here, or the wake-word service would stay paused ("deaf") until the process restarts.
        MicArbiter.setSpeaking(false)
        MicArbiter.setRecognizing(false)
        if (WakeWordEvents.commandSink === wakeCommandSink) WakeWordEvents.commandSink = null
        speech.stop()
        systemVoice.shutdown()
        super.onCleared()
    }
}

data class SummaryConfirmation(val filter: MessageFilter, val count: Int, val disclosure: String)

/** Editable email draft shown before handoff. The address stays on the device. */
data class EmailDraftState(
    val recipientName: String,
    val address: String,
    val subject: String,
    val body: String,
    val source: String
)

/** One line of the visible conversation. Assistant ids match [com.jarvis.core.AssistantReply] ids. */
data class ChatMessage(val id: Long, val fromUser: Boolean, val text: String, val time: Long = System.currentTimeMillis())
