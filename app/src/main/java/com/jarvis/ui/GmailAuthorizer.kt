package com.jarvis.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.jarvis.core.GMAIL_READONLY_SCOPE
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface GmailAuthOutcome {
    data class Granted(val token: String) : GmailAuthOutcome
    data class NeedsConsent(val pendingIntent: PendingIntent) : GmailAuthOutcome
    data class Failed(val message: String) : GmailAuthOutcome
}

/**
 * Google Identity Services AuthorizationClient (the current replacement for GoogleSignIn scopes),
 * requesting gmail.readonly only. The access token is kept in memory for this process and never persisted.
 */
class GmailAuthorizer(context: Context, private val http: OkHttpClient = OkHttpClient()) {
    private val appContext = context.applicationContext
    private val client = Identity.getAuthorizationClient(appContext)
    @Volatile private var token: String? = null

    val isConnected: Boolean get() = token != null
    fun cachedToken(): String? = token
    /** Called after Gmail rejects a token (HTTP 401); also drops it from Play services' cache. */
    suspend fun forget(staleToken: String) {
        if (token == staleToken) token = null
        clearCachedToken(staleToken)
    }

    /** Without this, authorize() keeps returning a revoked or expired token from Play services' cache. */
    private suspend fun clearCachedToken(value: String) = withContext(Dispatchers.IO) {
        try {
            GoogleAuthUtil.clearToken(appContext, value)
        } catch (_: Exception) {
            // Best effort: GoogleAuthException/IOException leave the in-memory token already cleared.
        }
    }

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(GMAIL_READONLY_SCOPE)))
        .build()

    /** Returns a token silently when consent already exists; otherwise the consent PendingIntent to launch. */
    suspend fun authorize(): GmailAuthOutcome = suspendCancellableCoroutine { continuation ->
        client.authorize(request)
            .addOnSuccessListener { result -> continuation.resume(handle(result)) }
            .addOnFailureListener { error -> continuation.resume(GmailAuthOutcome.Failed(describe(error))) }
    }

    fun handleConsentResult(data: Intent?): GmailAuthOutcome = try {
        handle(client.getAuthorizationResultFromIntent(data))
    } catch (error: ApiException) {
        GmailAuthOutcome.Failed(describe(error))
    }

    /** Revokes the grant at Google (standard OAuth revoke endpoint) and drops the in-memory token. */
    suspend fun disconnect(): Boolean {
        val current = token ?: return true
        token = null
        clearCachedToken(current)
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(
                    Request.Builder().url("https://oauth2.googleapis.com/revoke")
                        .post(FormBody.Builder().add("token", current).build()).build()
                ).execute().use { it.isSuccessful }
            } catch (_: IOException) {
                false
            }
        }
    }

    private fun handle(result: AuthorizationResult): GmailAuthOutcome {
        if (result.hasResolution()) {
            return result.pendingIntent?.let { GmailAuthOutcome.NeedsConsent(it) }
                ?: GmailAuthOutcome.Failed("Google did not return a consent screen.")
        }
        if (result.grantedScopes.none { it == GMAIL_READONLY_SCOPE }) {
            return GmailAuthOutcome.Failed("Gmail read access was not granted.")
        }
        val accessToken = result.accessToken ?: return GmailAuthOutcome.Failed("Google returned no access token.")
        token = accessToken
        return GmailAuthOutcome.Granted(accessToken)
    }

    private fun describe(error: Exception): String = when ((error as? ApiException)?.statusCode) {
        16 -> "Gmail connection was cancelled."
        10 -> "Google rejected this app's OAuth configuration (developer error). Check the Android OAuth client's package name and SHA-1."
        7 -> "No network connection for Google authorization."
        else -> "Gmail authorization failed${(error as? ApiException)?.let { " (code ${it.statusCode})" }.orEmpty()}."
    }
}
