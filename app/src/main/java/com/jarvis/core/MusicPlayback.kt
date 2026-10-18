package com.jarvis.core

/** Music apps JARVIS knows how to hand a request to. */
enum class MusicApp(val packageName: String, val label: String) {
    YOUTUBE_MUSIC("com.google.android.apps.youtube.music", "YouTube Music"),
    SPOTIFY("com.spotify.music", "Spotify");

    companion object {
        fun fromArgument(value: String?): MusicApp? = when (value?.lowercase()?.replace(Regex("[^a-z]"), "")) {
            "spotify" -> SPOTIFY
            "youtubemusic", "ytmusic", "youtube" -> YOUTUBE_MUSIC
            else -> null
        }
    }
}

/** How a request will be handed off, chosen before anything is launched. */
sealed interface MusicPlan {
    /** Open one specific song (found by search) in YouTube Music: the path that actually starts playback. */
    data class YouTubeMusicSong(val query: String) : MusicPlan
    /** Ask an app to search; it may or may not start playing (Spotify Free only shows results). */
    data class AppSearch(val app: MusicApp, val query: String) : MusicPlan
    /** No supported music app: let Android show its chooser. */
    data class Chooser(val query: String) : MusicPlan
}

object MusicPlanner {
    fun plan(query: String, requested: MusicApp?, installed: Set<MusicApp>, youtubeKeyConfigured: Boolean): MusicPlan {
        val app = requested?.takeIf { it in installed }
            ?: MusicApp.YOUTUBE_MUSIC.takeIf { it in installed }
            ?: MusicApp.SPOTIFY.takeIf { it in installed }
            ?: return MusicPlan.Chooser(query)
        return if (app == MusicApp.YOUTUBE_MUSIC && youtubeKeyConfigured) MusicPlan.YouTubeMusicSong(query)
        else MusicPlan.AppSearch(app, query)
    }
}

/** One observation of an app's media session (from MediaSessionManager). */
data class PlaybackSnapshot(val playing: Boolean, val title: String?, val artist: String?)

sealed interface PlaybackOutcome {
    data class Confirmed(val title: String, val artist: String?) : PlaybackOutcome
    /** The app was observed but did not start something new. */
    data object NotStarted : PlaybackOutcome
    /** JARVIS cannot see media sessions (notification access off), so it cannot know. */
    data object CannotObserve : PlaybackOutcome
}

object PlaybackConfirmation {
    /**
     * Playback counts as started only if, after the handoff, the app is PLAYING and either it was not playing before
     * or the title changed. A song that was already playing is not evidence for this request.
     */
    fun decide(before: PlaybackSnapshot?, after: PlaybackSnapshot?): PlaybackOutcome? {
        if (after == null || !after.playing || after.title.isNullOrBlank()) return null
        val wasSame = before?.playing == true && before.title == after.title
        return if (wasSame) null else PlaybackOutcome.Confirmed(after.title, after.artist)
    }
}

object MusicReplies {
    fun reply(plan: MusicPlan, app: MusicApp?, outcome: PlaybackOutcome, query: String, searchProblem: String? = null): String {
        val appLabel = app?.label ?: "a music app"
        return when (outcome) {
            is PlaybackOutcome.Confirmed ->
                "Playing “${outcome.title}”${outcome.artist?.takeIf(String::isNotBlank)?.let { " by $it" }.orEmpty()} in $appLabel."
            PlaybackOutcome.NotStarted -> when (plan) {
                is MusicPlan.AppSearch -> {
                    val base = if (plan.app == MusicApp.SPOTIFY) {
                        "Opened Spotify with a search for “$query”, but playback didn't start. Tap a result to play it (on Spotify Free, apps can't start a specific track)."
                    } else {
                        "Opened $appLabel with a search for “$query”, but playback didn't start. Tap a result to play it."
                    }
                    base + searchProblem?.let { " ($it)" }.orEmpty()
                }
                is MusicPlan.YouTubeMusicSong -> "Opened “$query” in YouTube Music, but playback was not confirmed. Check the app."
                is MusicPlan.Chooser -> "Sent “$query” to the app you picked; playback was not confirmed."
            }
            PlaybackOutcome.CannotObserve ->
                "Opened “$query” in $appLabel. I can't confirm playback because Notification access is off (Permissions → Notification access lets me check)."
        }
    }

    fun traceOutcome(outcome: PlaybackOutcome): String = when (outcome) {
        is PlaybackOutcome.Confirmed -> "playback confirmed by media session"
        PlaybackOutcome.NotStarted -> "handed off; playback not started"
        PlaybackOutcome.CannotObserve -> "handed off; playback not observable"
    }
}
