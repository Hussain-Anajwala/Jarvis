package com.jarvis.ui

import android.app.Application
import android.util.Log
import android.content.Intent
import android.net.Uri
import com.jarvis.core.CommunicationRequestParser
import com.jarvis.core.ContactResolution
import com.jarvis.core.ContactResolver
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
    }
    private val db = AppDatabase.create(application)
    private val core = JarvisCore(db)
    val plans = db.planDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val planItems = db.planItemDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val traces = db.traceDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val reminders = db.reminderDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val events = db.eventDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val automationRules = db.automationRuleDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    var reply by mutableStateOf("Try: “I have a meeting tomorrow at 10 AM at college.”")
        private set
    var confirmCancel by mutableStateOf(false)
        private set
    var communicationDraft by mutableStateOf<String?>(null)
        private set
    var editingCommunicationDraft by mutableStateOf(false)
        private set
    var communicationRecipient by mutableStateOf<String?>(null)
        private set
    var communicationResolutionError by mutableStateOf<String?>(null)
        private set
    private var communicationPhoneNumber: String? = null
    private var pendingCancellationPlanId by mutableStateOf<String?>(null)

    fun submit(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val result = core.handle(text)
            if (result == "CONFIRM_CANCEL") {
                val activePlan = db.planDao().observeAll().first().firstOrNull { it.status == "active" }
                if (activePlan == null) {
                    reply = "There are no active plans to cancel."
                } else {
                    pendingCancellationPlanId = activePlan.id
                    confirmCancel = true
                }
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

    private suspend fun resolveContact(name: String): ContactResolution =
        try {
            withContext(Dispatchers.IO) {
                ContactResolver(getApplication<Application>().contentResolver).resolve(name)
            }
        } catch (_: SecurityException) {
            ContactResolution.QueryFailed
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
    }

    fun dismissCancellation() {
        Log.d(TAG, "Cancellation dismissed")
        confirmCancel = false
        pendingCancellationPlanId = null
    }

    fun recheckTravel() {
        reply = "Re-checking travel time now. I will label the result live or estimated."
        viewModelScope.launch {
            BackgroundEngine.evaluateNow(db)
            reply = "Travel re-check complete. See Decision Trace for live or estimated data."
        }
    }

    fun dismissCommunication() {
        communicationDraft = null
        editingCommunicationDraft = false
        communicationRecipient = null
        communicationPhoneNumber = null
    }

    fun beginCommunicationEdit() {
        editingCommunicationDraft = true
    }

    fun updateCommunicationDraft(body: String) {
        communicationDraft = body
    }

    fun confirmCommunication() {
        val body = communicationDraft ?: return
        val number = communicationPhoneNumber ?: return
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = Uri.fromParts("smsto", number, null)
            putExtra("sms_body", body)
        }
        val context = getApplication<Application>()
        context.startActivity(
            Intent.createChooser(intent, "Choose communication app")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        viewModelScope.launch { core.recordCommunicationHandoff(body) }
        communicationDraft = null
        editingCommunicationDraft = false
        communicationRecipient = null
        communicationPhoneNumber = null
        reply = "Draft handed off to your communication app."
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
