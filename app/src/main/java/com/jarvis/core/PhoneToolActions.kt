package com.jarvis.core

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

data class PhoneIntentSpec(
    val action: String,
    val data: String? = null,
    val packageName: String? = null,
    val extras: Map<String, Any> = emptyMap()
)

object PhoneToolIntentFactory {
    fun timer(seconds: Int): PhoneIntentSpec? =
        if (seconds <= 0) null else PhoneIntentSpec(
            action = "android.intent.action.SET_TIMER",
            extras = mapOf(
                "android.intent.extra.alarm.LENGTH" to seconds,
                "android.intent.extra.alarm.SKIP_UI" to false
            )
        )

    fun alarm(hour: Int, minute: Int, label: String): PhoneIntentSpec? =
        if (hour !in 0..23 || minute !in 0..59) null else PhoneIntentSpec(
            action = "android.intent.action.SET_ALARM",
            extras = mapOf(
                "android.intent.extra.alarm.HOUR" to hour,
                "android.intent.extra.alarm.MINUTES" to minute,
                "android.intent.extra.alarm.MESSAGE" to label.ifBlank { "JARVIS alarm" },
                "android.intent.extra.alarm.SKIP_UI" to false
            )
        )

    fun youtubeSearch(query: String): PhoneIntentSpec? =
        query.trim().takeIf(String::isNotBlank)?.let {
            PhoneIntentSpec("android.intent.action.VIEW", "https://www.youtube.com/results?search_query=${encode(it)}")
        }

    fun youtubeVideo(videoId: String): PhoneIntentSpec? =
        videoId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,}")) }?.let {
            PhoneIntentSpec("android.intent.action.VIEW", "vnd.youtube:$it", "com.google.android.youtube")
        }

    fun youtubeVideoWeb(videoId: String): PhoneIntentSpec? =
        videoId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,}")) }?.let {
            PhoneIntentSpec("android.intent.action.VIEW", "https://www.youtube.com/watch?v=$it")
        }

    /** A specific song in YouTube Music: the official music.youtube.com link opens and plays it. */
    fun youtubeMusicSong(videoId: String): PhoneIntentSpec? =
        videoId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,}")) }?.let {
            PhoneIntentSpec("android.intent.action.VIEW", "https://music.youtube.com/watch?v=$it", "com.google.android.apps.youtube.music")
        }

    fun musicSearch(query: String, packageName: String? = null): PhoneIntentSpec? =
        query.trim().takeIf(String::isNotBlank)?.let {
            PhoneIntentSpec(
                packageName = packageName,
                action = "android.media.action.MEDIA_PLAY_FROM_SEARCH",
                extras = mapOf(
                    "query" to it,
                    "android.intent.extra.focus" to "vnd.android.cursor.item/*",
                    "android.intent.extra.title" to it
                )
            )
        }

    fun call(number: String): PhoneIntentSpec? =
        number.takeIf { it.isNotBlank() }?.let {
            PhoneIntentSpec("android.intent.action.CALL", "tel:$it")
        }

    fun navigate(destination: String): PhoneIntentSpec? =
        destination.trim().takeIf(String::isNotBlank)?.let {
            PhoneIntentSpec("android.intent.action.VIEW", "google.navigation:q=${encode(it)}")
        }

    /** Generic map search understood by most map apps when Google Maps navigation is unavailable. */
    fun mapSearch(destination: String): PhoneIntentSpec? =
        destination.trim().takeIf(String::isNotBlank)?.let {
            PhoneIntentSpec("android.intent.action.VIEW", "geo:0,0?q=${encode(it)}")
        }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

object PhoneToolRequestCodec {
    fun encode(step: PlanStep): String =
        JSONObject()
            .put("name", step.toolName)
            .put("parameters", JSONObject(step.parameters))
            .toString()

    fun decode(value: String): PlanStep? {
        return try {
            val request = JSONObject(value)
            val name = request.optString("name")
            val parameters = request.optJSONObject("parameters") ?: return null
            if (ReasoningToolSchema.definition(name) == null) return null
            val values = mutableMapOf<String, String>()
            val keys = parameters.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val rawValue = parameters.opt(key)
                if (rawValue !is String) return null
                values[key] = rawValue
            }
            PlanStep(name, values).takeIf { ReasoningToolSchema.validate(it) == null }
        } catch (_: JSONException) {
            null
        }
    }
}

data class YouTubeSearchDecision(val videoId: String?, val fallbackReason: String?)

object YouTubeSearchPolicy {
    fun decide(apiKeyConfigured: Boolean, httpStatus: Int?, videoId: String?): YouTubeSearchDecision =
        when {
            !apiKeyConfigured -> YouTubeSearchDecision(null, "YouTube API key is not configured.")
            httpStatus == null -> YouTubeSearchDecision(null, "YouTube search could not connect.")
            httpStatus == 403 || httpStatus == 429 ->
                YouTubeSearchDecision(null, "YouTube search quota or access is unavailable (HTTP $httpStatus).")
            httpStatus !in 200..299 ->
                YouTubeSearchDecision(null, "YouTube search failed (HTTP $httpStatus).")
            videoId.isNullOrBlank() ->
                YouTubeSearchDecision(null, "YouTube search returned no video.")
            else -> YouTubeSearchDecision(videoId, null)
        }
}

class YouTubeSearchClient(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val endpoint: String = "https://www.googleapis.com/youtube/v3/search"
) {
    /** [musicOnly] limits results to YouTube's Music category (id 10), for YouTube Music playback. */
    suspend fun search(query: String, musicOnly: Boolean = false): YouTubeSearchDecision {
        if (apiKey.isBlank()) return YouTubeSearchPolicy.decide(false, null, null)
        if (query.isBlank()) return YouTubeSearchDecision(null, "YouTube search query is missing.")
        return try {
            withContext(Dispatchers.IO) {
                val url = endpoint.toHttpUrl().newBuilder()
                    .addQueryParameter("part", "snippet")
                    .addQueryParameter("type", "video")
                    .addQueryParameter("maxResults", "1")
                    .apply { if (musicOnly) addQueryParameter("videoCategoryId", "10") }
                    .addQueryParameter("q", query)
                    .addQueryParameter("key", apiKey)
                    .build()
                client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        YouTubeSearchPolicy.decide(true, response.code, null)
                    } else {
                        val body = response.body?.string().orEmpty()
                        val videoId = try {
                            JSONObject(body).optJSONArray("items")
                                ?.optJSONObject(0)
                                ?.optJSONObject("id")
                                ?.optString("videoId")
                        } catch (_: JSONException) {
                            null
                        }
                        YouTubeSearchPolicy.decide(true, response.code, videoId)
                    }
                }
            }
        } catch (_: IOException) {
            YouTubeSearchPolicy.decide(true, null, null)
        }
    }
}

sealed interface PhoneToolExecution {
    /** [traceOutcome] says what JARVIS actually knows, e.g. a handoff is not confirmed playback. */
    data class Success(
        val userMessage: String,
        val traceOutcome: String = "handed off or applied"
    ) : PhoneToolExecution
    data class Failure(val message: String) : PhoneToolExecution
}

sealed interface PhoneHardwareResult {
    data class Success(val message: String, val traceOutcome: String = "applied") : PhoneHardwareResult
    data class Failure(val message: String) : PhoneHardwareResult
}

interface PhoneHardwareAdapter {
    fun sendMediaAction(action: String): PhoneHardwareResult
    fun setVolumePercent(level: Int): PhoneHardwareResult
    fun setFlashlight(enabled: Boolean): PhoneHardwareResult
}

class PhoneHardwareToolExecutor(private val hardware: PhoneHardwareAdapter) {
    fun execute(step: PlanStep): PhoneToolExecution =
        when (step.toolName) {
            "media_control" -> {
                val action = step.parameters["action"]
                if (action !in setOf("play", "pause", "next", "previous")) {
                    PhoneToolExecution.Failure("Choose play, pause, next, or previous.")
                } else {
                    hardware.sendMediaAction(checkNotNull(action)).asExecution()
                }
            }
            "set_volume" -> {
                val level = step.parameters["level"]?.toIntOrNull()
                if (level == null || level !in 0..100) {
                    PhoneToolExecution.Failure("Volume level must be from 0 to 100.")
                } else {
                    hardware.setVolumePercent(level).asExecution()
                }
            }
            "flashlight" -> {
                val enabled = when (step.parameters["enabled"]) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
                if (enabled == null) {
                    PhoneToolExecution.Failure("Flashlight enabled must be true or false.")
                } else {
                    hardware.setFlashlight(enabled).asExecution()
                }
            }
            else -> PhoneToolExecution.Failure("That phone hardware tool is not available.")
        }

    private fun PhoneHardwareResult.asExecution(): PhoneToolExecution = when (this) {
        is PhoneHardwareResult.Success -> PhoneToolExecution.Success(message, traceOutcome)
        is PhoneHardwareResult.Failure -> PhoneToolExecution.Failure(message)
    }
}

sealed interface CallContactDecision {
    data class NeedsConfirmation(val contact: ResolvedContact) : CallContactDecision
    data object NeedsPermission : CallContactDecision
    data class Ready(val contact: ResolvedContact) : CallContactDecision
    data class Failure(val message: String) : CallContactDecision
    /** Several people or numbers matched: the user picks one, then the usual confirmation follows. */
    data class Choose(val options: List<ResolvedContact>, val suggestions: Boolean = false) : CallContactDecision
}

/** Honest wording about Contacts access, shared by calls, messages, and email. */
object ContactAccess {
    const val READ_FAILED = "JARVIS couldn't read your contacts. Allow Contacts in Settings → Connections and permissions " +
        "(or Android Settings → Apps → JARVIS → Permissions)."
    const val DENIED = "Contacts access is off, so I can't look up who you mean. Allow it in Settings → Connections and " +
        "permissions (or Android Settings → Apps → JARVIS → Permissions), then ask again."

    /** "Ma ❤️ · Mobile · +91 98765 43210" — enough to tell people and numbers apart. */
    fun label(contact: ResolvedContact): String =
        listOfNotNull(contact.displayName, contact.numberLabel, contact.normalizedPhoneNumber).joinToString(" · ")

    /** The spoken/visible question for a choice list. */
    fun question(action: String, requested: String, options: List<ResolvedContact>, suggestions: Boolean = false): String {
        val people = options.map { it.displayName }.distinct().size
        val what = when {
            suggestions -> "I couldn't find \"$requested\" exactly. Did you mean one of these"
            people == 1 -> "${options.first().displayName} has ${options.size} numbers"
            else -> "I found ${options.size} matches for $requested"
        }
        if (suggestions) {
            return "$what? Which one should I $action? " + options.take(6).mapIndexed { i, c -> "${i + 1}: ${label(c)}" }.joinToString("; ") + "."
        }
        return "$what. Which one should I $action? " +
            options.take(6).mapIndexed { i, c -> "${i + 1}: ${label(c)}" }.joinToString("; ") + "."
    }
}

object CallContactSafetyGate {
    fun afterResolution(resolution: ContactResolution): CallContactDecision = when (resolution) {
        is ContactResolution.Found -> CallContactDecision.NeedsConfirmation(resolution.contact)
        ContactResolution.NotFound -> CallContactDecision.Failure("No matching contact was found.")
        is ContactResolution.Ambiguous -> CallContactDecision.Choose(resolution.options, resolution.suggestions)
        ContactResolution.QueryFailed -> CallContactDecision.Failure(ContactAccess.READ_FAILED)
    }

    fun afterConfirmation(contact: ResolvedContact, confirmed: Boolean, callPermissionGranted: Boolean): CallContactDecision =
        when {
            !confirmed -> CallContactDecision.Failure("Call cancelled.")
            !callPermissionGranted -> CallContactDecision.NeedsPermission
            else -> CallContactDecision.Ready(contact)
        }
}

/** A media key is fire-and-forget: report what was sent, never claim the app acted. */
object MediaKeyMessages {
    fun afterDispatch(action: String, audioWasPlaying: Boolean): PhoneHardwareResult.Success =
        if (!audioWasPlaying && action != "play") {
            PhoneHardwareResult.Success(
                "Sent $action, but no app appeared to be playing audio, so it may have no effect.",
                "media key sent; no audio was playing"
            )
        } else {
            PhoneHardwareResult.Success(
                "Sent $action to the current media app. JARVIS cannot confirm that the app acted.",
                "media key sent; result unconfirmed"
            )
        }
}
