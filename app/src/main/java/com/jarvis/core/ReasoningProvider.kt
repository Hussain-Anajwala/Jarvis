package com.jarvis.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class ReasoningRequest(val utterance: String, val availableTools: List<String>)
data class PlanStep(val toolName: String, val parameters: Map<String, String>)
data class ReasoningResponse(
    val reply: String,
    val steps: List<PlanStep> = emptyList(),
    val confidence: Double = 1.0
)

interface ReasoningProvider {
    suspend fun reason(request: ReasoningRequest): ReasoningResponse
}

class MockReasoningProvider : ReasoningProvider {
    override suspend fun reason(request: ReasoningRequest): ReasoningResponse {
        val text = request.utterance.lowercase()
        val cancellationIntent = Regex("""\b(cancel|delete|remove)\b""").containsMatchIn(text)
        val alarmRequest = AlarmRequestParser.parse(request.utterance)
        val meetingRequest = MeetingRequestParser.parse(request.utterance)
        return when {
            cancellationIntent ->
                ReasoningResponse("I found the meeting plan. Cancelling it will also cancel its linked reminder.", listOf(PlanStep("calendar.cancel", emptyMap())))
            Regex("""\b(message|text|email|send)\b""").containsMatchIn(text) ->
                CommunicationRequestParser.parse(request.utterance)?.let { communication ->
                    ReasoningResponse(
                        "I drafted a communication for ${communication.recipientName}.",
                        listOf(
                            PlanStep(
                                "communication.draft",
                                mapOf("recipient" to communication.recipientName, "body" to communication.body)
                            )
                        )
                    )
                } ?: ReasoningResponse(
                    "I could not identify the recipient. Try “message Maa that I will call soon.”",
                    listOf(PlanStep("communication.draft", mapOf("recipient" to "", "body" to "")))
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
                    )
                )
            }
            AlarmRequestParser.isAlarmIntent(request.utterance) ->
                ReasoningResponse(
                    "Please specify an alarm time with AM or PM, for example “set an alarm for 7 AM.”"
                )
            MeetingRequestParser.isMeetingIntent(request.utterance) -> {
                val missing = MeetingRequestParser.missingDetails(request.utterance)
                if (missing.isNotEmpty()) {
                    val question = buildList {
                        if ("time" in missing) add("What time is the meeting?")
                        if ("location" in missing) add("Where is the meeting?")
                    }.joinToString(" ")
                    ReasoningResponse(question)
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
                        )
                    )
                }
            }
            Regex("remind|reminder").containsMatchIn(text) ->
                ReasoningResponse("I will schedule that reminder.", listOf(PlanStep("reminder.create", mapOf("title" to request.utterance))))
            Regex("task|todo").containsMatchIn(text) ->
                ReasoningResponse("I will add that task.", listOf(PlanStep("task.create", mapOf("title" to request.utterance))))
            else -> ReasoningResponse("I can create meetings, reminders, and tasks. Try: “I have a meeting tomorrow at 10 AM at college.”")
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
            ReasoningResponse(json.optString("natural_language_reply", "Cloud response received."))
        }
    }
}
