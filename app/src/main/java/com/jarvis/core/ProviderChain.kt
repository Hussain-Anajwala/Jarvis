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
    val requiredFields: List<String>
)

object ReasoningToolSchema {
    private val definitions = mapOf(
        "meeting.create" to listOf("title", "hour", "minute", "location", "date"),
        "reminder.create" to listOf("title"),
        "task.create" to listOf("title"),
        "communication.draft" to listOf("recipient", "body"),
        "alarm.handoff" to listOf("hour", "minute", "label"),
        "calendar.cancel" to emptyList(),
        "reminder.cancel" to emptyList(),
        "task.list" to emptyList(),
        "task.complete" to listOf("title")
    )

    fun forNames(names: List<String>): List<ReasoningToolDefinition> =
        names.distinct().mapNotNull { name ->
            definitions[name]?.let { fields ->
                ReasoningToolDefinition(name, "JARVIS tool: $name", fields)
            }
        }

    fun requiredFields(name: String): Set<String> =
        definitions[name]?.toSet() ?: emptySet()

    fun toGeminiJson(names: List<String>): JSONArray =
        JSONArray().apply {
            for (definition in forNames(names)) {
                val properties = JSONObject()
                definition.requiredFields.forEach { field ->
                    properties.put(field, JSONObject().put("type", "string"))
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

                val response = configured.provider.reason(request)
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
