package com.jarvis.core

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.AlarmClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import kotlin.math.roundToInt


class AndroidPhoneToolRuntime(
    context: Context,
    private val youtubeApiKey: String,
    private val client: OkHttpClient = OkHttpClient()
) {
    private val appContext = context.applicationContext
    /** The language of the current request (set by the screen). */
    var replyLang: ReplyLang = ReplyLang.EN
    private val youtubeSearch = YouTubeSearchClient(youtubeApiKey, client)
    private val hardwareTools = PhoneHardwareToolExecutor(AndroidPhoneHardwareAdapter(appContext))
    private val playback = MediaSessionPlaybackObserver(appContext)

    suspend fun execute(step: PlanStep): PhoneToolExecution = when (step.toolName) {
        "set_timer" -> setTimer(step)
        "play_youtube" -> playYoutube(step.parameters["query"].orEmpty())
        "play_music" -> playMusic(step.parameters["query"].orEmpty(), MusicApp.fromArgument(step.parameters["app"]))
        "media_control", "set_volume", "flashlight" -> hardwareTools.execute(step)
        "navigate" -> navigate(step.parameters["destination"].orEmpty())
        "call_contact" -> PhoneToolExecution.Failure("Call requests must be resolved and confirmed before dispatch.")
        else -> PhoneToolExecution.Failure("That phone tool is not available.")
    }

    fun callConfirmedContact(contact: ResolvedContact): PhoneToolExecution {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return PhoneToolExecution.Failure("Phone permission is not granted. Allow it before calling.")
        }
        val spec = PhoneToolIntentFactory.call(contact.normalizedPhoneNumber)
            ?: return PhoneToolExecution.Failure("The resolved contact has no callable phone number.")
        return launch(spec, "the phone app") { "Calling ${contact.displayName}." }
    }

    private fun setTimer(step: PlanStep): PhoneToolExecution {
        val seconds = step.parameters["duration_seconds"]?.toIntOrNull()
            ?: return PhoneToolExecution.Failure("Timer duration must be a positive number of seconds.")
        if (seconds !in 1..86_400) return PhoneToolExecution.Failure("Timer duration must be between 1 second and 24 hours.")
        val engine = JarvisTimerEngine(appContext)
        if (engine.enabled) {
            // JARVIS runs the timer itself so "cancel the timer" can really cancel it (no app can cancel Clock's timers).
            val started = engine.startOnce(seconds, step.parameters["label"].orEmpty())
            return PhoneToolExecution.Success(TimerReplies.started(started, engine.exact(), lang = replyLang), "JARVIS timer ${started.timer.id} " +
                if (started.duplicate) "already running" else "scheduled")
        }
        val result = launch(PhoneToolIntentFactory.timer(seconds), "Clock") { "Handed off a ${formatDuration(seconds)} timer request to Clock." }
        // Remembered so "cancel the timer I started" can find it later (Android cannot list Clock's timers).
        if (result is PhoneToolExecution.Success) ClockHistoryStore(appContext).recordTimer(seconds)
        return result
    }

    private suspend fun playYoutube(query: String): PhoneToolExecution {
        if (query.isBlank()) return PhoneToolExecution.Failure("Tell me what to search for on YouTube.")
        if (youtubeApiKey.isBlank()) {
            return launch(PhoneToolIntentFactory.youtubeSearch(query), "YouTube") {
                "Opened YouTube search; no YouTube Data API key is configured."
            }
        }

        val result = youtubeSearch.search(query)

        val videoId = result.videoId
        if (videoId == null) {
            return launch(PhoneToolIntentFactory.youtubeSearch(query), "YouTube") {
                "Opened YouTube search. ${result.fallbackReason ?: "Video search was unavailable."}"
            }
        }

        val appSpec = PhoneToolIntentFactory.youtubeVideo(videoId)
            ?: return launch(PhoneToolIntentFactory.youtubeSearch(query)) {
                "Opened YouTube search because the returned video identifier was invalid."
            }
        val videoIntent = appSpec.toIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!BackgroundHandoff.canOpenAppsNow()) return PhoneToolExecution.Failure(BackgroundHandoff.offer(appContext, videoIntent, "YouTube"))
        return try {
            appContext.startActivity(videoIntent)
            PhoneToolExecution.Success("Opened the selected YouTube video in the YouTube app.")
        } catch (_: ActivityNotFoundException) {
            launch(PhoneToolIntentFactory.youtubeVideoWeb(videoId)) {
                "Opened the selected YouTube video in a browser because the YouTube app is unavailable."
            }
        } catch (_: SecurityException) {
            launch(PhoneToolIntentFactory.youtubeVideoWeb(videoId)) {
                "Opened the selected YouTube video in a browser because the app handoff was blocked."
            }
        }
    }

    private suspend fun playMusic(query: String, requested: MusicApp?): PhoneToolExecution {
        if (query.isBlank()) return PhoneToolExecution.Failure("Tell me what music to play.")
        val installed = MusicApp.entries.filter { isInstalled(it.packageName) }.toSet()
        val plan = MusicPlanner.plan(query, requested, installed, youtubeApiKey.isNotBlank())
        if (requested != null && requested !in installed) {
            return PhoneToolExecution.Failure("${requested.label} isn't installed on this phone.")
        }
        val app = when (plan) {
            is MusicPlan.YouTubeMusicSong -> MusicApp.YOUTUBE_MUSIC
            is MusicPlan.AppSearch -> plan.app
            is MusicPlan.Chooser -> null
        }
        val before = app?.let { playback.snapshot(it.packageName) }
        // In the background no screen can open, but an app that already has a media session can be asked to play.
        if (!BackgroundHandoff.canOpenAppsNow() && app != null && playback.playFromSearch(app.packageName, query)) {
            val outcome = playback.awaitStart(app.packageName, before)
            if (outcome is PlaybackOutcome.Confirmed) {
                return PhoneToolExecution.Success(MusicReplies.reply(MusicPlan.AppSearch(app, query), app, outcome, query, null), MusicReplies.traceOutcome(outcome))
            }
        }
        var searchProblem: String? = null
        val launched = when (plan) {
            is MusicPlan.YouTubeMusicSong -> {
                val found = youtubeSearch.search(query, musicOnly = true)
                val videoId = found.videoId
                val songSpec = videoId?.let { PhoneToolIntentFactory.youtubeMusicSong(it) }
                if (songSpec != null) {
                    launch(songSpec, "YouTube Music") { "" }
                } else {
                    searchProblem = found.fallbackReason
                    launch(PhoneToolIntentFactory.musicSearch(query, MusicApp.YOUTUBE_MUSIC.packageName), "YouTube Music") { "" }
                }
            }
            is MusicPlan.AppSearch -> launch(PhoneToolIntentFactory.musicSearch(query, plan.app.packageName), plan.app.label) { "" }
            is MusicPlan.Chooser -> launch(PhoneToolIntentFactory.musicSearch(query)) { "" }
        }
        if (launched is PhoneToolExecution.Failure) return launched
        // Only an observed media session counts as playback; an opened app or a search does not.
        val outcome = if (app == null) PlaybackOutcome.NotStarted else playback.awaitStart(app.packageName, before)
        val effectivePlan = if (plan is MusicPlan.YouTubeMusicSong && searchProblem != null) MusicPlan.AppSearch(MusicApp.YOUTUBE_MUSIC, query) else plan
        return PhoneToolExecution.Success(
            MusicReplies.reply(effectivePlan, app, outcome, query, searchProblem),
            MusicReplies.traceOutcome(outcome)
        )
    }

    private fun isInstalled(packageName: String): Boolean = try {
        appContext.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    private fun navigate(destination: String): PhoneToolExecution {
        if (destination.isBlank()) return PhoneToolExecution.Failure("Tell me where to navigate.")
        val primary = launch(PhoneToolIntentFactory.navigate(destination), "Maps") {
            "Opened navigation to $destination."
        }
        if (primary is PhoneToolExecution.Success) return primary
        if (!BackgroundHandoff.canOpenAppsNow()) return primary // already parked behind a notification; no second offer
        // Google Maps navigation is unavailable; a geo: search works with most map apps.
        return launch(PhoneToolIntentFactory.mapSearch(destination)) {
            "Opened $destination in a maps app. Start navigation there; turn-by-turn handoff was unavailable."
        }
    }

    private fun launch(spec: PhoneIntentSpec?, what: String = "that app", success: () -> String): PhoneToolExecution {
        if (spec == null) return PhoneToolExecution.Failure("The request is missing required information.")
        val intent = spec.toIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Android silently drops activity launches from the background; never report one as done.
        if (!BackgroundHandoff.canOpenAppsNow()) return PhoneToolExecution.Failure(BackgroundHandoff.offer(appContext, intent, what))
        return try {
            appContext.startActivity(intent)
            PhoneToolExecution.Success(success())
        } catch (_: ActivityNotFoundException) {
            PhoneToolExecution.Failure("No installed app can handle this request.")
        } catch (_: SecurityException) {
            PhoneToolExecution.Failure("Android denied the requested app handoff.")
        }
    }

    private fun PhoneIntentSpec.toIntent(): Intent {
        val spec = this
        return Intent(
            when (spec.action) {
                "android.intent.action.SET_TIMER" -> AlarmClock.ACTION_SET_TIMER
                "android.media.action.MEDIA_PLAY_FROM_SEARCH" -> MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH
                "android.intent.action.CALL" -> Intent.ACTION_CALL
                else -> spec.action
            }
        ).apply {
            spec.data?.let { this.data = Uri.parse(it) }
            spec.packageName?.let { setPackage(it) }
            spec.extras.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putExtra(key, value)
                    is Int -> putExtra(key, value)
                    is String -> putExtra(key, value)
                }
            }
        }
    }

    private fun formatDuration(seconds: Int): String = when {
        seconds % 3_600 == 0 -> "${seconds / 3_600} hour(s)"
        seconds % 60 == 0 -> "${seconds / 60} minute(s)"
        else -> "$seconds second(s)"
    }
}

private class AndroidPhoneHardwareAdapter(private val context: Context) : PhoneHardwareAdapter {
    @Suppress("DEPRECATION")
    override fun sendMediaAction(action: String): PhoneHardwareResult {
        val keyCode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return PhoneHardwareResult.Failure("Choose play, pause, next, or previous.")
        }
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return PhoneHardwareResult.Failure("Android audio controls are unavailable.")
        return try {
            val audioWasPlaying = audio.isMusicActive
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            MediaKeyMessages.afterDispatch(action, audioWasPlaying)
        } catch (_: SecurityException) {
            PhoneHardwareResult.Failure("Android did not allow sending media controls.")
        }
    }

    override fun setVolumePercent(level: Int): PhoneHardwareResult {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return PhoneHardwareResult.Failure("Android audio controls are unavailable.")
        return try {
            val target = (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * level / 100f).roundToInt()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            PhoneHardwareResult.Success("Set music volume to $level%.")
        } catch (_: SecurityException) {
            PhoneHardwareResult.Failure("Android did not allow changing music volume.")
        } catch (_: IllegalStateException) {
            PhoneHardwareResult.Failure("Music volume could not be changed right now.")
        }
    }

    // CameraManager.setTorchMode does not open the camera and needs no CAMERA permission.
    override fun setFlashlight(enabled: Boolean): PhoneHardwareResult {
        val cameraManager = context.getSystemService(CameraManager::class.java)
            ?: return PhoneHardwareResult.Failure("Camera controls are unavailable on this device.")
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return PhoneHardwareResult.Failure("This device has no available flashlight.")
            cameraManager.setTorchMode(cameraId, enabled)
            PhoneHardwareResult.Success(if (enabled) "Turned the flashlight on." else "Turned the flashlight off.")
        } catch (_: SecurityException) {
            PhoneHardwareResult.Failure("Android denied access to the flashlight.")
        } catch (_: CameraAccessException) {
            PhoneHardwareResult.Failure("The camera is busy or the flashlight could not be changed.")
        } catch (_: IllegalArgumentException) {
            PhoneHardwareResult.Failure("The flashlight is unavailable on this camera.")
        }
    }
}

/**
 * Reads other apps' media sessions through JARVIS's notification-listener component (the platform requires
 * notification access for this). Used only to confirm whether playback actually started.
 */
class MediaSessionPlaybackObserver(private val context: Context) {
    fun snapshot(packageName: String): PlaybackSnapshot? {
        if (!JarvisNotificationListener.isAccessEnabled(context)) return null
        return try {
            val manager = context.getSystemService(android.media.session.MediaSessionManager::class.java) ?: return null
            manager.getActiveSessions(JarvisNotificationListener.component(context))
                .filter { it.packageName == packageName }
                .map { controller ->
                    val metadata = controller.metadata
                    PlaybackSnapshot(
                        playing = controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING,
                        title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
                        artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                    )
                }
                .maxByOrNull { if (it.playing) 1 else 0 }
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Asks the app's existing media session to play [query] (the same documented call Android Auto and assistants
     * use). Needs no screen, so it works while JARVIS is in the background. Returns false when the app has no session
     * to ask; the caller must still confirm playback with [awaitStart].
     */
    fun playFromSearch(packageName: String, query: String): Boolean {
        if (!JarvisNotificationListener.isAccessEnabled(context)) return false
        return try {
            val manager = context.getSystemService(android.media.session.MediaSessionManager::class.java) ?: return false
            val controller = manager.getActiveSessions(JarvisNotificationListener.component(context))
                .firstOrNull { it.packageName == packageName } ?: return false
            val actions = controller.playbackState?.actions ?: 0L
            if (actions and android.media.session.PlaybackState.ACTION_PLAY_FROM_SEARCH == 0L) return false
            controller.transportControls.playFromSearch(query, android.os.Bundle())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    suspend fun awaitStart(packageName: String, before: PlaybackSnapshot?, timeoutMs: Long = 10_000): PlaybackOutcome {
        if (!JarvisNotificationListener.isAccessEnabled(context)) return PlaybackOutcome.CannotObserve
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            PlaybackConfirmation.decide(before, snapshot(packageName))?.let { return it }
            kotlinx.coroutines.delay(500)
        }
        return PlaybackOutcome.NotStarted
    }
}
