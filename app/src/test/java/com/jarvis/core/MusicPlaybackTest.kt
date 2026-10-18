package com.jarvis.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlaybackTest {
    private fun step(text: String) = (PhoneCommandParser.parse(text) as PhoneCommandParseResult.Parsed).step

    @Test
    fun songArtistAndProviderAreParsed() {
        assertEquals(PlanStep("play_music", mapOf("query" to "Kesariya", "app" to "spotify")), step("play Kesariya on Spotify"))
        assertEquals(PlanStep("play_music", mapOf("query" to "Arijit Singh", "app" to "youtube_music")), step("play Arijit Singh on YouTube Music"))
        assertEquals(PlanStep("play_music", mapOf("query" to "lofi beats", "app" to "youtube_music")), step("play lofi beats in yt music"))
        assertEquals(PlanStep("play_music", mapOf("query" to "Kesariya by Arijit Singh")), step("play Kesariya by Arijit Singh"))
        assertEquals(PlanStep("play_music", mapOf("query" to "Miles Davis")), step("play music Miles Davis"))
        // A YouTube *video* request is still YouTube; "on youtube music" is not mistaken for it.
        assertEquals(PlanStep("play_youtube", mapOf("query" to "cricket highlights")), step("play cricket highlights on YouTube"))
        assertTrue(PhoneCommandParser.parse("play something on spotify") is PhoneCommandParseResult.Clarification)
        assertTrue(ToolRouter.route(PlanStep("play_music", mapOf("query" to "x", "app" to "spotify")), JarvisCore.AVAILABLE_TOOLS) is ToolRoute.PhoneTool)
        assertEquals(MusicApp.SPOTIFY, MusicApp.fromArgument("Spotify"))
        assertEquals(MusicApp.YOUTUBE_MUSIC, MusicApp.fromArgument("youtube_music"))
        assertNull(MusicApp.fromArgument("winamp"))
    }

    @Test
    fun providerSelectionPrefersTheRequestedInstalledAppAndTheSongPathOnlyWithAKey() {
        val both = setOf(MusicApp.SPOTIFY, MusicApp.YOUTUBE_MUSIC)
        assertEquals(MusicPlan.AppSearch(MusicApp.SPOTIFY, "x"), MusicPlanner.plan("x", MusicApp.SPOTIFY, both, youtubeKeyConfigured = true))
        assertEquals(MusicPlan.YouTubeMusicSong("x"), MusicPlanner.plan("x", null, both, youtubeKeyConfigured = true))
        assertEquals(MusicPlan.AppSearch(MusicApp.YOUTUBE_MUSIC, "x"), MusicPlanner.plan("x", null, both, youtubeKeyConfigured = false))
        assertEquals(MusicPlan.AppSearch(MusicApp.SPOTIFY, "x"), MusicPlanner.plan("x", MusicApp.YOUTUBE_MUSIC, setOf(MusicApp.SPOTIFY), true))
        assertEquals(MusicPlan.Chooser("x"), MusicPlanner.plan("x", null, emptySet(), true))
    }

    @Test
    fun playbackIsConfirmedOnlyByANewPlayingSession() {
        val idle = PlaybackSnapshot(playing = false, title = "Old song", artist = "A")
        val playingNew = PlaybackSnapshot(playing = true, title = "Kesariya", artist = "Arijit Singh")
        assertEquals(PlaybackOutcome.Confirmed("Kesariya", "Arijit Singh"), PlaybackConfirmation.decide(idle, playingNew))
        assertEquals(PlaybackOutcome.Confirmed("Kesariya", "Arijit Singh"), PlaybackConfirmation.decide(null, playingNew))
        // Already playing the same song before the request is not evidence.
        assertNull(PlaybackConfirmation.decide(playingNew, playingNew))
        assertNull(PlaybackConfirmation.decide(idle, idle))
        assertNull(PlaybackConfirmation.decide(idle, null))
        assertNull(PlaybackConfirmation.decide(idle, PlaybackSnapshot(true, null, null)))
    }

    @Test
    fun repliesNeverClaimPlaybackWithoutConfirmation() {
        val search = MusicPlan.AppSearch(MusicApp.SPOTIFY, "Kesariya")
        val notStarted = MusicReplies.reply(search, MusicApp.SPOTIFY, PlaybackOutcome.NotStarted, "Kesariya")
        assertFalse(notStarted.startsWith("Playing"))
        assertTrue(notStarted.contains("playback didn't start"))
        val unobservable = MusicReplies.reply(MusicPlan.YouTubeMusicSong("x"), MusicApp.YOUTUBE_MUSIC, PlaybackOutcome.CannotObserve, "x")
        assertTrue(unobservable.contains("can't confirm playback"))
        assertEquals(
            "Playing “Kesariya” by Arijit Singh in YouTube Music.",
            MusicReplies.reply(MusicPlan.YouTubeMusicSong("x"), MusicApp.YOUTUBE_MUSIC, PlaybackOutcome.Confirmed("Kesariya", "Arijit Singh"), "x")
        )
        assertTrue(MusicReplies.reply(MusicPlan.AppSearch(MusicApp.YOUTUBE_MUSIC, "x"), MusicApp.YOUTUBE_MUSIC, PlaybackOutcome.NotStarted, "x", "YouTube search returned no video.")
            .endsWith("(YouTube search returned no video.)"))
        assertEquals("handed off; playback not started", MusicReplies.traceOutcome(PlaybackOutcome.NotStarted))
    }

    @Test
    fun songLinkAndSearchIntentsAreBuiltSafely() {
        assertEquals("https://music.youtube.com/watch?v=BddP6PYo2gs", PhoneToolIntentFactory.youtubeMusicSong("BddP6PYo2gs")?.data)
        assertEquals(MusicApp.YOUTUBE_MUSIC.packageName, PhoneToolIntentFactory.youtubeMusicSong("BddP6PYo2gs")?.packageName)
        assertNull(PhoneToolIntentFactory.youtubeMusicSong("../evil"))
        val spotify = PhoneToolIntentFactory.musicSearch("Kesariya", MusicApp.SPOTIFY.packageName)!!
        assertEquals("android.media.action.MEDIA_PLAY_FROM_SEARCH", spotify.action)
        assertEquals("com.spotify.music", spotify.packageName)
        assertNull(PhoneToolIntentFactory.musicSearch("   "))
    }

    @Test
    fun musicSearchUsesTheMusicCategoryAndHandlesZeroResultsAndTimeouts() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"items":[{"id":{"videoId":"BddP6PYo2gs"}}]}"""))
            server.enqueue(MockResponse().setBody("""{"items":[]}"""))
            server.enqueue(MockResponse().setResponseCode(403))
            server.start()
            val client = YouTubeSearchClient("k", endpoint = server.url("/search").toString())
            assertEquals("BddP6PYo2gs", client.search("Kesariya", musicOnly = true).videoId)
            assertEquals("10", server.takeRequest().requestUrl!!.queryParameter("videoCategoryId"))
            assertEquals(YouTubeSearchDecision(null, "YouTube search returned no video."), client.search("zzz", musicOnly = true))
            assertNull(client.search("x", musicOnly = true).videoId)
        }
        val timeout = YouTubeSearchClient(
            "k",
            client = okhttp3.OkHttpClient.Builder().readTimeout(200, java.util.concurrent.TimeUnit.MILLISECONDS).build(),
            endpoint = MockWebServer().also {
                it.enqueue(MockResponse().setBody("{}").setHeadersDelay(2, java.util.concurrent.TimeUnit.SECONDS))
                it.start()
            }.url("/search").toString()
        )
        assertEquals(YouTubeSearchDecision(null, "YouTube search could not connect."), timeout.search("x", musicOnly = true))
    }
}
