package com.jarvis.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

data class ReasoningToolDefinition(
    val name: String,
    val description: String,
    val requiredFields: List<String>,
    val riskLevel: String = "low",
    val requiredPermissions: List<String> = emptyList(),
    val optionalFields: List<String> = emptyList()
) {
    val allFields: List<String> get() = requiredFields + optionalFields
}

object ReasoningToolSchema {
    private val definitions = listOf(
        ReasoningToolDefinition("meeting.create", "Create a calendar meeting; date is ISO-8601.", listOf("title", "hour", "minute", "location", "date"), requiredPermissions = listOf("READ_CALENDAR", "WRITE_CALENDAR")),
        ReasoningToolDefinition("reminder.create", "Create a reminder.", listOf("title")),
        ReasoningToolDefinition("task.create", "Create a task.", listOf("title")),
        ReasoningToolDefinition("communication.draft", "Draft a message to a named contact.", listOf("recipient", "body"), "medium", listOf("READ_CONTACTS")),
        ReasoningToolDefinition("calendar.cancel", "Cancel the active calendar plan after confirmation.", emptyList(), "medium", listOf("WRITE_CALENDAR")),
        ReasoningToolDefinition("reminder.cancel", "Cancel a reminder.", emptyList(), "medium"),
        ReasoningToolDefinition("task.list", "List tasks.", emptyList()),
        ReasoningToolDefinition("task.complete", "Complete a task by title.", listOf("title")),
        ReasoningToolDefinition("set_timer", "Open Clock to set a timer. duration_seconds must be positive.", listOf("duration_seconds"), requiredPermissions = listOf("SET_ALARM")),
        ReasoningToolDefinition("set_alarm", "Open Clock to set an alarm. Use 24-hour hour and minute values.", listOf("hour", "minute", "label"), requiredPermissions = listOf("SET_ALARM")),
        ReasoningToolDefinition("play_youtube", "Search YouTube for a video and open the best result, or hand off a YouTube search.", listOf("query"), requiredPermissions = listOf("INTERNET")),
        ReasoningToolDefinition("play_music", "Hand a music search to the user's music app. Autoplay is not guaranteed.", listOf("query")),
        ReasoningToolDefinition("media_control", "Send a media key to the active media session. action is play, pause, next, or previous.", listOf("action")),
        ReasoningToolDefinition("set_volume", "Set music volume as a percentage from 0 to 100.", listOf("level")),
        ReasoningToolDefinition("flashlight", "Turn the device torch on or off. enabled is true or false.", listOf("enabled")),
        ReasoningToolDefinition("call_contact", "Resolve a contact locally and request confirmation before calling.", listOf("name"), "medium", listOf("READ_CONTACTS", "CALL_PHONE")),
        ReasoningToolDefinition("navigate", "Open navigation to the requested destination in a maps app.", listOf("destination")),
        ReasoningToolDefinition("set_favorite_song", "Save the user's favorite song locally on the device.", listOf("song")),
        ReasoningToolDefinition("play_favorite_song", "Play the user's locally saved favorite song in their music app.", emptyList()),
        ReasoningToolDefinition("get_favorite_song", "Tell the user which favorite song is saved locally.", emptyList()),
        ReasoningToolDefinition("clear_favorite_song", "Forget the locally saved favorite song.", emptyList()),
        ReasoningToolDefinition("count_new_messages", "Count new message notifications captured on this device. Optional app (e.g. WhatsApp) and contact filters.", emptyList(), optionalFields = listOf("app", "contact")),
        ReasoningToolDefinition("list_new_messages", "List new message notifications captured on this device. Optional app and contact filters.", emptyList(), optionalFields = listOf("app", "contact")),
        ReasoningToolDefinition("summarize_new_messages", "Summarize new message notifications. Sends message text to the cloud model only after the user confirms.", emptyList(), "medium", optionalFields = listOf("app", "contact"))
    ).associateBy { it.name }

    fun forNames(names: List<String>): List<ReasoningToolDefinition> =
        names.distinct().mapNotNull(definitions::get)

    fun requiredFields(name: String): Set<String> =
        definitions[name]?.requiredFields?.toSet() ?: emptySet()

    fun definition(name: String): ReasoningToolDefinition? = definitions[name]

    /**
     * Parses a model's function arguments. Unknown keys are rejected; optional keys sent as null are
     * ignored; numbers and booleans become strings (ranges are checked later by [validate]).
     */
    fun parseArguments(name: String, args: JSONObject): Pair<Map<String, String>?, String?> {
        val definition = definitions[name] ?: return null to "Unrecognized function name."
        val values = mutableMapOf<String, String>()
        for (key in args.keys()) {
            if (key !in definition.allFields) return null to "Unexpected function argument."
            when (val value = args.opt(key)) {
                null, JSONObject.NULL -> if (key in definition.requiredFields) return null to "Required function arguments are missing."
                is String -> values[key] = value
                is Boolean -> values[key] = value.toString()
                is Number -> values[key] = if (value.toDouble() % 1.0 == 0.0) value.toLong().toString() else value.toString()
                else -> return null to "Function arguments must be strings."
            }
        }
        if (definition.requiredFields.any { values[it].isNullOrBlank() }) {
            return null to "Required function arguments are missing."
        }
        return values to null
    }

    /** Drops blank optional arguments, which models often send as empty strings. */
    fun normalize(step: PlanStep): PlanStep {
        val definition = definitions[step.toolName] ?: return step
        return step.copy(parameters = step.parameters.filterNot { (key, value) ->
            key in definition.optionalFields && value.isBlank()
        })
    }

    fun validate(step: PlanStep): String? {
        val definition = definitions[step.toolName] ?: return "Tool is not registered."
        if (definition.requiredFields.any { step.parameters[it].isNullOrBlank() }) {
            return "Required tool arguments are missing."
        }
        if (step.parameters.keys.any { it !in definition.allFields }) {
            return "Unexpected tool argument."
        }
        val values = step.parameters
        return when (step.toolName) {
            "meeting.create" -> validHourMinute(values["hour"], values["minute"])
                ?: if (runCatching { java.time.LocalDate.parse(values.getValue("date")) }.isFailure) "Meeting date is invalid." else null
            "set_alarm" -> validHourMinute(values["hour"], values["minute"])
            "set_timer" -> if (values["duration_seconds"]?.toIntOrNull() in 1..86_400) null
                else "Timer duration must be between 1 second and 24 hours."
            "play_youtube", "play_music", "call_contact", "navigate", "set_favorite_song" ->
                if (values.values.any(String::isBlank)) "Tool argument cannot be blank." else null
            "media_control" -> if (values["action"] in setOf("play", "pause", "next", "previous")) null
                else "Media action must be play, pause, next, or previous."
            "set_volume" -> if (values["level"]?.toIntOrNull() in 0..100) null
                else "Volume level must be from 0 to 100."
            "flashlight" -> if (values["enabled"] == "true" || values["enabled"] == "false") null
                else "Flashlight enabled must be true or false."
            else -> null
        }
    }

    private fun validHourMinute(hour: String?, minute: String?): String? {
        val parsedHour = hour?.toIntOrNull()
        val parsedMinute = minute?.toIntOrNull()
        return if (parsedHour in 0..23 && parsedMinute in 0..59) null
        else "Time must contain a valid 24-hour hour and minute."
    }

    fun toGeminiJson(names: List<String>): JSONArray =
        JSONArray().apply {
            for (definition in forNames(names)) {
                val properties = JSONObject()
                definition.requiredFields.forEach { field ->
                    properties.put(field, JSONObject().put("type", "string"))
                }
                definition.optionalFields.forEach { field ->
                    properties.put(field, JSONObject().put("type", "string").put("nullable", true))
                }
                val parameters = JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                if (definition.requiredFields.isNotEmpty()) {
                    parameters.put("required", JSONArray(definition.requiredFields))
                }
                put(
                    JSONObject()
                        .put("name", definition.name)
                        .put("description", definition.description)
                        .put("parameters", parameters)
                )
            }
        }

    fun toOpenAiJson(names: List<String>): JSONArray =
        JSONArray().apply {
            for (definition in forNames(names)) {
                val properties = JSONObject()
                definition.requiredFields.forEach { field ->
                    properties.put(field, JSONObject().put("type", "string"))
                }
                // Optional fields may be null: models fill every declared property under strict validation.
                definition.optionalFields.forEach { field ->
                    properties.put(field, JSONObject().put("type", JSONArray().put("string").put("null")))
                }
                put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", definition.name)
                                .put("description", definition.description)
                                .put(
                                    "parameters",
                                    JSONObject()
                                        .put("type", "object")
                                        .put("properties", properties)
                                        .put("required", JSONArray(definition.requiredFields))
                                        .put("additionalProperties", false)
                                )
                        )
                )
            }
        }
}

object ToolRiskGate {
    fun requiresConfirmation(toolName: String): Boolean =
        ReasoningToolSchema.definition(toolName)?.riskLevel in setOf("medium", "high")
}

interface ProviderCooldownStore {
    fun readUntil(providerId: String): Long
    fun writeUntil(providerId: String, untilEpochMs: Long)
    fun clear(providerId: String)
}

class SharedPreferencesProviderCooldownStore(context: Context) : ProviderCooldownStore {
    private val preferences =
        context.getSharedPreferences("reasoning_provider_cooldowns", Context.MODE_PRIVATE)

    override fun readUntil(providerId: String): Long =
        preferences.getLong(providerId, 0L)

    override fun writeUntil(providerId: String, untilEpochMs: Long) {
        check(preferences.edit().putLong(providerId, untilEpochMs).commit()) {
            "Could not persist provider cooldown."
        }
    }

    override fun clear(providerId: String) {
        check(preferences.edit().remove(providerId).commit()) {
            "Could not clear provider cooldown."
        }
    }
}

class MemoryProviderCooldownStore : ProviderCooldownStore {
    private val values = ConcurrentHashMap<String, Long>()
    override fun readUntil(providerId: String): Long = values[providerId] ?: 0L
    override fun writeUntil(providerId: String, untilEpochMs: Long) {
        values[providerId] = untilEpochMs
    }
    override fun clear(providerId: String) {
        values.remove(providerId)
    }
}

data class ConfiguredReasoningProvider(
    val id: String,
    val provider: ReasoningProvider
)

class ResilientReasoningProvider(
    private val providers: List<ConfiguredReasoningProvider>,
    private val offlineProvider: ReasoningProvider = OfflineRuleProvider(),
    private val cooldownStore: ProviderCooldownStore = MemoryProviderCooldownStore(),
    private val clock: () -> Long = System::currentTimeMillis
) : ReasoningProvider {
    override suspend fun reason(request: ReasoningRequest): ReasoningResponse =
        withContext(Dispatchers.IO) {
            val attempts = mutableListOf<ModelAttempt>()
            val failures = mutableListOf<String>()
            for (configured in providers) {
                val now = clock()
                val cooldownUntil = cooldownStore.readUntil(configured.id)
                if (cooldownUntil > now) {
                    attempts += ModelAttempt(
                        modelName = "provider-cooldown",
                        httpStatus = null,
                        detail = "Provider cooling down until $cooldownUntil.",
                        providerName = configured.id
                    )
                    failures += "${configured.id} is cooling down."
                    continue
                }

                val response = try {
                    configured.provider.reason(request)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // A provider bug must fail over like a provider outage, never crash the app.
                    failures += "${configured.id}: unexpected ${error.javaClass.simpleName}"
                    attempts += ModelAttempt("unknown", null, "Unexpected ${error.javaClass.simpleName}", configured.id)
                    continue
                }
                attempts += response.attempts.map { attempt ->
                    if (attempt.providerName == "gemini") attempt.copy(providerName = configured.id)
                    else attempt
                }
                if (response.failureReason == null && response.engineName != "offline") {
                    cooldownStore.clear(configured.id)
                    return@withContext response.copy(
                        engineName = configured.id,
                        attempts = attempts.toList()
                    )
                }

                val failure = response.failureReason ?: "Provider returned offline fallback."
                failures += "${configured.id}: $failure"
                val cooldown = failureCooldownMs(response.attempts)
                if (cooldown > 0) cooldownStore.writeUntil(configured.id, now + cooldown)
            }

            val offline = offlineProvider.reason(request)
            offline.copy(
                failureReason = failures.takeIf { it.isNotEmpty() }?.joinToString("; "),
                attempts = attempts.toList()
            )
        }

    private fun failureCooldownMs(attempts: List<ModelAttempt>): Long {
        val details = attempts.mapNotNull { it.detail }.joinToString(" ").lowercase()
        if (
            ("quota" in details && ("exceeded" in details || "billing" in details)) ||
            "local gemini request budget reached" in details
        ) {
            val now = Instant.ofEpochMilli(clock()).atZone(PACIFIC_ZONE)
            return now.toLocalDate().plusDays(1).atStartOfDay(PACIFIC_ZONE)
                .toInstant().toEpochMilli() - now.toInstant().toEpochMilli()
        }
        val retryAfter = attempts.lastOrNull { it.httpStatus == 429 }?.retryAfterMs
        return when {
            attempts.any { it.httpStatus == 503 } -> 60_000L
            attempts.any { it.httpStatus == 404 } -> 60 * 60_000L
            attempts.any { it.httpStatus == 401 || it.httpStatus == 403 } -> 60 * 60_000L
            attempts.any { it.httpStatus == 429 } -> retryAfter?.coerceAtLeast(1_000L) ?: 60_000L
            else -> 0L
        }
    }

    companion object {
        private val PACIFIC_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
    }
}

object ReasoningProviderFactory {
    fun create(context: Context): ReasoningProvider {
        val providers = buildList {
            if (com.jarvis.BuildConfig.GROQ_API_KEY.isNotBlank()) {
                add(
                    ConfiguredReasoningProvider(
                        "groq",
                        GroqReasoningProvider(
                            apiKey = com.jarvis.BuildConfig.GROQ_API_KEY,
                            configuredModelName = com.jarvis.BuildConfig.GROQ_MODEL,
                            modelCache = SharedPreferencesModelNameCache(context, "groq_model_cache")
                        )
                    )
                )
            }

            listOf(
                "gemini-primary" to com.jarvis.BuildConfig.GEMINI_API_KEY,
                "gemini-secondary" to com.jarvis.BuildConfig.GEMINI_API_KEY_2
            ).forEach { (providerId, apiKey) ->
                if (apiKey.isNotBlank()) {
                    add(
                        ConfiguredReasoningProvider(
                            providerId,
                            GeminiReasoningProvider(
                                apiKey = apiKey,
                                configuredModelName = com.jarvis.BuildConfig.GEMINI_MODEL,
                                providerName = providerId,
                                modelCache = SharedPreferencesModelNameCache(
                                    context,
                                    "${providerId}_model_cache"
                                ),
                                quotaTracker = SharedPreferencesGeminiQuotaTracker(context),
                                dailyBudget = com.jarvis.BuildConfig.GEMINI_DAILY_BUDGET,
                                minuteBudget = com.jarvis.BuildConfig.GEMINI_MINUTE_BUDGET
                            )
                        )
                    )
                }
            }
        }
        return ResilientReasoningProvider(
            providers = providers,
            cooldownStore = SharedPreferencesProviderCooldownStore(context)
        )
    }
}

/** One system prompt for every cloud provider so behaviour and evaluation stay comparable. */
object JarvisSystemPrompt {
    fun build(now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): String {
        val today = now.toLocalDate()
        fun day(date: java.time.LocalDate) = date.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)
        // Models are unreliable at weekday arithmetic, so the next seven dates are spelled out.
        val upcoming = (1L..7L).joinToString(", ") { offset -> today.plusDays(offset).let { "${day(it)} $it" } }
        return "You are JARVIS, a personal Android assistant. Today is ${day(today)}, $today " +
            "(time zone ${now.zone.id}). Upcoming dates: $upcoming. A weekday name means its next date in that list. " +
            "Pass meeting dates as YYYY-MM-DD; never ask the user for a date format. Never guess a time, place, or " +
            "recipient: when one is missing, ask one short question. If an hour is ambiguous (for example \"around 6\" " +
            "without AM or PM), ask whether it is AM or PM. Use the event the user names as the title (for example " +
            "\"Dentist visit\" for \"a dentist visit\"); only when no event name is given use \"Meeting at <location>\" " +
            "instead of asking. For a cancellation request call calendar.cancel directly: the app shows its own " +
            "confirmation dialog, so do not ask for confirmation in text. Use only the provided tools; reply briefly " +
            "in plain language when no tool fits."
    }
}
