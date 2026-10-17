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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class JarvisViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "JarvisCancellation"
        private const val ALARM_TAG = "JarvisAlarm"
    }
    private val db = AppDatabase.create(application)
    private val calendarProvider = CalendarProviderGateway(application)
    private val reasoningProvider = ReasoningProviderFactory.create(application)
    private val phoneToolRuntime = AndroidPhoneToolRuntime(application, BuildConfig.YOUTUBE_API_KEY)
    private val memoryRepository = MemoryRepository(db.memoryDao())
    private val locationSource = AndroidLocationSource(application)
    private val travelAgent = OsrmTravelContextAgent(locationSource)
    private val core = JarvisCore(
        db,
        provider = reasoningProvider,
        calendarProvider = calendarProvider,
        travel = travelAgent,
        memory = memoryRepository,
        notificationAccess = { JarvisNotificationListener.isAccessEnabled(application) },
        locationSource = locationSource
    )
    val memories = db.memoryDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { LegacyPersonalFactsMigration.migrate(application, memoryRepository) }
    }

    fun addMemory(text: String) {
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
    var reply by mutableStateOf("Try: “I have a meeting tomorrow at 10 AM at college.”")
        private set
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

    fun submit(text: String) {
        if (text.isBlank()) return
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
                reply = "Waiting for your confirmation before any message text is sent."
            } else if (result.startsWith("PHONE_TOOL:")) {
                handlePhoneToolRequest(result.removePrefix("PHONE_TOOL:"))
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
                        is ContactResolution.Found -> {
                            communicationRecipient = resolution.contact.displayName
                            communicationPhoneNumber = resolution.contact.normalizedPhoneNumber
                            communicationDraft = body
                        }
                        ContactResolution.NotFound ->
                            communicationResolutionError = "No contact found named $recipientName."
                        ContactResolution.MultipleMatches ->
                            communicationResolutionError = "More than one contact matched $recipientName. Use a more specific contact name."
                        ContactResolution.QueryFailed ->
                            communicationResolutionError = "JARVIS couldn't read contacts. Check Contacts permission and try again."
                    }
                }
            } else {
                reply = result
            }
        }
    }

    fun confirmSummary() {
        val pending = summaryConfirmation ?: return
        summaryConfirmation = null
        reply = "Summarizing…"
        viewModelScope.launch {
            reply = core.summarizeMessages(pending.filter)
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
        val compose = pendingCompose
        if (compose != null && permission == Manifest.permission.READ_CONTACTS) {
            pendingCompose = null
            viewModelScope.launch {
                if (granted) resolveAndDraftEmail(compose)
                else {
                    reply = "Contacts permission was denied, so I can't find the email address. Nothing was drafted."
                    core.recordPhoneToolOutcome("compose_email", "contacts permission denied")
                }
            }
            return
        }
        viewModelScope.launch {
            phoneActions.onPermissionResult(permission, granted)
            syncPhoneActionState()
        }
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
    private var pendingCompose: PlanStep? = null

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
                reply = "Google will ask you to allow read-only Gmail access. JARVIS never sends email."
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
                is GmailResult.Success -> { reply = EmailReplies.today(result.value); null }
                is GmailResult.Failure -> result
            }
            else -> when (val result = gmail.todaysEmails(startOfDay)) {
                is GmailResult.Success -> {
                    if (result.value.isEmpty()) {
                        reply = EmailReplies.today(result.value)
                    } else {
                        pendingEmails = result.value
                        emailSummaryCount = minOf(result.value.size, JarvisCore.SUMMARY_LIMIT)
                        reply = "Waiting for your confirmation before any email text is sent."
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
        reply = "Summarizing…"
        viewModelScope.launch {
            reply = core.summarizeEmails(emails)
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

    private suspend fun startCompose(step: PlanStep) {
        if (ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            resolveAndDraftEmail(step)
        } else {
            pendingCompose = step
            phonePermissionRequest = Manifest.permission.READ_CONTACTS
        }
    }

    private suspend fun resolveAndDraftEmail(step: PlanStep) {
        val name = step.parameters.getValue("recipient_name")
        val resolution = try {
            withContext(Dispatchers.IO) { resolveContactEmail(getApplication<Application>().contentResolver, name) }
        } catch (_: SecurityException) {
            EmailResolution.QueryFailed
        }
        when (resolution) {
            is EmailResolution.Found -> {
                reply = "Drafting an email to ${resolution.displayName}…"
                val (draft, source) = core.draftEmail(resolution.displayName, step.parameters.getValue("request"), step.parameters["tone"])
                engineLabel = core.lastEngineLabel
                emailDraft = EmailDraftState(resolution.displayName, resolution.address, draft.subject, draft.body, source)
                reply = "Review and edit the draft. JARVIS will open your mail app; you tap Send there."
            }
            EmailResolution.NotFound -> reply = "No email address is saved in Contacts for $name."
            EmailResolution.MultipleMatches -> reply = "More than one contact with an email matched $name. Use a more specific name."
            EmailResolution.QueryFailed -> reply = "JARVIS couldn't read contacts. Check Contacts permission and try again."
        }
        if (resolution !is EmailResolution.Found) core.recordPhoneToolOutcome("compose_email", "recipient not resolved")
    }

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
            reply = "The saved email address for ${draft.recipientName} is not a plain address, so JARVIS won't use it."
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
            reply = "Opened your mail app with the draft to ${draft.recipientName}. Review it and tap Send there; JARVIS did not send anything."
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
        try {
            getApplication<Application>().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            viewModelScope.launch { core.recordAlarmHandoff(hour, minute) }
            reply = "Opening Clock to confirm an alarm for ${String.format("%d:%02d", if (hour % 12 == 0) 12 else hour % 12, minute)} ${if (hour < 12) "AM" else "PM"}."
        } catch (_: ActivityNotFoundException) {
            reply = "No compatible Clock app is available to set that alarm."
        } catch (_: SecurityException) {
            reply = "Android did not allow opening the Clock app for that alarm."
        }
    }

    fun recheckTravel() {
        reply = "Re-checking travel time now. I will label the result live or estimated."
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
        communicationResolutionError = "Contacts permission is required to resolve a named recipient."
    }

    fun approveAutomation(id: String) {
        viewModelScope.launch { db.automationRuleDao().updateStatus(id, "approved") }
    }

    fun dismissAutomation(id: String) {
        viewModelScope.launch { db.automationRuleDao().updateStatus(id, "paused") }
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
