package com.jarvis.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechOutputTest {
    /** Records what was spoken; each utterance waits until released (or stopped) to mimic real playback. */
    private class FakeEngine(override val label: String, var result: SpeechResult = SpeechResult.Completed) : SpeechEngine {
        val spoken = CopyOnWriteArrayList<String>()
        val stops = CopyOnWriteArrayList<Long>()
        /** When true, each utterance keeps "playing" until stop() is called for it. */
        @Volatile var hold = false
        @Volatile private var current: CompletableDeferred<Unit>? = null
        override var available = true
        override suspend fun speak(text: String, settings: SpeechSettings): SpeechResult {
            spoken += text
            if (hold) CompletableDeferred<Unit>().also { current = it }.await()
            return result
        }
        override fun stop() {
            stops += System.nanoTime()
            current?.complete(Unit)
            current = null
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() = scope.cancel()

    private var settings = SpeechSettings(autoSpeak = true)
    private val system = FakeEngine("Phone voice")
    private val cloud = FakeEngine("ElevenLabs")
    private val controller = SpeechOutputController(scope, system, cloud, settings = { settings })
    private val replies = ReplyChannel("Try: …") { controller.onAssistantReply(it) }

    private suspend fun settle() = delay(150)

    @Test
    fun userInputAndPlaceholdersAreNeverSpoken() = runBlocking {
        replies.progress("Heard: “set a timer”") // the user's own words
        replies.progress("Summarizing…")
        settle()
        assertTrue(system.spoken.isEmpty())
        assertEquals("Summarizing…", replies.display)
    }

    @Test
    fun aCompletedAnswerIsSpokenExactlyOnceAndHistoryIsNeverReplayed() = runBlocking {
        val first = replies.answer("How long should I set the timer for?")
        settle()
        controller.onAssistantReply(first) // recomposition / activity recreation re-delivering the same event
        controller.onAssistantReply(first)
        replies.answer("Timer set.")
        settle()
        assertEquals(listOf("How long should I set the timer for?", "Timer set."), system.spoken)
    }

    @Test
    fun progressiveUpdatesBeforeTheFinalAnswerDoNotRepeatSpeech() = runBlocking {
        replies.progress("Preparing your briefing on this device…")
        replies.progress("Preparing your briefing on this device… (weather)")
        replies.answer("Good morning. Briefing for Mon: Calendar: nothing today.")
        settle()
        assertEquals(listOf("Good morning. Briefing for Mon: Calendar: nothing today."), system.spoken)
    }

    @Test
    fun speakRepliesOffMeansNoAutomaticSpeechAndTurningOnDoesNotSpeakStaleText() = runBlocking {
        settings = settings.copy(autoSpeak = false)
        replies.answer("It is 28°C.")
        settle()
        settings = settings.copy(autoSpeak = true) // toggling on must not read what is already on screen
        settle()
        assertTrue(system.spoken.isEmpty())
        replies.answer("New answer")
        settle()
        assertEquals(listOf("New answer"), system.spoken)
    }

    @Test
    fun aHandsFreeRequestIsAnsweredAloudOnceEvenWhenAutomaticSpeechIsOff() = runBlocking {
        settings = settings.copy(autoSpeak = false)
        var now = 1_000L
        val c = SpeechOutputController(scope, system, cloud, settings = { settings }, clock = { now })
        val ch = ReplyChannel("") { c.onAssistantReply(it) }
        c.speakNextAnswerAloud()
        ch.progress("Thinking…")
        ch.answer("It is 28 degrees.")
        ch.answer("A later, unrelated answer")
        settle()
        assertEquals(listOf("It is 28 degrees."), system.spoken)
    }

    @Test
    fun aHandsFreeRequestExpiresAndStopCancelsIt() = runBlocking {
        settings = settings.copy(autoSpeak = false)
        var now = 1_000L
        val c = SpeechOutputController(scope, system, cloud, settings = { settings }, clock = { now })
        val ch = ReplyChannel("") { c.onAssistantReply(it) }
        c.speakNextAnswerAloud(withinMs = 60_000)
        now += 61_000 // the answer took too long: it no longer belongs to that request
        ch.answer("Expired")
        c.speakNextAnswerAloud()
        c.cancelSpeakNext() // the user pressed Stop
        ch.answer("Cancelled")
        settle()
        assertTrue(system.spoken.isEmpty())
    }

    @Test
    fun aHandsFreeAnswerBuiltFromPrivateContentStillNeverGoesToTheCloudVoice() = runBlocking {
        settings = settings.copy(autoSpeak = false, provider = SpeechProvider.ELEVENLABS)
        val c = SpeechOutputController(scope, system, cloud, settings = { settings })
        val ch = ReplyChannel("") { c.onAssistantReply(it) }
        c.speakNextAnswerAloud()
        ch.answer("You have 2 new messages from Maa.", privateContent = true)
        settle()
        assertEquals(listOf("You have 2 new messages from Maa."), system.spoken)
        assertTrue(cloud.spoken.isEmpty())
    }

    @Test
    fun stopEndsActiveSpeechAndNothingQueuedPlaysLater() = runBlocking {
        system.hold = true
        replies.answer("A long answer that is still playing")
        settle()
        assertEquals(SpeechPhase.SPEAKING, controller.status.value.phase)
        controller.stop()
        settle()
        assertEquals(SpeechPhase.STOPPED, controller.status.value.phase)
        assertTrue(system.stops.isNotEmpty())
        system.hold = false
        settle()
        assertEquals(1, system.spoken.size) // nothing else was spoken after Stop
    }

    @Test
    fun overlappingAnswersOnlyTheLatestKeepsPlaying() = runBlocking {
        system.hold = true
        replies.answer("first answer")
        settle()
        replies.answer("second answer")
        settle()
        assertEquals(listOf("first answer", "second answer"), system.spoken)
        assertTrue("the first utterance was stopped when the second arrived", system.stops.isNotEmpty())
        // A stale id arriving late (e.g. a slow callback) is ignored.
        controller.onAssistantReply(AssistantReply(1, "first answer"))
        settle()
        assertEquals(2, system.spoken.size)
    }

    @Test
    fun cloudFailureFallsBackToPhoneVoiceAndPrivateAnswersNeverGoToTheCloud() = runBlocking {
        settings = settings.copy(provider = SpeechProvider.ELEVENLABS)
        cloud.result = SpeechResult.Failed("ElevenLabs rate limit or concurrency limit (HTTP 429)")
        replies.answer("Public answer")
        settle()
        assertEquals(listOf("Public answer"), cloud.spoken)
        assertEquals(listOf("Public answer"), system.spoken)
        assertTrue(controller.status.value.detail!!.contains("HTTP 429"))

        cloud.result = SpeechResult.Completed
        replies.answer("New messages: Asha — see you at 5", privateContent = true)
        settle()
        assertFalse(cloud.spoken.contains("New messages: Asha — see you at 5"))
        assertTrue(system.spoken.contains("New messages: Asha — see you at 5"))

        cloud.available = false // no key configured → phone voice, no failure
        replies.answer("Another public answer")
        settle()
        assertEquals("Another public answer", system.spoken.last())
    }

    @Test
    fun errorsDoNotLeaveStaleSpeechForLater() = runBlocking {
        system.result = SpeechResult.Failed("phone voice not ready")
        replies.answer("Will fail")
        settle()
        assertEquals(SpeechPhase.FAILED, controller.status.value.phase)
        system.result = SpeechResult.Completed
        settle()
        assertEquals(listOf("Will fail"), system.spoken) // not retried or replayed later
        replies.answer("Next")
        settle()
        assertEquals(listOf("Will fail", "Next"), system.spoken)
    }

    // --- ElevenLabs engine against a fake server ---

    private class BufferSink : PcmSink {
        val bytes = java.io.ByteArrayOutputStream()
        var rate = 0
        var finished = false
        var stopped = false
        override fun start(sampleRateHz: Int) { rate = sampleRateHz }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            assertEquals("writes stay 16-bit aligned", 0, length % 2)
            this.bytes.write(bytes, offset, length)
        }
        override fun finish() { finished = true }
        override fun stop() { stopped = true }
    }

    @Test
    fun elevenLabsStreamsPcmFromTheOfficialEndpointWithOnlyTheAnswerText() = runBlocking {
        MockWebServer().use { server ->
            val audio = ByteArray(9) { it.toByte() } // odd length: the last byte is an incomplete sample
            server.enqueue(MockResponse().setBody(Buffer().write(audio)))
            server.start()
            val sink = BufferSink()
            val tts = ElevenLabsTts({ "test-key" }, { sink }, baseUrl = server.url("/v1/text-to-speech/").toString())
            val result = tts.speak("It is 28 degrees.", SpeechSettings(voiceId = ElevenLabsTts.DEFAULT_VOICE_ID, rate = 1.1f))
            assertEquals(SpeechResult.Completed, result)
            assertEquals(ElevenLabsTts.SAMPLE_RATE_HZ, sink.rate)
            assertEquals(8, sink.bytes.size())
            assertTrue(sink.finished)
            val request = server.takeRequest()
            assertEquals("/v1/text-to-speech/${ElevenLabsTts.DEFAULT_VOICE_ID}/stream", request.requestUrl!!.encodedPath)
            assertEquals("pcm_22050", request.requestUrl!!.queryParameter("output_format"))
            assertEquals("test-key", request.getHeader("xi-api-key"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("It is 28 degrees.", body.getString("text"))
            assertEquals(ElevenLabsTts.MODEL_ID, body.getString("model_id"))
            assertEquals(setOf("text", "model_id", "voice_settings"), body.keys().asSequence().toSet())
        }
    }

    @Test
    fun elevenLabsErrorsMissingKeyAndNetworkFailuresAreReportedNotThrown() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":{"status":"invalid_api_key"}}"""))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":{"status":"quota_exceeded"}}"""))
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.start()
            val tts = ElevenLabsTts(
                { "k" }, { BufferSink() },
                client = okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                baseUrl = server.url("/v1/text-to-speech/").toString()
            )
            val s = SpeechSettings()
            assertEquals(SpeechResult.Failed("ElevenLabs rejected the API key (HTTP 401)"), tts.speak("a", s))
            assertEquals(SpeechResult.Failed("ElevenLabs quota or credits exhausted (HTTP 401)"), tts.speak("a", s))
            assertEquals(SpeechResult.Failed("ElevenLabs rate limit or concurrency limit (HTTP 429)", retryable = true), tts.speak("a", s))
            val network = tts.speak("a", s)
        assertTrue(network.toString(), network is SpeechResult.Failed && network.reason.startsWith("network error"))
            assertEquals(SpeechResult.Failed("no ElevenLabs API key configured"), ElevenLabsTts({ null }, { BufferSink() }).speak("a", s))
            assertFalse(ElevenLabsTts({ " " }, { BufferSink() }).available)
        }
    }

    @Test
    fun voiceIsChosenFromTheCuratedListAndAnythingElseUsesTheDefault() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4)))) }
            server.start()
            val logs = mutableListOf<String>()
            val tts = ElevenLabsTts({ "secret-test-key" }, { BufferSink() }, baseUrl = server.url("/v1/text-to-speech/").toString(), log = { logs += it })
            val brian = ElevenLabsVoices.choices.first { it.name == "Brian" }
            tts.speak("a", SpeechSettings(voiceId = brian.id))
            tts.speak("a", SpeechSettings(voiceId = "../x")) // an old hand-typed ID can never steer the request path
            tts.speak("a", SpeechSettings(voiceId = ""))
            assertEquals("/v1/text-to-speech/${brian.id}/stream", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/v1/text-to-speech/${ElevenLabsTts.DEFAULT_VOICE_ID}/stream", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/v1/text-to-speech/${ElevenLabsTts.DEFAULT_VOICE_ID}/stream", server.takeRequest().requestUrl!!.encodedPath)
            assertTrue(logs.any { it.startsWith("ElevenLabs HTTP 200") })
            assertTrue(logs.any { it.startsWith("ElevenLabs received 4 bytes") })
            assertTrue("diagnostics never contain the key", logs.none { "secret-test-key" in it || "xi-api-key" in it })
        }
    }

    @Test
    fun voiceDirectoryConfirmsEachCuratedVoiceAgainstTheAccountWithoutSpendingSpeechCredits() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val id = request.requestUrl!!.pathSegments.last()
                    return when (id) {
                        ElevenLabsTts.DEFAULT_VOICE_ID -> MockResponse().setBody("""{"voice_id":"$id","name":"George","labels":{"gender":"male","accent":"british","descriptive":"warm"}}""")
                        ElevenLabsVoices.choices[1].id -> MockResponse().setResponseCode(404).setBody("""{"detail":{"status":"voice_not_found"}}""")
                        ElevenLabsVoices.choices[2].id -> MockResponse().setResponseCode(401).setBody("""{"detail":{"status":"invalid_api_key"}}""")
                        else -> MockResponse().setBody("not json")
                    }
                }
            }
            server.start()
            val directory = ElevenLabsVoiceDirectory({ "k" }, baseUrl = server.url("/v1/voices/").toString())
            val result = directory.check()
            assertEquals(VoiceCheck.Available("George", "male · british · warm"), result.getValue(ElevenLabsTts.DEFAULT_VOICE_ID))
            assertEquals(VoiceCheck.Missing, result.getValue(ElevenLabsVoices.choices[1].id))
            assertEquals(VoiceCheck.Unknown("ElevenLabs rejected the API key (HTTP 401)"), result.getValue(ElevenLabsVoices.choices[2].id))
            assertEquals(VoiceCheck.Unknown("unexpected response"), result.getValue(ElevenLabsVoices.choices[3].id))
            assertEquals(ElevenLabsVoices.choices.size, result.size)
            // Metadata reads only: nothing was sent to a text-to-speech path.
            assertTrue(generateSequence { server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS) }.all { "text-to-speech" !in it.path!! && it.method == "GET" })
            val noKey = ElevenLabsVoiceDirectory({ null }).check()
            assertTrue(noKey.values.all { it is VoiceCheck.Unknown })
        }
    }

    @Test
    fun aConnectionDroppedMidAnswerPlaysNothingSoTheWholeAnswerCanFallBack() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(64_000))).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.start()
            val sink = BufferSink()
            val tts = ElevenLabsTts(
                { "k" }, { sink },
                client = okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                baseUrl = server.url("/v1/text-to-speech/").toString()
            )
            val result = tts.speak("A long answer", SpeechSettings())
            assertTrue(result.toString(), result is SpeechResult.Failed && result.retryable)
            assertEquals("no partial audio was played in the ElevenLabs voice", 0, sink.bytes.size())
            assertEquals(0, sink.rate)
        }
    }

    @Test
    fun theSelectedVoiceReadsEveryOrdinaryAnswerIncludingOnesThatUsedMemories() = runBlocking {
        settings = settings.copy(provider = SpeechProvider.ELEVENLABS)
        replies.answer("Your favourite song is Kesariya.") // built with a saved memory: not private any more
        settle()
        replies.answer("It is 28 degrees.")
        settle()
        assertEquals(listOf("Your favourite song is Kesariya.", "It is 28 degrees."), cloud.spoken)
        assertTrue(system.spoken.isEmpty())
    }

    @Test
    fun aBriefCloudFailureIsRetriedOnceBeforeAnyVoiceChange() = runBlocking {
        settings = settings.copy(provider = SpeechProvider.ELEVENLABS)
        val flaky = object : SpeechEngine {
            val attempts = CopyOnWriteArrayList<String>()
            override val label = "ElevenLabs"
            override val available = true
            override suspend fun speak(text: String, settings: SpeechSettings): SpeechResult {
                attempts += text
                return if (attempts.size == 1) SpeechResult.Failed("ElevenLabs rate limit (HTTP 429)", retryable = true) else SpeechResult.Completed
            }
            override fun stop() = Unit
        }
        val c = SpeechOutputController(scope, system, flaky, settings = { settings }, retryDelayMs = 10)
        ReplyChannel("") { c.onAssistantReply(it) }.answer("Hello there.")
        settle()
        assertEquals(2, flaky.attempts.size)
        assertTrue("the phone voice never read any of it", system.spoken.isEmpty())
        assertEquals(SpeechPhase.IDLE, c.status.value.phase)
        assertEquals("ElevenLabs", c.status.value.engine)
    }

    @Test
    fun aPersistentFailureMovesTheWholeAnswerToThePhoneVoiceOnceAndSaysWhy() = runBlocking {
        settings = settings.copy(provider = SpeechProvider.ELEVENLABS)
        cloud.result = SpeechResult.Failed("ElevenLabs rejected the API key (HTTP 401)")
        replies.answer("Complete answer text.")
        settle()
        assertEquals(listOf("Complete answer text."), cloud.spoken) // not retried: a bad key will not fix itself
        assertEquals(listOf("Complete answer text."), system.spoken)
        assertTrue(controller.status.value.detail!!.contains("this answer used the phone voice"))
    }

    @Test
    fun messageContentUsesThePhoneVoiceUnlessTheUserAllowsTheCloudVoiceForIt() = runBlocking {
        settings = settings.copy(provider = SpeechProvider.ELEVENLABS)
        replies.answer("Maa: call me when you're free", privateContent = true)
        settle()
        assertEquals(listOf("Maa: call me when you're free"), system.spoken)
        settings = settings.copy(cloudForPrivate = true)
        replies.answer("Ravi: running late", privateContent = true)
        settle()
        assertEquals(listOf("Ravi: running late"), cloud.spoken)
    }

    @Test
    fun voiceRoutingIsDecidedOncePerAnswer() {
        val eleven = SpeechSettings(provider = SpeechProvider.ELEVENLABS)
        assertEquals(VoiceRouting.Decision.Cloud, VoiceRouting.decide(eleven, cloudAvailable = true, privateContent = false, speakableLength = 120))
        assertTrue(VoiceRouting.decide(eleven, cloudAvailable = false, privateContent = false, speakableLength = 120) is VoiceRouting.Decision.Phone)
        assertTrue(VoiceRouting.decide(eleven, true, false, ElevenLabsTts.MAX_CHARS + 1) is VoiceRouting.Decision.Phone)
        assertEquals(VoiceRouting.Decision.Phone(null), VoiceRouting.decide(SpeechSettings(), true, false, 10))
    }

    @Test
    fun statusNamesTheAnswerSoAFinishedQuestionCanHandOverToListening() = runBlocking {
        system.hold = true
        val answer = replies.answer("What time should the meeting start?")
        settle()
        assertTrue(controller.isSpeaking(answer.id))
        assertEquals(answer.id, controller.status.value.replyId)
        controller.stop()
        settle()
        assertTrue(!controller.isSpeaking(answer.id))
    }

    @Test
    fun spokenTextIsNormalisedButTheWrittenAnswerIsNot() = runBlocking {
        val answer = replies.answer("Sun 18 Oct: 30°C")
        settle()
        assertEquals("Sun 18 Oct: 30°C", answer.text)
        assertTrue(system.spoken.single().contains("30 degrees Celsius"))
    }

    @Test
    fun cancellingTheSpeechJobCancelsTheElevenLabsRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2))).throttleBody(1, 1, java.util.concurrent.TimeUnit.SECONDS))
            server.start()
            val sink = BufferSink()
            val tts = ElevenLabsTts({ "k" }, { sink }, baseUrl = server.url("/v1/text-to-speech/").toString())
            val job = scope.launch { tts.speak("x", SpeechSettings()) }
            delay(300)
            tts.stop()
            withTimeout(3_000) { job.join() }
            assertFalse(sink.finished)
        }
    }
}
