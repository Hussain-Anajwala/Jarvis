package com.jarvis.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class PhoneToolsTest {
    @Test
    fun registersAllM2ToolsWithStaticRiskAndPermissionMetadata() {
        val expected = setOf(
            "set_timer", "set_alarm", "play_youtube", "play_music", "media_control",
            "set_volume", "flashlight", "call_contact", "navigate"
        )
        val definitions = ReasoningToolSchema.forNames(expected.toList()).associateBy { it.name }

        assertEquals(expected, definitions.keys)
        assertEquals("medium", definitions.getValue("call_contact").riskLevel)
        assertTrue("CALL_PHONE" in definitions.getValue("call_contact").requiredPermissions)
        assertTrue("READ_CONTACTS" in definitions.getValue("call_contact").requiredPermissions)
        assertTrue(definitions.getValue("flashlight").requiredPermissions.isEmpty())
        assertTrue("SET_ALARM" in definitions.getValue("set_timer").requiredPermissions)
        assertEquals(expected.size, ReasoningToolSchema.toOpenAiJson(expected.toList()).length())
        assertEquals(expected.size, ReasoningToolSchema.toGeminiJson(expected.toList()).length())
        assertTrue(ToolRiskGate.requiresConfirmation("call_contact"))
        assertTrue(ToolRiskGate.requiresConfirmation("calendar.cancel"))
        assertFalse(ToolRiskGate.requiresConfirmation("set_timer"))
    }

    @Test
    fun validatesRequiredArgumentsAndRanges() {
        assertNull(ReasoningToolSchema.validate(PlanStep("set_timer", mapOf("duration_seconds" to "60"))))
        assertNotNull(ReasoningToolSchema.validate(PlanStep("set_timer", mapOf("duration_seconds" to "0"))))
        assertNotNull(ReasoningToolSchema.validate(PlanStep("set_timer", mapOf("duration_seconds" to "86401"))))
        assertNotNull(ReasoningToolSchema.validate(PlanStep("set_volume", mapOf("level" to "101"))))
        assertNull(ReasoningToolSchema.validate(PlanStep("set_volume", mapOf("level" to "0"))))
        assertNotNull(ReasoningToolSchema.validate(PlanStep("media_control", mapOf("action" to "rewind"))))
        assertNotNull(ReasoningToolSchema.validate(PlanStep("flashlight", mapOf("enabled" to "yes"))))
        assertNotNull(ReasoningToolSchema.validate(PlanStep("navigate", emptyMap())))
        assertNotNull(ReasoningToolSchema.validate(PlanStep("set_timer", mapOf("duration_seconds" to "5", "extra" to "x"))))
    }

    @Test
    fun offlineParserBuildsPhoneToolRequestsAndClarifiesMissingValues() {
        assertEquals(
            PlanStep("set_timer", mapOf("duration_seconds" to "300")),
            parsed("Set a timer for 5 minutes")
        )
        assertEquals(
            PlanStep("set_alarm", mapOf("hour" to "7", "minute" to "0", "label" to "JARVIS alarm")),
            parsed("Set an alarm for 7 AM")
        )
        assertEquals(PlanStep("play_youtube", mapOf("query" to "weather forecast")), parsed("Play weather forecast on YouTube"))
        assertEquals(PlanStep("play_music", mapOf("query" to "Miles Davis")), parsed("Play music Miles Davis"))
        assertEquals(PlanStep("media_control", mapOf("action" to "next")), parsed("skip song"))
        assertEquals(PlanStep("set_volume", mapOf("level" to "35")), parsed("set volume to 35%"))
        assertEquals(PlanStep("flashlight", mapOf("enabled" to "true")), parsed("turn on flashlight"))
        assertEquals(PlanStep("call_contact", mapOf("name" to "Asha")), parsed("call Asha"))
        assertEquals(PlanStep("navigate", mapOf("destination" to "Dadar station")), parsed("navigate to Dadar station"))
        assertEquals(
            PhoneCommandParseResult.Clarification("How long should I set the timer for?"),
            PhoneCommandParser.parse("set a timer")
        )
        assertEquals(
            PhoneCommandParseResult.Clarification("Should I turn the flashlight on or off?"),
            PhoneCommandParser.parse("flashlight")
        )
        assertEquals(
            PhoneCommandParseResult.Clarification("Who should I call?"),
            PhoneCommandParser.parse("call")
        )
        assertEquals(
            PhoneCommandParseResult.Clarification("Where would you like to navigate?"),
            PhoneCommandParser.parse("navigate to")
        )
    }

    @Test
    fun intentFactoryConstructsClockMediaCallAndNavigationRequests() {
        val timer = PhoneToolIntentFactory.timer(90)
        assertEquals("android.intent.action.SET_TIMER", timer?.action)
        assertEquals(90, timer?.extras?.get("android.intent.extra.alarm.LENGTH"))
        assertEquals(false, timer?.extras?.get("android.intent.extra.alarm.SKIP_UI"))

        val alarm = PhoneToolIntentFactory.alarm(7, 5, "wake up")
        assertEquals("android.intent.action.SET_ALARM", alarm?.action)
        assertEquals(7, alarm?.extras?.get("android.intent.extra.alarm.HOUR"))

        val music = PhoneToolIntentFactory.musicSearch("Miles Davis")
        assertEquals("android.media.action.MEDIA_PLAY_FROM_SEARCH", music?.action)
        assertEquals("Miles Davis", music?.extras?.get("query"))
        assertEquals("https://www.youtube.com/results?search_query=hello%20world", PhoneToolIntentFactory.youtubeSearch("hello world")?.data)
        assertEquals("vnd.youtube:AbCd_123456", PhoneToolIntentFactory.youtubeVideo("AbCd_123456")?.data)
        assertEquals("tel:+14155550123", PhoneToolIntentFactory.call("+14155550123")?.data)
        assertEquals("google.navigation:q=Central%20Park", PhoneToolIntentFactory.navigate("Central Park")?.data)
        assertNull(PhoneToolIntentFactory.timer(0))
        assertNull(PhoneToolIntentFactory.youtubeVideo("bad id"))
    }

    @Test
    fun callResolutionStopsForUnknownOrAmbiguousAndRequiresConfirmation() {
        assertTrue(CallContactSafetyGate.afterResolution(ContactResolution.NotFound) is CallContactDecision.Failure)
        assertTrue(CallContactSafetyGate.afterResolution(ContactResolution.Ambiguous(listOf(ResolvedContact("A", "1"), ResolvedContact("A", "2")))) is CallContactDecision.Choose)
        assertTrue(CallContactSafetyGate.afterResolution(ContactResolution.QueryFailed) is CallContactDecision.Failure)

        val contact = ResolvedContact("Asha Rao", "+14155550123")
        val resolved = CallContactSafetyGate.afterResolution(ContactResolution.Found(contact))
        assertEquals(CallContactDecision.NeedsConfirmation(contact), resolved)
        assertEquals(
            CallContactDecision.Failure("Call cancelled."),
            CallContactSafetyGate.afterConfirmation(contact, confirmed = false, callPermissionGranted = true)
        )
        assertEquals(
            CallContactDecision.NeedsPermission,
            CallContactSafetyGate.afterConfirmation(contact, confirmed = true, callPermissionGranted = false)
        )
        assertEquals(
            CallContactDecision.Ready(contact),
            CallContactSafetyGate.afterConfirmation(contact, confirmed = true, callPermissionGranted = true)
        )
    }

    @Test
    fun hardwareFailuresAndInvalidValuesAreRecoverable() {
        val executor = PhoneHardwareToolExecutor(
            object : PhoneHardwareAdapter {
                override fun sendMediaAction(action: String) =
                    PhoneHardwareResult.Failure("No active media session accepted the $action command.")
                override fun setVolumePercent(level: Int) =
                    PhoneHardwareResult.Failure("Android audio controls are unavailable.")
                override fun setFlashlight(enabled: Boolean) =
                    PhoneHardwareResult.Failure("This device has no available flashlight.")
            }
        )

        assertEquals(
            PhoneToolExecution.Failure("No active media session accepted the pause command."),
            executor.execute(PlanStep("media_control", mapOf("action" to "pause")))
        )
        assertEquals(
            PhoneToolExecution.Failure("Android audio controls are unavailable."),
            executor.execute(PlanStep("set_volume", mapOf("level" to "50")))
        )
        assertEquals(
            PhoneToolExecution.Failure("This device has no available flashlight."),
            executor.execute(PlanStep("flashlight", mapOf("enabled" to "true")))
        )
        assertTrue(executor.execute(PlanStep("set_volume", mapOf("level" to "500"))) is PhoneToolExecution.Failure)
    }

    @Test
    fun youtubeWithoutKeyAndQuotaFailuresChooseSearchFallback() {
        assertEquals(
            YouTubeSearchDecision(null, "YouTube API key is not configured."),
            YouTubeSearchPolicy.decide(false, null, null)
        )
        assertEquals(
            YouTubeSearchDecision(null, "YouTube search quota or access is unavailable (HTTP 403)."),
            YouTubeSearchPolicy.decide(true, 403, null)
        )
        assertEquals(
            YouTubeSearchDecision(null, "YouTube search quota or access is unavailable (HTTP 429)."),
            YouTubeSearchPolicy.decide(true, 429, null)
        )
        assertEquals(YouTubeSearchDecision("video123", null), YouTubeSearchPolicy.decide(true, 200, "video123"))
    }

    @Test
    fun youtubeSearchClientUsesConfiguredApiAndHandlesQuotaResponses() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[{"id":{"videoId":"abc123_xyz"}}]}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"quota"}}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"quota"}}"""))
        server.start()
        try {
            val client = YouTubeSearchClient(
                apiKey = "test-key",
                endpoint = server.url("/youtube/v3/search").toString()
            )
            assertEquals(YouTubeSearchDecision("abc123_xyz", null), client.search("cats"))
            val request = server.takeRequest()
            assertEquals("cats", request.requestUrl?.queryParameter("q"))
            assertEquals("test-key", request.requestUrl?.queryParameter("key"))
            assertEquals(
                YouTubeSearchDecision(null, "YouTube search quota or access is unavailable (HTTP 403)."),
                client.search("dogs")
            )
            assertEquals(
                YouTubeSearchDecision(null, "YouTube search quota or access is unavailable (HTTP 429)."),
                client.search("birds")
            )
            val requestsBeforeMissingKey = server.requestCount
            assertEquals(
                YouTubeSearchDecision(null, "YouTube API key is not configured."),
                YouTubeSearchClient("", endpoint = server.url("/youtube/v3/search").toString()).search("fish")
            )
            assertEquals(requestsBeforeMissingKey, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun phoneRequestCodecRejectsMalformedOrInvalidModelArguments() {
        val step = PlanStep("set_volume", mapOf("level" to "40"))
        assertEquals(step, PhoneToolRequestCodec.decode(PhoneToolRequestCodec.encode(step)))
        assertNull(PhoneToolRequestCodec.decode("{not-json"))
        assertNull(PhoneToolRequestCodec.decode("""{"name":"set_volume","parameters":{"level":"101"}}"""))
        assertNull(PhoneToolRequestCodec.decode("""{"name":"set_volume","parameters":{"level":40}}"""))
    }

    private fun parsed(text: String): PlanStep =
        (PhoneCommandParser.parse(text) as PhoneCommandParseResult.Parsed).step
}
