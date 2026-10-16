package com.jarvis.core

import android.util.Log
import android.content.Context
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
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject

data class ReasoningTurn(val role: String, val text: String)
data class ReasoningRequest(
    val utterance: String,
    val availableTools: List<String>,
    val history: List<ReasoningTurn> = emptyList()
)
data class PlanStep(val toolName: String, val parameters: Map<String, String>)
data class ReasoningResponse(
    val reply: String,
    val steps: List<PlanStep> = emptyList(),
    val confidence: Double = 1.0,
    val engineName: String = "offline",
    val failureReason: String? = null,
    val modelName: String? = null,
    val attempts: List<ModelAttempt> = emptyList()
)
data class ModelAttempt(
    val modelName: String,
    val httpStatus: Int?,
    val detail: String? = null
)

interface ModelNameCache {
    fun read(): String?
    fun write(modelName: String)
}

class SharedPreferencesModelNameCache(context: Context) : ModelNameCache {
    private val preferences = context.getSharedPreferences("gemini_model_cache", Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString("model_name", null)

    override fun write(modelName: String) {
        preferences.edit().putString("model_name", modelName).apply()
    }
}

private class MemoryModelNameCache(initialModelName: String) : ModelNameCache {
    @Volatile private var value: String? = initialModelName
    override fun read(): String? = value
    override fun write(modelName: String) {
        value = modelName
    }
}

interface ReasoningProvider {
    suspend fun reason(request: ReasoningRequest): ReasoningResponse
}

class OfflineRuleProvider : ReasoningProvider {
    override suspend fun reason(request: ReasoningRequest): ReasoningResponse {
        val text = request.utterance.lowercase()
        val cancellationIntent = Regex("""\b(cancel|delete|remove)\b""").containsMatchIn(text)
        val alarmRequest = AlarmRequestParser.parse(request.utterance)
        val previousMeetingRequest = request.history.lastOrNull {
            it.role == "user" && MeetingRequestParser.isMeetingIntent(it.text)
        }
        val clarificationWasAsked = request.history.lastOrNull()?.let {
            it.role == "model" && listOf("time", "where", "venue", "title")
                .any { term -> it.text.contains(term, ignoreCase = true) }
        } == true
        val meetingText = if (
            previousMeetingRequest != null && clarificationWasAsked &&
            (MeetingRequestParser.isMeetingIntent(request.utterance).not())
        ) {
            "${previousMeetingRequest.text} ${request.utterance}"
        } else {
            request.utterance
        }
        val meetingIntent = MeetingRequestParser.isMeetingIntent(request.utterance) ||
            (clarificationWasAsked && previousMeetingRequest != null)
        val meetingRequest = MeetingRequestParser.parse(meetingText)
        val meetingTitle = MeetingRequestParser.explicitTitle(request.utterance)
        ?: MeetingRequestParser.explicitTitle(meetingText)
        return when {
            cancellationIntent ->
                ReasoningResponse(
                    "I found the meeting plan. Cancelling it will also cancel its linked reminder.",
                    listOf(PlanStep("calendar.cancel", emptyMap())),
                    engineName = "offline"
                )
            Regex("""\b(message|text|email|send)\b""").containsMatchIn(text) ->
                CommunicationRequestParser.parse(request.utterance)?.let { communication ->
                    ReasoningResponse(
                        "I drafted a communication for ${communication.recipientName}.",
                        listOf(
                            PlanStep(
                                "communication.draft",
                                mapOf("recipient" to communication.recipientName, "body" to communication.body)
                            )
                        ),
                        engineName = "offline"
                    )
                } ?: ReasoningResponse(
                    "I could not identify the recipient. Try “message Maa that I will call soon.”",
                    listOf(PlanStep("communication.draft", mapOf("recipient" to "", "body" to ""))),
                    engineName = "offline"
                )
            alarmRequest != null -> {
                val alarm = alarmRequest
                ReasoningResponse(
                    "I will hand off this alarm to your Clock app.",
                    listOf(
                        PlanStep(
                            "alarm.handoff",
                            mapOf(
                                "hour" to alarm.hour.toString(),
                                "minute" to alarm.minute.toString(),
                                "label" to alarm.label
                            )
                        )
                    ),
                    engineName = "offline"
                )
            }
            AlarmRequestParser.isAlarmIntent(request.utterance) ->
                ReasoningResponse(
                    "Please specify an alarm time with AM or PM, for example “set an alarm for 7 AM.”",
                    engineName = "offline"
                )
            meetingIntent -> {
                val missing = MeetingRequestParser.missingDetails(meetingText)
                if (missing.isNotEmpty()) {
                    val question = MeetingRequestParser.clarificationQuestion(meetingText, missing)
                    ReasoningResponse(question, engineName = "offline")
                } else {
                    val meeting = checkNotNull(meetingRequest)
                    ReasoningResponse(
                        "I will create the meeting and a linked departure reminder.",
                        listOf(
                            PlanStep(
                                "meeting.create",
                                mapOf(
                                    "title" to (meetingTitle ?: "Meeting at ${meeting.location}"),
                                    "hour" to meeting.hourOfDay.toString(),
                                    "minute" to meeting.minute.toString(),
                                    "location" to meeting.location,
                                    "date" to meeting.date.toString()
                                )
                            )
                        ),
                        engineName = "offline"
                    )
                }
            }
            Regex("remind|reminder").containsMatchIn(text) ->
                ReasoningResponse(
                    "I will schedule that reminder.",
                    listOf(PlanStep("reminder.create", mapOf("title" to request.utterance))),
                    engineName = "offline"
                )
            Regex("task|todo").containsMatchIn(text) ->
                ReasoningResponse(
                    "I will add that task.",
                    listOf(PlanStep("task.create", mapOf("title" to request.utterance))),
                    engineName = "offline"
                )
            else -> ReasoningResponse(
                "I can create meetings, reminders, and tasks. Try: “I have a meeting tomorrow at 10 AM at college.”",
                engineName = "offline"
            )
        }
    }
}

class GeminiReasoningProvider(
    private val apiKey: String,
    private val configuredModelName: String = "gemini-flash-latest",
    private val client: OkHttpClient = OkHttpClient(),
    private val apiBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta/",
    private val modelCache: ModelNameCache = MemoryModelNameCache(configuredModelName),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val warningLogger: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.w("GeminiReasoning", message) else Log.w("GeminiReasoning", message, error)
    },
    private val infoLogger: (String) -> Unit = { message -> Log.i("GeminiReasoning", message) }
) : ReasoningProvider {
    companion object {
        private const val MAX_CANDIDATES = 3
        private const val MAX_ATTEMPTS_PER_MODEL = 3
        private const val MAX_RETRY_AFTER_MS = 5_000L
        private val RETRY_DELAYS_MS = longArrayOf(500L, 1_500L)
        private val EXCLUDED_MODEL_TERMS = listOf("tts", "image", "audio", "live")
    }

    @Volatile
    private var cachedModelName: String? = modelCache.read()?.takeIf(String::isNotBlank)

    private data class HttpResult(
        val status: Int,
        val body: String,
        val retryAfter: String?
    )

    private data class ParsedFunctionCall(
        val step: PlanStep?,
        val invalidReason: String? = null
    )

    override suspend fun reason(request: ReasoningRequest): ReasoningResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext OfflineRuleProvider().reason(request).copy(
                engineName = "offline",
                failureReason = "Gemini API key is not configured.",
                modelName = configuredModelName
            )
        }

        val attempts = mutableListOf<ModelAttempt>()
        val candidates = mutableListOf<String>()
        cachedModelName?.let(candidates::add)
        if (configuredModelName.isNotBlank() && configuredModelName !in candidates) {
            candidates += configuredModelName
        }
        val attemptedModels = linkedSetOf<String>()
        var modelListLoaded = false
        var lastFailure = "Gemini request failed."

        while (attemptedModels.size < MAX_CANDIDATES) {
            val modelName = candidates.firstOrNull { it !in attemptedModels } ?: break
            attemptedModels += modelName
            var nextModel = false

            for (attemptNumber in 0 until MAX_ATTEMPTS_PER_MODEL) {
                val result = try {
                    execute(generateContentRequest(modelName, request))
                } catch (exception: IOException) {
                    val detail = "Network error (${exception.javaClass.simpleName})."
                    attempts += ModelAttempt(modelName, null, detail)
                    lastFailure = detail
                    warningLogger("Model $modelName: $detail", null)
                    if (!modelListLoaded) {
                        loadCandidates(candidates, attempts)
                        modelListLoaded = true
                    }
                    if (attemptNumber < MAX_ATTEMPTS_PER_MODEL - 1) {
                        sleeper(RETRY_DELAYS_MS[attemptNumber])
                        continue
                    }
                    nextModel = true
                    break
                }

                attempts += ModelAttempt(modelName, result.status)
                if (result.status in 200..299) {
                    val json = try {
                        JSONObject(result.body)
                    } catch (exception: JSONException) {
                        warningLogger("Model $modelName HTTP ${result.status}: invalid JSON.", exception)
                        return@withContext invalidResponse(modelName, attempts)
                    }
                    val parsed = parseFunctionCall(json, request.availableTools.toSet())
                    if (parsed.invalidReason != null) {
                        warningLogger("Model $modelName HTTP ${result.status}: ${parsed.invalidReason}", null)
                        return@withContext ReasoningResponse(
                            reply = "Gemini returned an invalid tool response. No action was taken.",
                            engineName = "gemini",
                            modelName = modelName,
                            attempts = attempts.toList()
                        )
                    }
                    val step = parsed.step
                    val reply = if (step != null) {
                        when (step.toolName) {
                            "meeting.create" -> "I will create the meeting and a linked departure reminder."
                            "calendar.cancel" -> "I found the meeting plan. Cancelling it will also cancel its linked reminder."
                            "reminder.create" -> "I will schedule that reminder."
                            "task.create" -> "I will add that task."
                            "communication.draft" -> "I drafted a communication for ${step.parameters["recipient"] ?: "that contact"}."
                            else -> "I will handle that request."
                        }
                    } else {
                        parseTextReply(json)
                    }
                    if (reply.isNullOrBlank()) {
                        warningLogger("Model $modelName HTTP ${result.status}: no usable text or function call.", null)
                        return@withContext invalidResponse(modelName, attempts)
                    }

                    cachedModelName = modelName
                    modelCache.write(modelName)
                    return@withContext ReasoningResponse(
                        reply = reply,
                        steps = listOfNotNull(step),
                        engineName = "gemini",
                        modelName = modelName,
                        attempts = attempts.toList()
                    )
                }

                val detail = readApiError(result.body)
                lastFailure = "HTTP ${result.status}${detail?.let { " $it" }.orEmpty()}"
                attempts[attempts.lastIndex] = ModelAttempt(modelName, result.status, detail)
                warningLogger("Model $modelName: $lastFailure", null)

                if (!modelListLoaded) {
                    loadCandidates(candidates, attempts)
                    modelListLoaded = true
                }

                when {
                    result.status == 429 && quotaExhausted(detail) ->
                        return@withContext offlineFallback(
                            request,
                            lastFailure,
                            modelName,
                            attempts
                        )
                    result.status == 404 -> {
                        nextModel = true
                        break
                    }
                    result.status == 400 && functionCallingUnsupported(detail) -> {
                        nextModel = true
                        break
                    }
                    result.status == 429 || result.status == 503 -> {
                        if (attemptNumber < MAX_ATTEMPTS_PER_MODEL - 1) {
                            val waitMs = if (result.status == 429) {
                                retryAfterMs(result.retryAfter, attemptNumber)
                            } else {
                                RETRY_DELAYS_MS[attemptNumber]
                            }
                            sleeper(waitMs)
                        } else {
                            nextModel = true
                            break
                        }
                    }
                    else -> return@withContext offlineFallback(
                        request,
                        lastFailure,
                        modelName,
                        attempts
                    )
                }
            }
            if (!nextModel) break
        }

        offlineFallback(
            request,
            lastFailure,
            attemptedModels.lastOrNull() ?: configuredModelName,
            attempts
        )
    }

    private fun execute(request: Request): HttpResult =
        client.newCall(request).execute().use { response ->
            HttpResult(
                status = response.code,
                body = response.body?.string().orEmpty(),
                retryAfter = response.header("Retry-After")
            )
        }

    private fun generateContentRequest(modelName: String, request: ReasoningRequest): Request {
        val functions = toolSchemaFor(request.availableTools)
        val payload = JSONObject().apply {
            put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            "You are JARVIS, a personal Android assistant. Ask one short question when required details are missing. Never guess time, date, place, title, or recipient. For meeting.create, provide date as YYYY-MM-DD based only on the user's request. Use only the provided functions."
                        )
                    )
                )
            )
            val contents = JSONArray()
            request.history.takeLast(10).forEach { turn ->
                contents.put(
                    JSONObject()
                        .put("role", turn.role)
                        .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
                )
            }
            contents.put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", request.utterance)))
            )
            put("contents", contents)
            put("tools", JSONArray().put(JSONObject().put("functionDeclarations", functions)))
            put("generationConfig", JSONObject().put("temperature", 0.2))
        }
        val normalizedName = modelName.removePrefix("models/")
        return Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/models/$normalizedName:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun toolSchemaFor(names: List<String>): JSONArray {
        val declarations = JSONArray()
        names.distinct().forEach { name ->
            val fields = when (name) {
                "meeting.create" -> listOf("title", "hour", "minute", "location", "date")
                "reminder.create", "task.create" -> listOf("title")
                "communication.draft" -> listOf("recipient", "body")
                "alarm.handoff" -> listOf("hour", "minute", "label")
                "calendar.cancel", "reminder.cancel", "task.list" -> emptyList()
                "task.complete" -> listOf("title")
                else -> return@forEach
            }
            val properties = JSONObject()
            fields.forEach { field ->
                properties.put(field, JSONObject().put("type", "string"))
            }
            val parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
            if (fields.isNotEmpty()) parameters.put("required", JSONArray(fields))
            declarations.put(
                JSONObject()
                    .put("name", name)
                    .put("description", "JARVIS tool: $name")
                    .put("parameters", parameters)
            )
        }
        return declarations
    }

    private fun parseFunctionCall(json: JSONObject, availableTools: Set<String>): ParsedFunctionCall {
        val candidates = json.optJSONArray("candidates") ?: return ParsedFunctionCall(null)
        for (i in 0 until candidates.length()) {
            val parts = candidates.optJSONObject(i)
                ?.optJSONObject("content")
                ?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                if (!part.has("functionCall")) continue
                val call = part.optJSONObject("functionCall")
                    ?: return ParsedFunctionCall(null, "Malformed function call.")
                val name = call.optString("name")
                if (name.isBlank() || name !in availableTools) {
                    return ParsedFunctionCall(null, "Unrecognized function name.")
                }
                val args = call.optJSONObject("args")
                    ?: return ParsedFunctionCall(null, "Malformed function arguments.")
                val required = requiredFields(name)
                val values = mutableMapOf<String, String>()
                val keys = args.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key !in required) return ParsedFunctionCall(null, "Unexpected function argument.")
                    val value = args.opt(key)
                    if (value !is String) return ParsedFunctionCall(null, "Function arguments must be strings.")
                    values[key] = value
                }
                if (required.any { values[it].isNullOrBlank() }) {
                    return ParsedFunctionCall(null, "Required function arguments are missing.")
                }
                return ParsedFunctionCall(PlanStep(name, values))
            }
        }
        return ParsedFunctionCall(null)
    }

    private fun requiredFields(name: String): Set<String> = when (name) {
        "meeting.create" -> setOf("title", "hour", "minute", "location", "date")
        "reminder.create", "task.create", "task.complete" -> setOf("title")
        "communication.draft" -> setOf("recipient", "body")
        "alarm.handoff" -> setOf("hour", "minute", "label")
        "calendar.cancel", "reminder.cancel", "task.list" -> emptySet()
        else -> emptySet()
    }

    private fun parseTextReply(json: JSONObject): String? {
        val candidates = json.optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val parts = candidates.optJSONObject(i)
                ?.optJSONObject("content")
                ?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                part.optString("text").trim().takeIf(String::isNotBlank)?.let { return it }
            }
        }
        return null
    }

    private fun loadCandidates(
        candidates: MutableList<String>,
        attempts: MutableList<ModelAttempt>
    ) {
        val request = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/models?pageSize=100")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = readApiError(body)
                    attempts += ModelAttempt("models-list", response.code, detail)
                    warningLogger("Model discovery HTTP ${response.code}${detail?.let { ": $it" }.orEmpty()}", null)
                    return
                }
                attempts += ModelAttempt("models-list", response.code)
                val models = JSONObject(body).optJSONArray("models") ?: return
                val discovered = mutableListOf<String>()
                for (index in 0 until models.length()) {
                    val model = models.optJSONObject(index) ?: continue
                    val name = model.optString("name").removePrefix("models/")
                    val methods = model.optJSONArray("supportedGenerationMethods") ?: continue
                    val canGenerate = (0 until methods.length())
                        .any { methods.optString(it) == "generateContent" }
                    val lowered = name.lowercase()
                    val flashTextModel = lowered.contains("flash") &&
                        EXCLUDED_MODEL_TERMS.none(lowered::contains)
                    if (canGenerate && flashTextModel && name !in candidates) discovered += name
                }
                candidates.addAll(0, discovered.sortedWith(
                    compareByDescending<String> { modelVersion(it) }
                        .thenBy { it }
                ))
                infoLogger("Model discovery returned ${candidates.size} Flash generateContent candidate(s).")
            }
        } catch (exception: IOException) {
            attempts += ModelAttempt(
                "models-list",
                null,
                "Network error (${exception.javaClass.simpleName})."
            )
            warningLogger("Gemini model discovery failed: ${exception.javaClass.simpleName}", exception)
        } catch (exception: JSONException) {
            attempts += ModelAttempt("models-list", 200, "Invalid model-list response.")
            warningLogger("Gemini model discovery returned invalid JSON.", exception)
        }
    }

    private fun modelVersion(name: String): Int {
        if (name.equals("gemini-flash-latest", ignoreCase = true)) return Int.MAX_VALUE
        val version = Regex("""gemini-(\d+)(?:\.(\d+))?-flash""")
            .find(name.lowercase())
            ?: return 0
        val major = version.groupValues[1].toIntOrNull() ?: 0
        val minor = version.groupValues[2].toIntOrNull() ?: 0
        return major * 100 + minor
    }

    private fun functionCallingUnsupported(detail: String?): Boolean {
        val message = detail?.lowercase() ?: return false
        val referencesFunctionCalling = "function" in message || "tool" in message
        val rejectsSupport = listOf("unsupported", "not supported", "does not support", "not enabled")
            .any(message::contains)
        return referencesFunctionCalling && rejectsSupport
    }

    private fun quotaExhausted(detail: String?): Boolean {
        val message = detail?.lowercase() ?: return false
        return "quota" in message && ("exceeded" in message || "billing" in message)
    }

    private fun retryAfterMs(retryAfter: String?, attemptNumber: Int): Long {
        val serverDelayMs = retryAfter?.trim()?.toLongOrNull()?.times(1_000L)
            ?: retryAfter?.let {
                try {
                    ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant()
                        .toEpochMilli() - System.currentTimeMillis()
                } catch (_: Exception) {
                    null
                }
            }
        return (serverDelayMs ?: RETRY_DELAYS_MS[attemptNumber])
            .coerceIn(0L, MAX_RETRY_AFTER_MS)
    }

    private fun readApiError(body: String): String? = try {
        val error = JSONObject(body).optJSONObject("error") ?: return null
        val status = error.optString("status").takeIf(String::isNotBlank)
        val message = error.optString("message")
            .replace(apiKey, "[redacted]")
            .replace(Regex("""key=[^&\s]+"""), "key=[redacted]")
            .replace(Regex("""[\r\n\t]+"""), " ")
            .take(240)
        listOfNotNull(status, message.takeIf(String::isNotBlank))
            .joinToString(": ")
            .takeIf(String::isNotBlank)
    } catch (_: JSONException) {
        null
    }

    private suspend fun offlineFallback(
        request: ReasoningRequest,
        reason: String,
        modelName: String,
        attempts: List<ModelAttempt>
    ): ReasoningResponse = OfflineRuleProvider().reason(request).copy(
        engineName = "offline",
        failureReason = reason,
        modelName = modelName,
        attempts = attempts.toList()
    )

    private fun invalidResponse(
        modelName: String,
        attempts: List<ModelAttempt>
    ) = ReasoningResponse(
        reply = "Gemini returned an invalid tool response. No action was taken.",
        engineName = "gemini",
        modelName = modelName,
        attempts = attempts.toList()
    )
}

private class LegacyGeminiReasoningProvider(
    private val apiKey: String,
    private val modelName: String = "gemini-3.8-flash",
    private val client: OkHttpClient = OkHttpClient()
) : ReasoningProvider {
    companion object {
        private const val TAG = "GeminiReasoning"
        private const val MAX_ATTEMPTS = 3
        private val RETRY_DELAYS_MS = longArrayOf(500, 1500)
    }

    private fun toolSchemaFor(names: List<String>): JSONArray {
        val declarations = JSONArray()
        for (toolName in names) {
            val params = JSONObject()
            when (toolName) {
                "meeting.create" -> {
                    params.put("type", "object")
                    params.put("properties", JSONObject().apply {
                        put("title", JSONObject().put("type", "string"))
                        put("hour", JSONObject().put("type", "string"))
                        put("minute", JSONObject().put("type", "string"))
                        put("location", JSONObject().put("type", "string"))
                    })
                    params.put("required", JSONArray().put("title").put("hour").put("minute").put("location"))
                }
                "calendar.cancel" -> {
                    params.put("type", "object")
                    params.put("properties", JSONObject())
                }
                "reminder.create" -> {
                    params.put("type", "object")
                    params.put("properties", JSONObject().apply {
                        put("title", JSONObject().put("type", "string"))
                    })
                    params.put("required", JSONArray().put("title"))
                }
                "task.create" -> {
                    params.put("type", "object")
                    params.put("properties", JSONObject().apply {
                        put("title", JSONObject().put("type", "string"))
                    })
                    params.put("required", JSONArray().put("title"))
                }
                "communication.draft" -> {
                    params.put("type", "object")
                    params.put("properties", JSONObject().apply {
                        put("recipient", JSONObject().put("type", "string"))
                        put("body", JSONObject().put("type", "string"))
                    })
                    params.put("required", JSONArray().put("recipient").put("body"))
                }
                else -> {
                    params.put("type", "object")
                    params.put("properties", JSONObject())
                }
            }
            declarations.put(
                JSONObject().apply {
                    put("name", toolName)
                    put("description", "JARVIS tool: $toolName")
                    put("parameters", params)
                }
            )
        }
        return declarations
    }

    private fun parseToolCall(json: JSONObject): PlanStep? {
        val candidates = json.optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(i) ?: continue
            val content = candidate.optJSONObject("content") ?: continue
            val parts = content.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                val functionCall = part.optJSONObject("functionCall") ?: continue
                val name = functionCall.optString("name")
                if (name.isBlank()) continue
                val args = functionCall.optJSONObject("args") ?: JSONObject()
                val params = mutableMapOf<String, String>()
                args.keys().forEach { key -> params[key] = args.optString(key, "") }
                return PlanStep(name, params)
            }
        }
        return null
    }

    private fun parseTextReply(json: JSONObject): String? {
        val candidates = json.optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(i) ?: continue
            val content = candidate.optJSONObject("content") ?: continue
            val parts = content.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                if (part.has("text")) {
                    return part.optString("text").trim().takeIf { it.isNotBlank() }
                }
            }
        }
        return null
    }

    override suspend fun reason(request: ReasoningRequest): ReasoningResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext OfflineRuleProvider().reason(request).copy(
                engineName = "offline",
                failureReason = "Gemini API key is not configured."
            )
        }
        val toolDeclarations = toolSchemaFor(request.availableTools)
        val payload = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "You are JARVIS, a personal Android assistant. Ask one short clarifying question when required details are missing. Never guess time, date, place, or recipient. Use only the provided tools and return a compact natural-language answer or function call."))))
            val contents = JSONArray()
            request.history.takeLast(10).forEach { turn ->
                contents.put(
                    JSONObject()
                        .put("role", turn.role)
                        .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
                )
            }
            contents.put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", request.utterance)))
            )
            put("contents", contents)
            put("tools", JSONArray().put(JSONObject().put("functionDeclarations", toolDeclarations)))
            put("generationConfig", JSONObject().put("temperature", 0.2))
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body)
            .build()
        var fallbackReason = "Gemini request failed."
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                client.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val detail = readApiError(response.body?.string().orEmpty(), apiKey)
                        fallbackReason = "HTTP ${response.code}${detail?.let { " $it" }.orEmpty()}"
                        Log.w(TAG, "Gemini request failed: $fallbackReason")
                        if ((response.code == 429 || response.code in 500..599) && attempt < MAX_ATTEMPTS - 1) {
                            delay(RETRY_DELAYS_MS[attempt])
                            return@use
                        }
                        return@withContext offlineFallback(request, fallbackReason)
                    }
                    val json = try {
                        JSONObject(response.body?.string().orEmpty())
                    } catch (exception: JSONException) {
                        fallbackReason = "Gemini returned an invalid response."
                        Log.w(TAG, fallbackReason, exception)
                        return@withContext offlineFallback(request, fallbackReason)
                    }
                    val toolStep = parseToolCall(json)
                    if (toolStep != null) {
                        val reply = when (toolStep.toolName) {
                            "meeting.create" -> "I will create the meeting and a linked departure reminder."
                            "calendar.cancel" -> "I found the meeting plan. Cancelling it will also cancel its linked reminder."
                            "reminder.create" -> "I will schedule that reminder."
                            "task.create" -> "I will add that task."
                            "communication.draft" -> "I drafted a communication for ${toolStep.parameters["recipient"] ?: "that contact"}."
                            else -> "I will handle that request."
                        }
                        return@withContext ReasoningResponse(reply, listOf(toolStep), engineName = "gemini")
                    }
                    val textReply = parseTextReply(json)
                    if (textReply != null) {
                        return@withContext ReasoningResponse(textReply, engineName = "gemini")
                    }
                    fallbackReason = "Gemini returned no usable text or function call."
                    Log.w(TAG, fallbackReason)
                    return@withContext offlineFallback(request, fallbackReason)
                }
            } catch (exception: IOException) {
                fallbackReason = "Network error (${exception.javaClass.simpleName})."
                Log.w(TAG, "Gemini request failed: $fallbackReason")
                if (attempt < MAX_ATTEMPTS - 1) {
                    delay(RETRY_DELAYS_MS[attempt])
                } else {
                    return@withContext offlineFallback(request, fallbackReason)
                }
            }
        }
        offlineFallback(request, fallbackReason)
    }

    private suspend fun offlineFallback(request: ReasoningRequest, reason: String): ReasoningResponse =
        OfflineRuleProvider().reason(request).copy(engineName = "offline", failureReason = reason)

    private fun readApiError(body: String, key: String): String? {
        return try {
            val error = JSONObject(body).optJSONObject("error") ?: return null
            val status = error.optString("status").takeIf { it.isNotBlank() }
            val message = error.optString("message")
                .replace(key, "[redacted]")
                .replace(Regex("""key=[^&\s]+"""), "key=[redacted]")
                .replace(Regex("""[\r\n\t]+"""), " ")
                .take(240)
            listOfNotNull(status, message.takeIf { it.isNotBlank() })
                .joinToString(": ")
                .takeIf { it.isNotBlank() }
        } catch (_: JSONException) {
            null
        }
    }
}

class CloudReasoningProvider(
    private val endpoint: String,
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient()
) : ReasoningProvider {
    override suspend fun reason(request: ReasoningRequest): ReasoningResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().put("user_utterance", request.utterance)
            .put("available_tools", request.availableTools).toString()
            .toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder().url(endpoint).addHeader("Authorization", "Bearer $apiKey").post(body).build()
        client.newCall(httpRequest).execute().use { response ->
            check(response.isSuccessful) { "Cloud reasoning failed: HTTP ${response.code}" }
            val json = JSONObject(response.body?.string().orEmpty())
            ReasoningResponse(json.optString("natural_language_reply", "Cloud response received."), engineName = "cloud")
        }
    }
}
