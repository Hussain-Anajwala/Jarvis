package com.jarvis.core

import android.util.Log
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class GroqReasoningProvider(
    private val apiKey: String,
    private val configuredModelName: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val apiBaseUrl: String = "https://api.groq.com/openai/v1",
    private val modelCache: ModelNameCache = MemoryModelNameCache(null),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val warningLogger: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    }
) : ReasoningProvider {
    private data class HttpResult(
        val status: Int,
        val body: String,
        val retryAfter: String?
    )

    @Volatile
    private var cachedModelName: String? = modelCache.read()?.takeIf(String::isNotBlank)

    override suspend fun reason(request: ReasoningRequest): ReasoningResponse =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext failure(
                    "Groq API key is not configured.",
                    emptyList(),
                    configuredModelName
                )
            }

            val attempts = mutableListOf<ModelAttempt>()
            val candidates = mutableListOf<String>()
            var modelsLoaded = cachedModelName == null
            cachedModelName?.let(candidates::add)
            if (modelsLoaded) candidates += orderCandidates(discoverModels(attempts))
            if (candidates.isEmpty()) {
                val discoveryFailure = attempts.lastOrNull()
                    ?.takeIf {
                        it.modelName == "models-list" &&
                            (it.httpStatus?.let { status -> status !in 200..299 } ?: true)
                    }
                    ?.let { "Groq model discovery HTTP ${it.httpStatus ?: "network error"}${it.detail?.let { detail -> ": $detail" }.orEmpty()}" }
                    ?: "Groq model discovery returned no active models."
                return@withContext failure(
                    discoveryFailure,
                    attempts,
                    configuredModelName
                )
            }
            var lastFailure = "Groq request failed."
            val attemptedModels = linkedSetOf<String>()
            var candidateIndex = 0
            while (attemptedModels.size < MAX_MODELS) {
                if (candidateIndex >= candidates.size) {
                    if (modelsLoaded) break
                    candidates += orderCandidates(discoverModels(attempts))
                    modelsLoaded = true
                    continue
                }
                val modelName = candidates[candidateIndex++]
                if (!attemptedModels.add(modelName)) continue
                for (attemptNumber in 0 until MAX_ATTEMPTS_PER_MODEL) {
                    val response = try {
                        execute(completionRequest(modelName, request))
                    } catch (exception: IOException) {
                        val detail = "Network error (${exception.javaClass.simpleName})."
                        attempts += ModelAttempt(modelName, null, detail, PROVIDER_ID)
                        lastFailure = detail
                        warningLogger("Groq model $modelName: $detail", null)
                        if (attemptNumber < MAX_ATTEMPTS_PER_MODEL - 1) {
                            sleeper(RETRY_DELAYS_MS[attemptNumber])
                            continue
                        }
                        break
                    }

                    attempts += ModelAttempt(modelName, response.status, providerName = PROVIDER_ID)
                    if (response.status in 200..299) {
                        val json = try {
                            JSONObject(response.body)
                        } catch (exception: JSONException) {
                            return@withContext failure(
                                "Groq returned invalid JSON.",
                                attempts,
                                modelName
                            )
                        }
                        val parsed = parseResponse(json, request.availableTools.toSet())
                        if (parsed.second != null) {
                            return@withContext ReasoningResponse(
                                reply = "Groq returned an invalid tool response. No action was taken.",
                                engineName = PROVIDER_ID,
                                modelName = modelName,
                                attempts = attempts,
                                failureReason = parsed.second
                            )
                        }
                        val reply = parsed.first?.let(::userMessageFor)
                            ?: json.optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("message")
                                ?.optString("content")
                                ?.trim()
                        if (reply.isNullOrBlank()) {
                            return@withContext failure(
                                "Groq returned no usable text or tool call.",
                                attempts,
                                modelName
                            )
                        }
                        cachedModelName = modelName
                        modelCache.write(modelName)
                        return@withContext ReasoningResponse(
                            reply = reply,
                            steps = listOfNotNull(parsed.first),
                            engineName = PROVIDER_ID,
                            modelName = modelName,
                            attempts = attempts
                        )
                    }

                    val detail = readApiError(response.body)
                    lastFailure = "HTTP ${response.status}${detail?.let { " $it" }.orEmpty()}"
                    attempts[attempts.lastIndex] = ModelAttempt(
                        modelName,
                        response.status,
                        detail,
                        PROVIDER_ID,
                        if (response.status == 429) {
                            retryAfterMs(response.retryAfter, attemptNumber)
                        } else null
                    )
                    warningLogger("Groq model $modelName: $lastFailure", null)
                    when {
                        response.status == 404 -> break
                        response.status == 429 || response.status == 503 -> {
                            if (attemptNumber < MAX_ATTEMPTS_PER_MODEL - 1) {
                                val retryMs = if (response.status == 429) {
                                    retryAfterMs(response.retryAfter, attemptNumber)
                                } else {
                                    RETRY_DELAYS_MS[attemptNumber]
                                }
                                sleeper(retryMs)
                            } else {
                                break
                            }
                        }
                        else -> return@withContext failure(lastFailure, attempts, modelName)
                    }
                }
            }
            failure(lastFailure, attempts, attemptedModels.lastOrNull() ?: configuredModelName)
        }

    private fun discoverModels(attempts: MutableList<ModelAttempt>): List<String> {
        val request = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = readApiError(body)
                    attempts += ModelAttempt("models-list", response.code, detail, PROVIDER_ID)
                    warningLogger(
                        "Groq model discovery HTTP ${response.code}${detail?.let { ": $it" }.orEmpty()}",
                        null
                    )
                    return emptyList()
                }
                attempts += ModelAttempt("models-list", response.code, providerName = PROVIDER_ID)
                val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
                buildList {
                    for (index in 0 until data.length()) {
                        val model = data.optJSONObject(index) ?: continue
                        if (model.has("active") && !model.optBoolean("active")) continue
                        val id = model.optString("id").trim()
                        if (id.isNotBlank()) add(id)
                    }
                }
            }
        } catch (exception: IOException) {
            attempts += ModelAttempt(
                "models-list",
                null,
                "Network error (${exception.javaClass.simpleName}).",
                PROVIDER_ID
            )
            warningLogger("Groq model discovery network error.", exception)
            emptyList()
        } catch (exception: JSONException) {
            attempts += ModelAttempt("models-list", 200, "Invalid model-list response.", PROVIDER_ID)
            warningLogger("Groq model discovery returned invalid JSON.", exception)
            emptyList()
        }
    }

    private fun orderCandidates(discovered: List<String>): List<String> {
        val ordered = linkedSetOf<String>()
        cachedModelName?.takeIf(discovered::contains)?.let(ordered::add)
        configuredModelName.takeIf(discovered::contains)?.let(ordered::add)
        ordered.addAll(
            discovered.sortedWith(
                compareByDescending<String> { isInstructModel(it) }
                    .thenByDescending(::parameterCountBillions)
                    .thenBy { it }
            )
        )
        return ordered.toList()
    }

    private fun isInstructModel(modelName: String): Boolean {
        val normalized = modelName.lowercase()
        return "instruct" in normalized || "versatile" in normalized ||
            normalized.startsWith("openai/gpt-oss-")
    }

    private fun parameterCountBillions(modelName: String): Int =
        Regex("""(\d+)b(?:[-/]|$)""", RegexOption.IGNORE_CASE)
            .find(modelName)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: 0

    private fun completionRequest(modelName: String, request: ReasoningRequest): Request {
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        "You are JARVIS, a personal Android assistant. Ask one short question when required details are missing. Never guess time, date, place, title, or recipient. Use only the provided tools."
                    )
            )
        request.history.takeLast(10).forEach { turn ->
            val role = if (turn.role == "user") "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", turn.text))
        }
        messages.put(JSONObject().put("role", "user").put("content", request.utterance))

        val payload = JSONObject()
            .put("model", modelName)
            .put("messages", messages)
            .put("tools", ReasoningToolSchema.toOpenAiJson(request.availableTools))
            .put("tool_choice", "auto")
            .put("parallel_tool_calls", false)
            .put("temperature", 0.2)
        return Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun execute(request: Request): HttpResult =
        client.newCall(request).execute().use { response ->
            HttpResult(
                response.code,
                response.body?.string().orEmpty(),
                response.header("Retry-After")
            )
        }

    private fun parseResponse(
        json: JSONObject,
        availableTools: Set<String>
    ): Pair<PlanStep?, String?> {
        val message = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: return null to null
        val calls = message.optJSONArray("tool_calls") ?: return null to null
        if (calls.length() == 0) return null to null
        if (calls.length() > 1) return null to "Multiple tool calls are not supported safely."
        val function = calls.optJSONObject(0)
            ?.optJSONObject("function")
            ?: return null to "Malformed function call."
        val name = function.optString("name")
        if (name !in availableTools) return null to "Unrecognized function name."
        val args = try {
            JSONObject(function.optString("arguments"))
        } catch (_: JSONException) {
            return null to "Malformed function arguments."
        }
        val required = ReasoningToolSchema.requiredFields(name)
        val values = mutableMapOf<String, String>()
        val keys = args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in required) return null to "Unexpected function argument."
            val value = args.opt(key)
            if (value !is String) return null to "Function arguments must be strings."
            values[key] = value
        }
        if (required.any { values[it].isNullOrBlank() }) {
            return null to "Required function arguments are missing."
        }
        return PlanStep(name, values) to null
    }

    private fun userMessageFor(step: PlanStep): String = when (step.toolName) {
        "meeting.create" -> "I will create the meeting and a linked departure reminder."
        "calendar.cancel" -> "I found the meeting plan. Cancelling it will also cancel its linked reminder."
        "reminder.create" -> "I will schedule that reminder."
        "task.create" -> "I will add that task."
        "communication.draft" -> "I drafted a communication for ${step.parameters["recipient"]}."
        else -> "I will handle that request."
    }

    private fun readApiError(body: String): String? = try {
        JSONObject(body).optJSONObject("error")
            ?.optString("message")
            ?.replace(apiKey, "[redacted]")
            ?.replace(Regex("""[\r\n\t]+"""), " ")
            ?.take(240)
            ?.takeIf(String::isNotBlank)
    } catch (_: JSONException) {
        null
    }

    private fun retryAfterMs(retryAfter: String?, attemptNumber: Int): Long {
        val delayMs = retryAfter?.trim()?.toLongOrNull()?.times(1000L)
            ?: retryAfter?.let {
                runCatching {
                    ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - System.currentTimeMillis()
                }.getOrNull()
            }
        return (delayMs ?: RETRY_DELAYS_MS[attemptNumber])
            .coerceIn(0L, MAX_RETRY_AFTER_MS)
    }

    private fun failure(
        reason: String,
        attempts: List<ModelAttempt>,
        modelName: String
    ) = ReasoningResponse(
        reply = "Groq is unavailable. I will try the next configured reasoning provider.",
        engineName = PROVIDER_ID,
        failureReason = reason,
        modelName = modelName,
        attempts = attempts.toList()
    )

    companion object {
        const val PROVIDER_ID = "groq"
        private const val TAG = "GroqReasoning"
        private const val MAX_MODELS = 3
        private const val MAX_ATTEMPTS_PER_MODEL = 3
        private const val MAX_RETRY_AFTER_MS = 5_000L
        private val RETRY_DELAYS_MS = longArrayOf(500L, 1_500L)
    }
}
