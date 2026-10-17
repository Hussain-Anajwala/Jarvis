package com.jarvis.core

import android.Manifest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M2SafetyRoutingTest {
    private val tools = JarvisCore.AVAILABLE_TOOLS

    // --- Cancellation-first and local/LLM routing ---

    @Test
    fun cancellationPhrasesNeverBecomePhoneActions() = runBlocking {
        for (text in listOf("cancel my meeting", "call off my meeting", "Call off the meeting tomorrow", "remove my plan")) {
            assertEquals(text, PhoneCommandParseResult.NotPhoneCommand, PhoneCommandParser.parse(text))
            assertFalse(text, ToolRouter.usesLocalPhoneParser(text))
            val response = OfflineRuleProvider().reason(ReasoningRequest(text, tools))
            assertEquals(text, "calendar.cancel", response.steps.single().toolName)
        }
    }

    @Test
    fun vaguePlaceholdersAskInsteadOfActing() {
        for (text in listOf("Play something on YouTube", "Take me there", "call someone")) {
            assertTrue(text, PhoneCommandParser.parse(text) is PhoneCommandParseResult.Clarification)
        }
        assertTrue((PhoneCommandParser.parse("Set an alarm") as PhoneCommandParseResult.Clarification).message.contains("?"))
    }

    @Test
    fun negatedOrNonPlanDeletionIsNotACancellation() = runBlocking {
        for (text in listOf("Don't cancel anything, just tell me my plans", "Delete all my contacts", "remove the alarm")) {
            val response = OfflineRuleProvider().reason(ReasoningRequest(text, tools))
            assertTrue(text, response.steps.none { it.toolName == "calendar.cancel" })
        }
    }

    @Test
    fun recognisedPhoneCommandsStayLocalAndOthersUseProviderChain() {
        assertTrue(ToolRouter.usesLocalPhoneParser("set a timer for 5 minutes"))
        assertTrue(ToolRouter.usesLocalPhoneParser("set a timer")) // local clarification
        assertTrue(ToolRouter.usesLocalPhoneParser("call Asha"))
        assertTrue(ToolRouter.usesLocalPhoneParser("play my favorite song"))
        assertFalse(ToolRouter.usesLocalPhoneParser("I have a meeting tomorrow"))
        assertFalse(ToolRouter.usesLocalPhoneParser("could you put on something relaxing"))
        assertFalse(ToolRouter.usesLocalPhoneParser("message Maa that I will call soon"))
    }

    @Test
    fun everyAdvertisedToolHasASchemaAndAlarmHasOneTool() {
        assertEquals(tools.size, ReasoningToolSchema.forNames(tools).size)
        assertEquals(tools.size, ReasoningToolSchema.toOpenAiJson(tools).length())
        assertEquals(tools.size, ReasoningToolSchema.toGeminiJson(tools).length())
        assertFalse("alarm.handoff" in tools)
        assertTrue("set_alarm" in tools)
    }

    @Test
    fun routerAppliesAvailabilityValidationAndRiskGate() {
        assertEquals(ToolRoute.ConfirmCancel, ToolRouter.route(PlanStep("calendar.cancel", emptyMap()), tools))
        val call = PlanStep("call_contact", mapOf("name" to "Asha"))
        assertEquals(ToolRoute.PhoneTool(call), ToolRouter.route(call, tools))
        assertEquals(
            ToolRoute.DraftCommunication("Maa", "hi"),
            ToolRouter.route(PlanStep("communication.draft", mapOf("recipient" to "Maa", "body" to "hi")), tools)
        )
        assertEquals(
            ToolRoute.AlarmHandoff("7", "0", "JARVIS alarm"),
            ToolRouter.route(PlanStep("set_alarm", mapOf("hour" to "7", "minute" to "0", "label" to "JARVIS alarm")), tools)
        )
        // Medium risk without a confirmation UI is refused even when offered to the model.
        assertTrue(ToolRouter.route(PlanStep("reminder.cancel", emptyMap()), tools + "reminder.cancel") is ToolRoute.Reject)
        // Unregistered, unavailable, malformed, and extra-argument calls are refused.
        assertTrue(ToolRouter.route(PlanStep("send_sms", mapOf("to" to "x")), tools + "send_sms") is ToolRoute.Reject)
        assertTrue(ToolRouter.route(PlanStep("alarm.handoff", mapOf("hour" to "7", "minute" to "0", "label" to "x")), tools) is ToolRoute.Reject)
        assertTrue(ToolRouter.route(PlanStep("set_volume", mapOf("level" to "250")), tools) is ToolRoute.Reject)
        assertTrue(ToolRouter.route(PlanStep("call_contact", mapOf("name" to "Asha", "number" to "123")), tools) is ToolRoute.Reject)
        assertTrue(ToolRouter.route(PlanStep("call_contact", mapOf("name" to " ")), tools) is ToolRoute.Reject)
        assertEquals(
            ToolRoute.FavoriteSong(PlanStep("play_favorite_song", emptyMap())),
            ToolRouter.route(PlanStep("play_favorite_song", emptyMap()), tools)
        )
    }

    @Test
    fun offlineAlarmUsesSetAlarmTool() = runBlocking {
        val step = OfflineRuleProvider().reason(ReasoningRequest("Set an alarm for 6:30 PM", tools)).steps.single()
        assertEquals(PlanStep("set_alarm", mapOf("hour" to "18", "minute" to "30", "label" to "JARVIS alarm")), step)
        assertEquals(
            PhoneCommandParseResult.Clarification("What time should I set the alarm for? Please include AM or PM."),
            PhoneCommandParser.parse("set an alarm for 7")
        )
    }

    // --- Favorite song (local key-value preference) ---

    @Test
    fun favoriteSongIsParsedSavedPlayedAndForgottenLocally() {
        val store = InMemoryPersonalFactsStore()
        assertEquals(
            PlanStep("set_favorite_song", mapOf("song" to "Tum Hi Ho")),
            (PhoneCommandParser.parse("My favourite song is \"Tum Hi Ho\"") as PhoneCommandParseResult.Parsed).step
        )
        assertEquals(
            PlanStep("set_favorite_song", mapOf("song" to "Kesariya")),
            (PhoneCommandParser.parse("remember that my favorite song is Kesariya") as PhoneCommandParseResult.Parsed).step
        )
        val play = PlanStep("play_favorite_song", emptyMap())
        assertTrue((FavoriteSongTools.handle(play, store) as FavoriteSongOutcome.Reply).message.contains("don't know"))

        FavoriteSongTools.handle(PlanStep("set_favorite_song", mapOf("song" to "Kesariya")), store)
        assertEquals("Kesariya", store.get(PersonalFactsStore.FAVORITE_SONG))
        assertEquals(
            FavoriteSongOutcome.PlayMusic(PlanStep("play_music", mapOf("query" to "Kesariya"))),
            FavoriteSongTools.handle(play, store)
        )
        assertTrue((FavoriteSongTools.handle(PlanStep("get_favorite_song", emptyMap()), store) as FavoriteSongOutcome.Reply).message.contains("Kesariya"))

        FavoriteSongTools.handle(PlanStep("set_favorite_song", mapOf("song" to "x".repeat(201))), store)
        assertEquals("Kesariya", store.get(PersonalFactsStore.FAVORITE_SONG))

        FavoriteSongTools.handle(PlanStep("clear_favorite_song", emptyMap()), store)
        assertNull(store.get(PersonalFactsStore.FAVORITE_SONG))
        assertEquals(
            PlanStep("play_favorite_song", emptyMap()),
            (PhoneCommandParser.parse("play my favorite song") as PhoneCommandParseResult.Parsed).step
        )
    }

    // --- Call confirmation and permission-denial state machine ---

    private class FakeHost(
        val granted: MutableSet<String> = mutableSetOf(),
        var resolution: ContactResolution = ContactResolution.NotFound,
        var throwOnResolve: Boolean = false
    ) : PhoneActionHost {
        val calls = mutableListOf<ResolvedContact>()
        val executed = mutableListOf<PlanStep>()
        val outcomes = mutableListOf<Pair<String, String>>()
        var resolveCount = 0
        override fun isGranted(permission: String) = permission in granted
        override suspend fun resolveContact(name: String): ContactResolution {
            resolveCount++
            if (throwOnResolve) throw SecurityException("denied")
            return resolution
        }
        override suspend fun execute(step: PlanStep): PhoneToolExecution {
            executed += step
            return PhoneToolExecution.Success("done")
        }
        override suspend fun callConfirmedContact(contact: ResolvedContact): PhoneToolExecution {
            calls += contact
            return PhoneToolExecution.Success("Calling ${contact.displayName}.")
        }
        override suspend fun recordOutcome(toolName: String, outcome: String) {
            outcomes += toolName to outcome
        }
    }

    private val asha = ResolvedContact("Asha Rao", "+14155550123")
    private val callAsha = PlanStep("call_contact", mapOf("name" to "Asha"))

    @Test
    fun deniedContactsPermissionNeverResolvesOrCalls() = runBlocking {
        val host = FakeHost(resolution = ContactResolution.Found(asha))
        val coordinator = PhoneActionCoordinator(host)
        coordinator.handle(callAsha)
        assertEquals(Manifest.permission.READ_CONTACTS, coordinator.permissionRequest)
        coordinator.onPermissionResult(Manifest.permission.READ_CONTACTS, granted = false)
        assertEquals(0, host.resolveCount)
        assertTrue(host.calls.isEmpty())
        assertNull(coordinator.callConfirmationContact)
        assertTrue(coordinator.reply!!.contains("No call was placed"))
    }

    @Test
    fun resolvedContactRequiresExplicitConfirmationAndCancelPlacesNoCall() = runBlocking {
        val host = FakeHost(mutableSetOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), ContactResolution.Found(asha))
        val coordinator = PhoneActionCoordinator(host)
        coordinator.handle(callAsha)
        assertEquals(asha, coordinator.callConfirmationContact)
        assertTrue(host.calls.isEmpty())
        coordinator.dismissCall()
        assertTrue(host.calls.isEmpty())
        assertEquals("Call cancelled. No call was placed.", coordinator.reply)
        // Confirming after the dialog closed does nothing.
        coordinator.confirmCall()
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun confirmationThenPhonePermissionDenialPlacesNoCall() = runBlocking {
        val host = FakeHost(mutableSetOf(Manifest.permission.READ_CONTACTS), ContactResolution.Found(asha))
        val coordinator = PhoneActionCoordinator(host)
        coordinator.handle(callAsha)
        coordinator.confirmCall()
        assertEquals(Manifest.permission.CALL_PHONE, coordinator.permissionRequest)
        assertTrue(host.calls.isEmpty())
        coordinator.onPermissionResult(Manifest.permission.CALL_PHONE, granted = false)
        assertTrue(host.calls.isEmpty())
        assertTrue(host.outcomes.contains("call_contact" to "phone permission denied"))
    }

    @Test
    fun confirmationThenPermissionGrantCallsTheShownContactOnce() = runBlocking {
        val host = FakeHost(mutableSetOf(Manifest.permission.READ_CONTACTS), ContactResolution.Found(asha))
        val coordinator = PhoneActionCoordinator(host)
        coordinator.handle(callAsha)
        coordinator.confirmCall()
        host.granted += Manifest.permission.CALL_PHONE
        coordinator.onPermissionResult(Manifest.permission.CALL_PHONE, granted = true)
        assertEquals(listOf(asha), host.calls)
        // A repeated or unsolicited permission result cannot place a second call.
        coordinator.onPermissionResult(Manifest.permission.CALL_PHONE, granted = true)
        assertEquals(1, host.calls.size)
    }

    @Test
    fun unknownAmbiguousOrUnreadableContactsStopWithoutConfirmation(): Unit = runBlocking {
        for (resolution in listOf(ContactResolution.NotFound, ContactResolution.MultipleMatches, ContactResolution.QueryFailed)) {
            val host = FakeHost(mutableSetOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), resolution)
            val coordinator = PhoneActionCoordinator(host)
            coordinator.handle(callAsha)
            assertNull(coordinator.callConfirmationContact)
            coordinator.confirmCall()
            assertTrue(host.calls.isEmpty())
        }
        val throwing = FakeHost(mutableSetOf(Manifest.permission.READ_CONTACTS), throwOnResolve = true)
        PhoneActionCoordinator(throwing).apply {
            handle(callAsha)
            assertNull(callConfirmationContact)
            assertEquals("Contacts could not be read. Check Contacts permission.", reply)
        }
    }

    @Test
    fun invalidStepsAreRefusedAndFlashlightNeedsNoPermission() = runBlocking {
        val host = FakeHost()
        val coordinator = PhoneActionCoordinator(host)
        coordinator.handle(PlanStep("set_volume", mapOf("level" to "101")))
        assertTrue(host.executed.isEmpty())
        coordinator.handle(PlanStep("flashlight", mapOf("enabled" to "true")))
        assertNull(coordinator.permissionRequest)
        assertEquals(listOf(PlanStep("flashlight", mapOf("enabled" to "true"))), host.executed)
        assertTrue(ReasoningToolSchema.definition("flashlight")!!.requiredPermissions.isEmpty())
    }

    // --- Honest media outcomes and navigation fallback ---

    @Test
    fun mediaKeyMessagesNeverClaimConfirmedPlayback() {
        val idle = MediaKeyMessages.afterDispatch("pause", audioWasPlaying = false)
        assertTrue(idle.message.contains("may have no effect"))
        val active = MediaKeyMessages.afterDispatch("next", audioWasPlaying = true)
        assertTrue(active.message.contains("cannot confirm"))
        assertEquals("media key sent; result unconfirmed", active.traceOutcome)
        assertTrue(MediaKeyMessages.afterDispatch("play", audioWasPlaying = false).message.contains("cannot confirm"))
    }

    @Test
    fun navigationHasGeoFallback() {
        assertEquals("geo:0,0?q=Dadar%20station", PhoneToolIntentFactory.mapSearch("Dadar station")?.data)
        assertNull(PhoneToolIntentFactory.mapSearch("  "))
    }

    // --- YouTube API failures ---

    @Test
    fun youtubeNetworkFailureAndMalformedSuccessFallBackToSearch() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not json</html>"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[{"id":{"kind":"youtube#channel"}}]}"""))
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        try {
            val client = YouTubeSearchClient("test-key", endpoint = server.url("/search").toString())
            assertEquals(YouTubeSearchDecision(null, "YouTube search could not connect."), client.search("a"))
            assertEquals(YouTubeSearchDecision(null, "YouTube search returned no video."), client.search("b"))
            assertEquals(YouTubeSearchDecision(null, "YouTube search returned no video."), client.search("c"))
            assertEquals(YouTubeSearchDecision(null, "YouTube search returned no video."), client.search("d"))
            assertEquals(YouTubeSearchDecision(null, "YouTube search failed (HTTP 500)."), client.search("e"))
        } finally {
            server.shutdown()
        }
        // An untrusted video id that is not a YouTube id is never turned into an intent.
        assertNull(PhoneToolIntentFactory.youtubeVideo("../../etc"))
    }
}
