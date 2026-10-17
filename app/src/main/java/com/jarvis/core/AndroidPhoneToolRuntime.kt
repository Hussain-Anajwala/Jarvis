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
    private val youtubeSearch = YouTubeSearchClient(youtubeApiKey, client)
    private val hardwareTools = PhoneHardwareToolExecutor(AndroidPhoneHardwareAdapter(appContext))

    suspend fun execute(step: PlanStep): PhoneToolExecution = when (step.toolName) {
        "set_timer" -> setTimer(step)
        "play_youtube" -> playYoutube(step.parameters["query"].orEmpty())
        "play_music" -> playMusic(step.parameters["query"].orEmpty())
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
        return launch(spec) { "Calling ${contact.displayName}." }
    }

    private fun setTimer(step: PlanStep): PhoneToolExecution {
        val seconds = step.parameters["duration_seconds"]?.toIntOrNull()
            ?: return PhoneToolExecution.Failure("Timer duration must be a positive number of seconds.")
        if (seconds !in 1..86_400) return PhoneToolExecution.Failure("Timer duration must be between 1 second and 24 hours.")
        return launch(PhoneToolIntentFactory.timer(seconds)) { "Handed off a ${formatDuration(seconds)} timer request to Clock." }
    }

    private suspend fun playYoutube(query: String): PhoneToolExecution {
        if (query.isBlank()) return PhoneToolExecution.Failure("Tell me what to search for on YouTube.")
        if (youtubeApiKey.isBlank()) {
            return launch(PhoneToolIntentFactory.youtubeSearch(query)) {
                "Opened YouTube search; no YouTube Data API key is configured."
            }
        }

        val result = youtubeSearch.search(query)

        val videoId = result.videoId
        if (videoId == null) {
            return launch(PhoneToolIntentFactory.youtubeSearch(query)) {
                "Opened YouTube search. ${result.fallbackReason ?: "Video search was unavailable."}"
            }
        }

        val appSpec = PhoneToolIntentFactory.youtubeVideo(videoId)
            ?: return launch(PhoneToolIntentFactory.youtubeSearch(query)) {
                "Opened YouTube search because the returned video identifier was invalid."
            }
        return try {
            appContext.startActivity(appSpec.toIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

    private fun playMusic(query: String): PhoneToolExecution {
        if (query.isBlank()) return PhoneToolExecution.Failure("Tell me what music to search for.")
        return when (val result = launch(PhoneToolIntentFactory.musicSearch(query)) {
            "Sent “$query” to a media app. Playback depends on that app and is not confirmed."
        }) {
            is PhoneToolExecution.Success -> result.copy(traceOutcome = "handed off; playback unconfirmed")
            is PhoneToolExecution.Failure -> result
        }
    }

    private fun navigate(destination: String): PhoneToolExecution {
        if (destination.isBlank()) return PhoneToolExecution.Failure("Tell me where to navigate.")
        val primary = launch(PhoneToolIntentFactory.navigate(destination)) {
            "Opened navigation to $destination."
        }
        if (primary is PhoneToolExecution.Success) return primary
        // Google Maps navigation is unavailable; a geo: search works with most map apps.
        return launch(PhoneToolIntentFactory.mapSearch(destination)) {
            "Opened $destination in a maps app. Start navigation there; turn-by-turn handoff was unavailable."
        }
    }

    private fun launch(spec: PhoneIntentSpec?, success: () -> String): PhoneToolExecution {
        if (spec == null) return PhoneToolExecution.Failure("The request is missing required information.")
        return try {
            appContext.startActivity(spec.toIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
