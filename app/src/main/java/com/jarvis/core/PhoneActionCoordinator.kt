package com.jarvis.core

import android.Manifest

/** Android-facing operations the phone-action state machine needs; faked in unit tests. */
interface PhoneActionHost {
    fun isGranted(permission: String): Boolean
    suspend fun resolveContact(name: String): ContactResolution
    suspend fun execute(step: PlanStep): PhoneToolExecution
    suspend fun callConfirmedContact(contact: ResolvedContact): PhoneToolExecution
    suspend fun recordOutcome(toolName: String, outcome: String)
}

/**
 * Runs a validated phone tool request through permissions and, for calls, the explicit
 * confirmation dialog. A call is placed only after [confirmCall] for the contact that was
 * shown in the dialog, and only once CALL_PHONE is granted.
 */
class PhoneActionCoordinator(private val host: PhoneActionHost) {
    var reply: String? = null
        private set
    var callConfirmationContact: ResolvedContact? = null
        private set
    var permissionRequest: String? = null
        private set

    private var pendingCallName: String? = null
    private var pendingConfirmedContact: ResolvedContact? = null

    suspend fun handle(step: PlanStep) {
        reply = null
        if (ReasoningToolSchema.validate(step) != null) {
            reply = "I couldn't safely process that phone request. Please try again with the required details."
            return
        }
        if (step.toolName != "call_contact") {
            execute(step)
            return
        }
        val name = step.parameters.getValue("name")
        if (host.isGranted(Manifest.permission.READ_CONTACTS)) {
            resolveCall(name)
        } else {
            pendingCallName = name
            permissionRequest = Manifest.permission.READ_CONTACTS
        }
    }

    suspend fun confirmCall() {
        reply = null
        val contact = callConfirmationContact ?: return
        callConfirmationContact = null
        when (CallContactSafetyGate.afterConfirmation(
            contact,
            confirmed = true,
            callPermissionGranted = host.isGranted(Manifest.permission.CALL_PHONE)
        )) {
            CallContactDecision.NeedsPermission -> {
                pendingConfirmedContact = contact
                permissionRequest = Manifest.permission.CALL_PHONE
            }
            is CallContactDecision.Ready -> placeCall(contact)
            else -> reply = "The call could not be prepared."
        }
    }

    suspend fun dismissCall() {
        reply = null
        if (callConfirmationContact == null) return
        callConfirmationContact = null
        reply = "Call cancelled. No call was placed."
        host.recordOutcome("call_contact", "cancelled by user")
    }

    suspend fun onPermissionResult(permission: String, granted: Boolean) {
        reply = null
        if (permission != permissionRequest) return
        permissionRequest = null
        when (permission) {
            Manifest.permission.READ_CONTACTS -> {
                val name = pendingCallName ?: return
                pendingCallName = null
                if (granted) {
                    resolveCall(name)
                } else {
                    reply = "Contacts permission was denied. No call was placed."
                    host.recordOutcome("call_contact", "contacts permission denied")
                }
            }
            Manifest.permission.CALL_PHONE -> {
                val contact = pendingConfirmedContact ?: return
                pendingConfirmedContact = null
                if (granted) {
                    placeCall(contact)
                } else {
                    reply = "Phone permission was denied. No call was placed."
                    host.recordOutcome("call_contact", "phone permission denied")
                }
            }
        }
    }

    private suspend fun resolveCall(name: String) {
        val resolution = try {
            host.resolveContact(name)
        } catch (_: SecurityException) {
            ContactResolution.QueryFailed
        }
        when (val decision = CallContactSafetyGate.afterResolution(resolution)) {
            is CallContactDecision.NeedsConfirmation -> {
                callConfirmationContact = decision.contact
                reply = "I found ${decision.contact.displayName}. Confirm before I place the call."
            }
            is CallContactDecision.Failure -> {
                reply = decision.message
                host.recordOutcome("call_contact", "contact resolution failed")
            }
            else -> reply = "The contact could not be resolved safely."
        }
    }

    private suspend fun placeCall(contact: ResolvedContact) {
        when (val result = host.callConfirmedContact(contact)) {
            is PhoneToolExecution.Success -> {
                reply = result.userMessage
                host.recordOutcome("call_contact", "confirmed and handed off")
            }
            is PhoneToolExecution.Failure -> {
                reply = result.message
                host.recordOutcome("call_contact", "failed")
            }
        }
    }

    private suspend fun execute(step: PlanStep) {
        when (val result = host.execute(step)) {
            is PhoneToolExecution.Success -> {
                reply = result.userMessage
                host.recordOutcome(step.toolName, result.traceOutcome)
            }
            is PhoneToolExecution.Failure -> {
                reply = result.message
                host.recordOutcome(step.toolName, "failed")
            }
        }
    }
}
