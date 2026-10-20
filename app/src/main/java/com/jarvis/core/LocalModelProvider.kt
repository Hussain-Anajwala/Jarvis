package com.jarvis.core

import org.json.JSONObject

/**
 * Phase 3 prototype seam for **on-device neural inference** (docs/TRD.md §9). Not to be confused with
 * [OfflineRuleProvider], which is a hand-written rule parser, not a language model.
 *
 * No runtime or model ships with the app: a [LocalTextModel] would wrap LiteRT-LM (recommended) or llama.cpp, loaded
 * from a model file the user downloads on Wi-Fi. Until one exists, [LocalModelReasoningProvider] is unused in the app.
 */
interface LocalTextModel {
    /** Model identifier for traces, e.g. "gemma3-1b-it-int4". */
    val name: String
    /** Greedy (temperature 0) completion of [prompt], at most [maxTokens] new tokens; throws on failure. */
    suspend fun complete(prompt: String, maxTokens: Int): String
}

/**
 * A small model proposes **one** tool call from a compact list (a 1B model cannot handle the full cloud prompt within
 * an acceptable prefill time on a phone CPU). Its output is never trusted: it must be one JSON object naming an
 * allowed tool, and the same schema validation as cloud output applies. Anything else becomes a plain reply.
 */
class LocalModelReasoningProvider(
    private val model: LocalTextModel,
    private val tools: List<String> = COMPACT_TOOLS,
    private val maxTokens: Int = 64
) : ReasoningProvider {
    override suspend fun reason(request: ReasoningRequest): ReasoningResponse {
        val raw = try {
            model.complete(prompt(request.utterance), maxTokens)
        } catch (error: Exception) {
            return ReasoningResponse("", engineName = ENGINE, modelName = model.name, failureReason = "local model failed (${error.javaClass.simpleName})")
        }
        val step = parse(raw)
        return if (step == null) {
            ReasoningResponse("I'm not sure what to do with that offline. Try rephrasing, or turn cloud AI on.", engineName = ENGINE, modelName = model.name)
        } else {
            ReasoningResponse("", listOf(step), engineName = ENGINE, modelName = model.name)
        }
    }

    fun prompt(utterance: String): String =
        "Choose one tool for the user's request. Reply with only JSON: {\"tool\":\"<name>\",\"args\":{...}} " +
            "or {\"tool\":\"none\"}.\nTools: " + tools.joinToString("; ") { name ->
                val fields = ReasoningToolSchema.definition(name)?.requiredFields.orEmpty()
                if (fields.isEmpty()) name else "$name(${fields.joinToString()})"
            } + "\nRequest: " + utterance.take(300) + "\nJSON:"

    /** The first JSON object in [raw], if it names an allowed tool whose arguments pass the schema check. */
    fun parse(raw: String): PlanStep? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        val json = balancedObject(raw, start) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tool = obj.optString("tool").takeIf { it in tools } ?: return null
        val args = obj.optJSONObject("args")
        val params = args?.keys()?.asSequence()?.associateWith { args.get(it).toString() }.orEmpty()
        val step = ReasoningToolSchema.normalize(PlanStep(tool, params))
        return step.takeIf { ToolRouter.route(it, tools) !is ToolRoute.Reject }
    }

    private fun balancedObject(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        for (i in start until text.length) {
            val c = text[i]
            if (c == '"' && (i == 0 || text[i - 1] != '\\')) inString = !inString
            if (inString) continue
            if (c == '{') depth++
            if (c == '}' && --depth == 0) return text.substring(start, i + 1)
        }
        return null
    }

    companion object {
        const val ENGINE = "local-model"
        /** Everyday tools whose arguments are short; free-text tools (messages, email) stay with the cloud or rules. */
        val COMPACT_TOOLS = listOf(
            "set_alarm", "set_timer", "reminder.create", "task.create", "play_music", "media_control",
            "set_volume", "flashlight", "navigate", "get_weather", "count_new_messages", "daily_briefing"
        )
    }
}
