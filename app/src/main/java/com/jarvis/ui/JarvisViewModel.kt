package com.jarvis.ui

import android.app.Application
import android.util.Log
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.core.JarvisCore
import com.jarvis.core.BackgroundEngine
import com.jarvis.data.AppDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
                communicationDraft = result.removePrefix("DRAFT_COMMUNICATION:")
            } else {
                reply = result
            }
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
    }

    fun confirmCommunication() {
        val body = communicationDraft ?: return
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = Uri.parse("smsto:5551234")
            putExtra("sms_body", body)
        }
        val context = getApplication<Application>()
        context.startActivity(
            Intent.createChooser(intent, "Choose communication app")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        viewModelScope.launch { core.recordCommunicationHandoff(body) }
        communicationDraft = null
        reply = "Draft handed off to your communication app."
    }

    fun approveAutomation(id: String) {
        viewModelScope.launch { db.automationRuleDao().updateStatus(id, "approved") }
    }

    fun dismissAutomation(id: String) {
        viewModelScope.launch { db.automationRuleDao().updateStatus(id, "paused") }
    }
}
