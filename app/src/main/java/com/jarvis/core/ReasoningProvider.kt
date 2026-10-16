package com.jarvis.core

import android.util.Log
import java.io.IOException
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
    val failureReason: String? = null
)

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
            it.role == "model" && it.text.contains("meeting", ignoreCase = true) &&
                (it.text.contains("time", ignoreCase = true) || it.text.contains("where", ignoreCase = true))
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
                    val question = when {
                        "time" in missing && "location" in missing -> "What time and where is the meeting?"
                        "time" in missing -> "What time is the meeting?"
                        else -> "Where is the meeting?"
                    }
                    ReasoningResponse(question, engineName = "offline")
                } else {
                    val meeting = checkNotNull(meetingRequest)
                    ReasoningResponse(
                        "I will create the meeting and a linked departure reminder.",
                        listOf(
                            PlanStep(
                                "meeting.create",
                                mapOf(
                                    "title" to "Meeting at ${meeting.location}",
                                    "hour" to meeting.hourOfDay.toString(),
                                    "minute" to meeting.minute.toString(),
                                    "location" to meeting.location
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
