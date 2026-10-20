package com.jarvis.core

/** Where one request is answered. */
enum class RouteTarget {
    /** A deterministic on-device handler (clock commands, email dialogue, memory questions, secret refusal): no model. */
    ON_DEVICE,
    /** The offline rule parser's tool call is used: no network. */
    LOCAL_PARSER,
    /** The cloud provider chain (Groq, then Gemini, then the offline parser on failure). */
    CLOUD
}

/** A routing decision and the rule that made it, recorded so every route can be checked afterwards. */
data class RouteDecision(val target: RouteTarget, val rule: String)

/**
 * Phase 2 adaptive local/cloud router. Rules, in order (the first that applies wins):
 *  1. privacy — text that looks like a password, PIN, card/ID number, or key never leaves the phone;
 *  2. on-device handlers — clock stop/snooze commands, the email dialogue, reminders and goal plans, and questions about saved memories are
 *     answered on the phone (a model would only add latency, cost, and the risk of a wrong action);
 *  3. recognised phone commands — the local phone parser (as in the earlier hybrid);
 *  4. cloud unavailable — no key, or the provider chain is cooling down after failures: use the offline parser;
 *  5. a confident local parse — the offline parser proposed a tool call that passes the schema check and is not a
 *     tool whose arguments are free text a rule parser can't judge (message bodies, email requests), and JARVIS is
 *     not in the middle of a clarifying exchange (the model that asked the question keeps the context);
 *  6. otherwise the cloud.
 * Rule 5 was designed after inspecting the offline baseline (2026-10-21): every offline tool call with the right tool
 * also had the right arguments, and its wrong calls were drafts with missing text. It is tested on held-out items.
 */
object AdaptiveRouter {
    /** Tools whose arguments are free text: a rule parser's version is not trusted when a model is available. */
    val NEEDS_UNDERSTANDING = setOf("communication.draft", "compose_email", "remember_fact", "summarize_new_messages", "summarize_emails")

    fun decide(
        utterance: String,
        offlineStep: PlanStep?,
        availableTools: Collection<String>,
        cloudAvailable: Boolean,
        /** The previous assistant turn asked a question (this utterance is probably its answer). */
        answeringQuestion: Boolean = false
    ): RouteDecision {
        if (SensitiveData.isSecret(utterance)) return RouteDecision(RouteTarget.ON_DEVICE, "privacy: looks like a credential or ID number")
        if (ClockCommandParser.parse(utterance) != null) return RouteDecision(RouteTarget.ON_DEVICE, "on-device: clock stop/snooze command")
        if (EmailDialogue.start(utterance) != null) return RouteDecision(RouteTarget.ON_DEVICE, "on-device: email dialogue")
        if (ReminderCommands.parse(utterance) != null) return RouteDecision(RouteTarget.ON_DEVICE, "on-device: reminder or plan command")
        if (GoalDialogue.begin(utterance, java.time.ZonedDateTime.now()) != null) return RouteDecision(RouteTarget.ON_DEVICE, "on-device: goal plan")
        if (MemoryQueryPolicy.localQuestion(utterance) != null) return RouteDecision(RouteTarget.ON_DEVICE, "on-device: memory question")
        if (ToolRouter.usesLocalPhoneParser(utterance)) return RouteDecision(RouteTarget.LOCAL_PARSER, "local: recognised phone command")
        if (!cloudAvailable) return RouteDecision(RouteTarget.LOCAL_PARSER, "local: cloud unavailable")
        if (!answeringQuestion && offlineStep != null && confident(offlineStep, availableTools)) {
            return RouteDecision(RouteTarget.LOCAL_PARSER, "local: confident parse (${offlineStep.toolName})")
        }
        return RouteDecision(RouteTarget.CLOUD, "cloud: needs language understanding")
    }

    private fun confident(step: PlanStep, availableTools: Collection<String>): Boolean {
        val normalized = ReasoningToolSchema.normalize(step)
        if (normalized.toolName in NEEDS_UNDERSTANDING) return false
        return ToolRouter.route(normalized, availableTools) !is ToolRoute.Reject
    }
}
