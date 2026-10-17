package com.jarvis.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqReasoningProviderTest {
    @Test
    fun discoversActiveModelsAndPrefersLargerToolCapableInstructModel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(modelList("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "whisper-large-v3"))
            server.enqueue(toolResponse("task.create", """{"title":"Buy milk"}"""))
            server.start()

            val result = provider(server).reason(ReasoningRequest("add a task", listOf("task.create")))

            assertEquals("openai/gpt-oss-120b", result.modelName)
            assertEquals("groq", result.engineName)
            assertEquals("Buy milk", result.steps.single().parameters["title"])
            assertEquals("groq", result.attempts.last().providerName)
            val listRequest = server.takeRequest()
            assertEquals("/openai/v1/models", listRequest.requestUrl?.encodedPath)
            assertEquals("Bearer test-groq-secret", listRequest.getHeader("Authorization"))
            val completion = server.takeRequest()
            assertEquals("/openai/v1/chat/completions", completion.requestUrl?.encodedPath)
            val payload = completion.body.readUtf8()
            assertTrue(payload.contains("\"model\":\"openai/gpt-oss-120b\""))
            assertTrue(payload.contains("\"tool_choice\":\"auto\""))
            assertTrue(payload.contains("\"name\":\"task.create\""))
        }
    }

    @Test
    fun notFoundAdvancesToAnotherDiscoveredModel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(modelList("alpha-8b-instruct", "beta-70b-instruct"))
            server.enqueue(apiError(404, "Model not found"))
            server.enqueue(toolResponse("task.create", """{"title":"Walk"}"""))
            server.start()
            val cache = TestCache()

            val result = provider(server, "alpha-8b-instruct", cache)
                .reason(ReasoningRequest("add a task", listOf("task.create")))

            assertEquals("beta-70b-instruct", result.modelName)
            assertEquals(listOf(404, 200), result.attempts.filter { it.modelName != "models-list" }.map { it.httpStatus })
            assertEquals("beta-70b-instruct", cache.value)
        }
    }

    @Test
    fun transient429HonorsRetryAfterAndRecovers() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(modelList("alpha-8b-instruct"))
            server.enqueue(apiError(429, "Rate limited").addHeader("Retry-After", "3"))
            server.enqueue(toolResponse("task.create", """{"title":"Walk"}"""))
            server.start()
            val waits = mutableListOf<Long>()

            val result = provider(server, "alpha-8b-instruct", sleeper = { waits += it })
                .reason(ReasoningRequest("add a task", listOf("task.create")))

            assertEquals("groq", result.engineName)
            assertEquals(listOf(3_000L), waits)
            assertEquals(
                listOf(429, 200),
                result.attempts.filter { it.modelName == "alpha-8b-instruct" }.map { it.httpStatus }
            )
        }
    }

    @Test
    fun repeated503MovesToNextDiscoveredModel() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(modelList("alpha-8b-instruct", "beta-70b-instruct"))
            repeat(3) { server.enqueue(apiError(503, "High demand")) }
            server.enqueue(toolResponse("task.create", """{"title":"Walk"}"""))
            server.start()
            val waits = mutableListOf<Long>()

            val result = provider(server, "alpha-8b-instruct", sleeper = { waits += it })
                .reason(ReasoningRequest("add a task", listOf("task.create")))

            assertEquals("beta-70b-instruct", result.modelName)
            assertEquals(listOf(500L, 1_500L), waits)
            assertEquals(
                listOf(503, 503, 503, 200),
                result.attempts.filter { it.modelName != "models-list" }.map { it.httpStatus }
            )
        }
    }

    @Test
    fun modelListForbiddenIsAnExplicitProviderFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(apiError(403, "The API key is not permitted to access this service"))
            server.start()

            val result = provider(server).reason(ReasoningRequest("hello", emptyList()))

            assertEquals("groq", result.engineName)
            assertTrue(result.failureReason.orEmpty().contains("HTTP 403"))
            assertTrue(result.attempts.any { it.providerName == "groq" && it.httpStatus == 403 })
        }
    }

    @Test
    fun malformedToolArgumentsReturnSafeProviderError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(modelList("alpha-8b-instruct"))
            server.enqueue(toolResponse("task.create", """{"title":"Walk","unexpected":"x"}"""))
            server.start()

            val result = provider(server, "alpha-8b-instruct")
                .reason(ReasoningRequest("add a task", listOf("task.create")))

            assertTrue(result.failureReason.orEmpty().contains("Unexpected"))
            assertTrue(result.steps.isEmpty())
            assertTrue(result.reply.contains("No action was taken"))
            assertEquals(200, result.attempts.last().httpStatus)
        }
    }

    private fun provider(
        server: MockWebServer,
        configuredModel: String = "not-listed",
        cache: TestCache = TestCache(),
        sleeper: suspend (Long) -> Unit = {}
    ) = GroqReasoningProvider(
        apiKey = "test-groq-secret",
        configuredModelName = configuredModel,
        client = okhttp3.OkHttpClient(),
        apiBaseUrl = server.url("/openai/v1").toString().trimEnd('/'),
        modelCache = cache,
        sleeper = sleeper,
        warningLogger = { _, _ -> }
    )

    private fun modelList(vararg ids: String): MockResponse {
        val models = ids.joinToString(",") {
            """{"id":"$it","active":true}"""
        }
        return MockResponse().setResponseCode(200).setBody("""{"data":[$models]}""")
    }

    private fun toolResponse(name: String, arguments: String) =
        MockResponse().setResponseCode(200).setBody(
            """{"choices":[{"message":{"tool_calls":[{"id":"call-1","type":"function","function":{"name":"$name","arguments":"${arguments.replace("\"", "\\\"")}"}}]}}]}"""
        )

    private fun apiError(status: Int, message: String) =
        MockResponse().setResponseCode(status).setBody(
            """{"error":{"message":"$message"}}"""
        )

    private class TestCache : ModelNameCache {
        var value: String? = null
        override fun read(): String? = value
        override fun write(modelName: String) {
            value = modelName
        }
    }
}
