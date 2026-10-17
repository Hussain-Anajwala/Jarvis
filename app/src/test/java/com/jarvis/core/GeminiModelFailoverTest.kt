package com.jarvis.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiModelFailoverTest {
    @Test
    fun notFoundDiscoversAndTriesNextEligibleFlashModel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(apiError(404, "NOT_FOUND", "Model retired"))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"models":[
                        {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
                        {"name":"models/gemini-3.7-flash","supportedGenerationMethods":["generateContent"]},
                        {"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]},
                        {"name":"models/gemini-3.9-flash-image","supportedGenerationMethods":["generateContent"]},
                        {"name":"models/gemini-no-tools-flash","supportedGenerationMethods":["countTokens"]}
                    ]}"""
                )
            )
            server.enqueue(textResponse("What time and where is the dentist appointment?"))
            server.start()
            val cache = TestModelNameCache("retired-flash")
            val provider = provider(server, cache)

            val result = provider.reason(ReasoningRequest("dentist appointment Friday", listOf("meeting.create")))

            assertEquals("Attempts: ${result.attempts}", "gemini-3.8-flash", result.modelName)
            assertEquals("What time and where is the dentist appointment?", result.reply)
            assertEquals("gemini-3.8-flash", cache.value)
            assertEquals(404, result.attempts.first { it.modelName == "retired-flash" }.httpStatus)
            assertEquals(
                "/v1beta/models/retired-flash:generateContent",
                server.takeRequest().requestUrl?.encodedPath
            )
            val listRequest = server.takeRequest()
            assertEquals("/v1beta/models", listRequest.requestUrl?.encodedPath)
            assertEquals("100", listRequest.requestUrl?.queryParameter("pageSize"))
            assertEquals(
                "/v1beta/models/gemini-3.8-flash:generateContent",
                server.takeRequest().requestUrl?.encodedPath
            )
        }
    }

    @Test
    fun serviceUnavailableRetriesCurrentModelBeforeAcceptingResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(apiError(503, "UNAVAILABLE", "Temporarily busy"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[]}"""))
            server.enqueue(textResponse("Gemini is available again."))
            server.start()
            val waits = mutableListOf<Long>()
            val result = provider(server, sleeper = { waits += it })
                .reason(ReasoningRequest("hello", listOf("meeting.create")))

            assertEquals("Gemini is available again.", result.reply)
            assertEquals("gemini-configured-flash", result.modelName)
            assertEquals(listOf(503, 200), result.attempts.filter { it.modelName == "gemini-configured-flash" }.map { it.httpStatus })
            assertEquals(listOf(500L), waits)
        }
    }

    @Test
    fun tooManyRequestsHonorsRetryAfterWithinBound() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                apiError(429, "RESOURCE_EXHAUSTED", "Rate limited")
                    .addHeader("Retry-After", "60")
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[]}"""))
            server.enqueue(textResponse("Retry succeeded."))
            server.start()
            val waits = mutableListOf<Long>()

            val result = provider(server, sleeper = { waits += it })
                .reason(ReasoningRequest("hello", listOf("meeting.create")))

            assertEquals("Retry succeeded.", result.reply)
            assertEquals(listOf(5_000L), waits)
            assertEquals(listOf(429, 200), result.attempts.filter { it.modelName == "gemini-configured-flash" }.map { it.httpStatus })
        }
    }

    @Test
    fun exhaustedQuotaFallsBackWithoutRepeatingRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                apiError(
                    429,
                    "RESOURCE_EXHAUSTED",
                    "You exceeded your current quota; check plan and billing."
                )
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[]}"""))
            server.start()
            val waits = mutableListOf<Long>()

            val result = provider(server, sleeper = { waits += it })
                .reason(ReasoningRequest("hello", listOf("meeting.create")))

            assertEquals("offline", result.engineName)
            assertTrue(result.failureReason.orEmpty().contains("quota", ignoreCase = true))
            assertEquals(listOf(429), result.attempts.filter { it.modelName == "gemini-configured-flash" }.map { it.httpStatus })
            assertTrue(waits.isEmpty())
            assertEquals("/v1beta/models/gemini-configured-flash:generateContent", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/v1beta/models", server.takeRequest().requestUrl?.encodedPath)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun localBudgetSkipsGeminiRequestBeforeNetworkCall() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val result = GeminiReasoningProvider(
                apiKey = "test-key",
                configuredModelName = "gemini-configured-flash",
                client = okhttp3.OkHttpClient(),
                apiBaseUrl = server.url("/v1beta/").toString(),
                modelCache = TestModelNameCache("gemini-configured-flash"),
                quotaTracker = object : GeminiQuotaTracker {
                    override fun tryAcquire(
                        keySlot: String,
                        modelName: String,
                        dailyBudget: Int,
                        minuteBudget: Int,
                        nowEpochMs: Long
                    ) = false
                },
                dailyBudget = 18,
                minuteBudget = 4,
                warningLogger = { _, _ -> },
                infoLogger = {}
            ).reason(ReasoningRequest("hello", listOf("task.create")))

            assertEquals("offline", result.engineName)
            assertTrue(result.failureReason.orEmpty().contains("Local Gemini request budget"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun unsupportedFunctionCallingAdvancesToNextDiscoveredModel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(apiError(400, "INVALID_ARGUMENT", "Function calling is not supported for this model"))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"models":[
                        {"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]}
                    ]}"""
                )
            )
            server.enqueue(textResponse("What time and where should I schedule it?"))
            server.start()

            val result = provider(server)
                .reason(ReasoningRequest("schedule a dentist visit", listOf("meeting.create")))

            assertEquals("gemini-3.8-flash", result.modelName)
            assertEquals("What time and where should I schedule it?", result.reply)
            assertEquals(400, result.attempts.first().httpStatus)
            assertEquals(
                "/v1beta/models/gemini-configured-flash:generateContent",
                server.takeRequest().requestUrl?.encodedPath
            )
            assertEquals("/v1beta/models", server.takeRequest().requestUrl?.encodedPath)
            assertEquals(
                "/v1beta/models/gemini-3.8-flash:generateContent",
                server.takeRequest().requestUrl?.encodedPath
            )
        }
    }

    @Test
    fun malformedFunctionCallReturnsSafeErrorWithoutExecutingOrFallingBack() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"meeting.create","args":{"title":"Dentist"}}}]}}]}"""
                )
            )
            server.start()

            val result = provider(server)
                .reason(ReasoningRequest("put a dentist visit on my calendar", listOf("meeting.create")))

            assertTrue(result.reply.contains("No action was taken"))
            assertTrue(result.steps.isEmpty())
            assertEquals("gemini", result.engineName)
            assertEquals("gemini-configured-flash", result.modelName)
            assertEquals(1, result.attempts.size)
            assertEquals(200, result.attempts.single().httpStatus)
            assertFalse(result.reply.contains("I can create meetings"))
            assertEquals("/v1beta/models/gemini-configured-flash:generateContent", server.takeRequest().requestUrl?.encodedPath)
        }
    }

    @Test
    fun repeatedTransient429WithoutRetryAfterFallsBackInsteadOfCrashing() = runBlocking {
        // Regression: the third 429 without Retry-After indexed past RETRY_DELAYS_MS (found by the eval run).
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    if (request.requestUrl?.encodedPath == "/v1beta/models") {
                        MockResponse().setResponseCode(200).setBody("""{"models":[]}""")
                    } else {
                        apiError(429, "RESOURCE_EXHAUSTED", "Too many requests, slow down")
                    }
            }
            server.start()

            val result = provider(server).reason(ReasoningRequest("hello", listOf("meeting.create")))

            assertEquals("offline", result.engineName)
            assertTrue(result.attempts.count { it.httpStatus == 429 } >= 3)
        }
    }

    private fun provider(
        server: MockWebServer,
        cache: TestModelNameCache = TestModelNameCache(null),
        sleeper: suspend (Long) -> Unit = {}
    ) = GeminiReasoningProvider(
        apiKey = "test-key-must-never-be-logged",
        configuredModelName = "gemini-configured-flash",
        client = okhttp3.OkHttpClient(),
        apiBaseUrl = server.url("/v1beta/").toString(),
        modelCache = cache,
        sleeper = sleeper,
        warningLogger = { _, _ -> },
        infoLogger = {}
    )

    private fun textResponse(text: String) =
        MockResponse().setResponseCode(200).setBody(
            """{"candidates":[{"content":{"parts":[{"text":"$text"}]}}]}"""
        )

    private fun apiError(status: Int, apiStatus: String, message: String) =
        MockResponse().setResponseCode(status).setBody(
            """{"error":{"code":$status,"status":"$apiStatus","message":"$message"}}"""
        )

    private class TestModelNameCache(initial: String?) : ModelNameCache {
        var value: String? = initial
        override fun read(): String? = value
        override fun write(modelName: String) {
            value = modelName
        }
    }
}
