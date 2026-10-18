package com.jarvis.core

import com.jarvis.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** A voice the user can pick by name. The ID is an implementation detail and is never shown or typed. */
data class VoiceChoice(val id: String, val name: String)

/**
 * The small, curated shortlist offered in Settings. These are ElevenLabs' default ("premade") voices; whether each
 * one really exists on the user's account is confirmed at runtime by [ElevenLabsVoiceDirectory] and shown in the UI.
 */
object ElevenLabsVoices {
    val choices = listOf(
        VoiceChoice(ElevenLabsTts.DEFAULT_VOICE_ID, "George"),
        VoiceChoice("nPczCjzI2devNBz1zQrb", "Brian"),
        VoiceChoice("onwK4e9ZLuTAKqWW03F9", "Daniel"),
        VoiceChoice("EXAVITQu4vr4xnSDxMaL", "Sarah"),
        VoiceChoice("Xb7hH8MSUJpSbSDYk0k2", "Alice"),
        VoiceChoice("cgSgspJ2msm6clMCkdW9", "Jessica")
    )

    /** A saved or typed ID that is not on the list (an older setup) falls back to the default voice. */
    fun resolve(id: String?): VoiceChoice = choices.firstOrNull { it.id == id } ?: choices.first()
}

/** What the account says about one curated voice. */
sealed interface VoiceCheck {
    /** [description] comes from ElevenLabs' own labels for the voice (e.g. "male · british"), never from us. */
    data class Available(val name: String, val description: String) : VoiceCheck
    data object Missing : VoiceCheck
    data class Unknown(val reason: String) : VoiceCheck
}

/**
 * Reads each curated voice with GET /v1/voices/{voice_id}. This is account metadata: it does not generate audio, so it
 * does not use text-to-speech credits. Only the API key and a public voice ID are sent.
 */
class ElevenLabsVoiceDirectory(
    private val apiKey: () -> String?,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://api.elevenlabs.io/v1/voices/"
) {
    suspend fun check(choices: List<VoiceChoice> = ElevenLabsVoices.choices): Map<String, VoiceCheck> {
        val key = apiKey()?.takeIf(String::isNotBlank) ?: return choices.associate { it.id to VoiceCheck.Unknown("no ElevenLabs key configured") }
        return coroutineScope {
            choices.map { choice -> async(Dispatchers.IO) { choice.id to checkOne(choice.id, key) } }.awaitAll().toMap()
        }
    }

    private suspend fun checkOne(id: String, key: String): VoiceCheck = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(baseUrl.trimEnd('/') + "/" + id).header("xi-api-key", key).get().build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> parse(body)
                    response.code == 404 || (response.code == 400 && "voice_not_found" in body) -> VoiceCheck.Missing
                    response.code == 401 -> VoiceCheck.Unknown(ElevenLabsTts.describe(401, body))
                    else -> VoiceCheck.Unknown("HTTP ${response.code}")
                }
            }
        } catch (error: IOException) {
            VoiceCheck.Unknown("network error (${error.javaClass.simpleName})")
        }
    }

    internal fun parse(body: String): VoiceCheck = try {
        val json = JSONObject(body)
        val labels = json.optJSONObject("labels")
        val description = listOf("gender", "accent", "descriptive")
            .mapNotNull { labels?.optString(it)?.takeIf(String::isNotBlank) }
            .joinToString(" · ")
        VoiceCheck.Available(json.optString("name").ifBlank { "voice" }, description)
    } catch (_: Exception) {
        VoiceCheck.Unknown("unexpected response")
    }
}

/**
 * Where optional service credentials come from. They are never typed into the app: a build reads them from the
 * git-ignored local.properties (or an environment variable). A key saved by an older version of the Settings screen is
 * still honoured, but the build's key wins. A key compiled into an APK can be extracted from it, so use restricted,
 * revocable keys; for a distributed build the key belongs on a server that proxies requests.
 */
class ApiCredentials(private val store: SecretStore) {
    enum class Source { BUILD, SAVED_ON_PHONE, NONE }

    fun elevenLabsKey(): String? = BuildConfig.ELEVENLABS_API_KEY.takeIf(String::isNotBlank) ?: store.get(SecretStore.ELEVENLABS_API_KEY)
    fun googleRoutesKey(): String? = BuildConfig.GOOGLE_ROUTES_API_KEY.takeIf(String::isNotBlank) ?: store.get(SecretStore.GOOGLE_ROUTES_API_KEY)
    fun mapboxToken(): String? = BuildConfig.MAPBOX_ACCESS_TOKEN.takeIf(String::isNotBlank) ?: store.get(SecretStore.MAPBOX_ACCESS_TOKEN)

    fun elevenLabsSource(): Source = when {
        BuildConfig.ELEVENLABS_API_KEY.isNotBlank() -> Source.BUILD
        store.has(SecretStore.ELEVENLABS_API_KEY) -> Source.SAVED_ON_PHONE
        else -> Source.NONE
    }
}
