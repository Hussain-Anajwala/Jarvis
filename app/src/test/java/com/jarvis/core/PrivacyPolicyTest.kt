package com.jarvis.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 adversarial tests. The real provider chain (Groq → Gemini → offline) talks to local mock servers that
 * record every request body, so the tests check exactly what would have left the phone. All secrets are synthetic
 * sentinels; assertion messages never print a payload.
 */
class PrivacyPolicyTest {
    private val pin = "739204"
    private val otp = "552918"
    private val sentinels = listOf(pin, otp, "hunter2x9", "4111111111111111")

    /** Records bodies; Groq lists one model and then fails every chat call, so the chain falls back to Gemini. */
    private class Recorder(private val groq: Boolean) : Dispatcher() {
        val bodies = mutableListOf<String>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            bodies += request.body.readUtf8()
            if (groq && request.method == "GET") return MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"m1","active":true}]}""")
            return MockResponse().setResponseCode(503).setBody("""{"error":{"message":"busy"}}""")
        }
    }

    private class Harness : AutoCloseable {
        val groqServer = MockWebServer()
        val geminiServer = MockWebServer()
        val groqLog = Recorder(groq = true).also { groqServer.dispatcher = it }
        val geminiLog = Recorder(groq = false).also { geminiServer.dispatcher = it }
        val audits = mutableListOf<String>()
        var settings = PrivacySettings()
        val chain = ResilientReasoningProvider(
            listOf(
                ConfiguredReasoningProvider("groq", GroqReasoningProvider("k", "m1", apiBaseUrl = groqServer.url("/openai/v1").toString().trimEnd('/'), sleeper = {}, warningLogger = { _, _ -> })),
                ConfiguredReasoningProvider("gemini", GeminiReasoningProvider("k", "gemini-x", apiBaseUrl = geminiServer.url("/v1beta/").toString(), sleeper = {}, warningLogger = { _, _ -> }, infoLogger = {}))
            )
        )
        val guarded = PrivacyGuardProvider(chain, { settings }) { audits += it.audit }
        val requests: Int get() = groqServer.requestCount + geminiServer.requestCount
        val outbound: List<String> get() = groqLog.bodies + geminiLog.bodies
        override fun close() { groqServer.shutdown(); geminiServer.shutdown() }
    }

    private fun Harness.assertNoSentinelSent() {
        sentinels.forEach { s -> assertFalse("an outbound payload contained a protected sentinel", outbound.any { it.contains(s) }) }
    }

    @Test
    fun aSecretInTheRequestNeverIssuesAnHttpRequest() = runBlocking {
        Harness().use { h ->
            for (text in listOf("my bank PIN is $pin", "the OTP is $otp, what do I do", "my gmail password is hunter2x9", "card 4111 1111 1111 1111 expires soon")) {
                val response = h.guarded.reason(ReasoningRequest(text, JarvisCore.AVAILABLE_TOOLS))
                assertEquals(PrivacyGuardProvider.PRIVACY_LOCAL, response.modelName)
                assertTrue(response.privacyNote!!.contains("stayed on your phone"))
            }
            assertEquals("no HTTP request for blocked inputs", 0, h.requests)
            assertTrue(h.audits.all { it.startsWith("local only") })
            h.audits.forEach { a -> sentinels.forEach { s -> assertFalse("audit text contained a protected value", a.contains(s)) } }
        }
    }

    @Test
    fun secretsInHistoryAndMemoriesAreDroppedEvenThroughRetriesAndTheFallbackProvider() = runBlocking {
        Harness().use { h ->
            val request = ReasoningRequest(
                "what's the weather like tomorrow",
                JarvisCore.AVAILABLE_TOOLS,
                history = listOf(ReasoningTurn("user", "my bank PIN is $pin"), ReasoningTurn("model", "I can't store that."), ReasoningTurn("user", "thanks")),
                memoryContext = listOf("My college is VIT Mumbai", "wifi password is hunter2x9")
            )
            h.guarded.reason(request)
            assertTrue("the safe request reached the providers", h.groqServer.requestCount > 0 && h.geminiServer.requestCount > 0)
            h.assertNoSentinelSent()
            // Minimisation keeps the useful context.
            assertTrue(h.outbound.any { it.contains("VIT Mumbai") })
            assertTrue(h.outbound.any { it.contains("weather like tomorrow") })
            assertTrue(h.audits.single().contains("dropped 1 history turn(s)") && h.audits.single().contains("dropped 1 memory item(s)"))
        }
    }

    @Test
    fun confirmedSummariesSendContentWithOnlyTheSecretValuesRedacted() = runBlocking {
        Harness().use { h ->
            val prompt = NewMessageReplies.summaryPrompt(
                listOf(
                    com.jarvis.data.NotificationRecord("1", "k1", "com.bank", "Bank", "HDFC", "$otp is your OTP for login. Do not share.", 1, false),
                    com.jarvis.data.NotificationRecord("2", "k2", "com.whatsapp", "WhatsApp", "Asha", "Meet at 6:30 near gate 4?", 2, false)
                )
            )
            h.guarded.reason(ReasoningRequest(prompt, emptyList(), purpose = RequestPurpose.MESSAGE_SUMMARY, userConsented = true))
            h.assertNoSentinelSent()
            assertTrue("unrelated content is kept", h.outbound.any { it.contains("Meet at 6:30 near gate 4") })
            assertTrue(h.outbound.any { it.contains("[redacted] is your OTP") })
        }
    }

    @Test
    fun privateContentWithoutConfirmationAndTheCloudOffSettingSendNothing() = runBlocking {
        Harness().use { h ->
            val summary = h.guarded.reason(ReasoningRequest("Summarize: - WhatsApp, Asha: hello", emptyList(), purpose = RequestPurpose.MESSAGE_SUMMARY))
            assertTrue(summary.privacyNote!!.contains("confirmation"))
            h.settings = PrivacySettings(cloudAllowed = false)
            val general = h.guarded.reason(ReasoningRequest("what should I cook tonight", JarvisCore.AVAILABLE_TOOLS))
            assertTrue(general.privacyNote!!.contains("Cloud AI is off"))
            assertEquals(0, h.requests)
        }
    }

    @Test
    fun ordinaryRequestsWithNumbersStillReachTheProvider() = runBlocking {
        Harness().use { h ->
            for (text in listOf("set an alarm for 7:30 tomorrow", "flight AI 2025 lands at 10:30", "what is an OTP?", "pin this note to the top", "reset my password for the college portal", "call 9876543210")) {
                assertEquals(text, PrivacyDecision.Allow::class, PrivacyPolicy.evaluate(ReasoningRequest(text, JarvisCore.AVAILABLE_TOOLS), PrivacySettings())::class)
            }
            h.guarded.reason(ReasoningRequest("what is an OTP?", JarvisCore.AVAILABLE_TOOLS))
            assertTrue(h.requests > 0)
        }
    }

    @Test
    fun redactionReplacesOnlyTheSecretValues() {
        assertEquals("Your OTP is [redacted]. Meeting at 10:30 in room 204." to 1, SensitiveData.redact("Your OTP is $otp. Meeting at 10:30 in room 204."))
        assertEquals("[redacted] is your OTP" to 1, SensitiveData.redact("$otp is your OTP"))
        assertEquals("password: [redacted]" to 1, SensitiveData.redact("password: hunter2x9"))
        assertEquals("pay with [card] today" to 1, SensitiveData.redact("pay with 4111 1111 1111 1111 today"))
        val untouched = "Flight AI 2025 at 10:30 on 21/10/2026, gate 4, seat 12A, ₹1,250"
        assertEquals(untouched to 0, SensitiveData.redact(untouched))
        listOf("what is an OTP?", "pin this note", "the password is wrong", "reset my password").forEach { assertFalse(it, SensitiveData.isSecret(it)) }
    }

    @Test
    fun storedTextKeepsTheDecisionButNeverTheValue() {
        val decision = PrivacyPolicy.evaluate(ReasoningRequest("remember my upi pin is $pin", emptyList()), PrivacySettings())
        assertTrue(decision is PrivacyDecision.LocalOnly)
        assertTrue(decision.audit.contains("labelled credential"))
        assertFalse(decision.audit.contains(pin))
        assertTrue(DataCategory.SECRET in decision.categories)
        assertFalse(SensitiveData.forStorage("Groq said: your PIN is $pin").contains(pin))
    }

    @Test
    fun aSecretAnswerIsNeverReadByTheCloudVoice() {
        val settings = SpeechSettings(provider = SpeechProvider.ELEVENLABS, cloudForPrivate = true)
        assertTrue(VoiceRouting.decide(settings, true, privateContent = true, speakableLength = 40, secret = true) is VoiceRouting.Decision.Phone)
        assertEquals(VoiceRouting.Decision.Cloud, VoiceRouting.decide(settings, true, privateContent = true, speakableLength = 40, secret = false))
    }
}
