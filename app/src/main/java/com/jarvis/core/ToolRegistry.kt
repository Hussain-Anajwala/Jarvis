package com.jarvis.core

/** What happens outside JARVIS's own records when a tool runs. */
enum class SideEffect(val label: String) {
    NONE("reads only"),
    LOCAL("changes JARVIS's records or phone settings"),
    HANDOFF("opens another app; the user finishes there"),
    ACCOUNT("writes to an account the user owns (calendar)"),
    COMMUNICATION("can reach another person (call, message, email)")
}

/** What the user must do before the tool's effect happens. */
enum class Approval(val label: String) {
    NONE("none"),
    CONFIRM_ON_SCREEN("an on-screen confirmation, dialog, or the user's own tap in the other app"),
    PLAN_APPROVAL("approval of the exact plan step")
}

/** How JARVIS can check the outcome, independently of the tool's own reply. */
enum class VerifyMethod(val label: String) {
    RECORD_AND_ALARM("stored record plus Android's alarm registry"),
    RECORD("stored record"),
    MEDIA_SESSION("a media session showing the new item playing"),
    OS_STATE("Android state (volume, torch, next alarm)"),
    HANDOFF_ONLY("not observable: the other app reports nothing back"),
    ANSWER("a local answer; nothing to verify")
}

sealed interface ArgType {
    data object Text : ArgType
    data class IntRange(val min: Int, val max: Int) : ArgType
    data object Bool : ArgType
    data class OneOf(val values: Set<String>) : ArgType
    data object IsoDate : ArgType
    data object EpochMs : ArgType
    data object Zone : ArgType
}

data class ArgSpec(val name: String, val type: ArgType, val required: Boolean, val allowBlank: Boolean = false)

data class ToolSpec(
    val id: String,
    val description: String,
    val args: List<ArgSpec>,
    val permissions: List<String>,
    val approval: Approval,
    val sideEffect: SideEffect,
    /** Repeating the call cannot create a second effect. */
    val idempotent: Boolean,
    val verify: VerifyMethod,
    val limitations: String,
    /** "model" (proposed by a model or the offline parser) or "plan" (a PlanExecutor step). */
    val origin: String
)

sealed interface ToolCheck {
    data class Ok(val spec: ToolSpec, val args: Map<String, String>) : ToolCheck
    data class Unknown(val reason: String) : ToolCheck
    data class Invalid(val spec: ToolSpec, val reason: String) : ToolCheck
}

/**
 * Phase 8B: one typed catalogue of every action JARVIS can take — model tools (ReasoningToolSchema) and plan steps
 * (PlanTools) — with argument types, permissions, approval, side effect, idempotency, and how the outcome is verified.
 * [check] runs before any routing or execution: an unknown tool or a badly typed argument fails safely with a reason,
 * whatever proposed it. Approval and confirmation flows themselves stay where they are (ToolRouter, PlanExecutor,
 * the confirmation dialogs); the registry makes them inspectable and is tested to agree with them.
 */
object ToolRegistry {
    private fun t(name: String, required: Boolean = true, allowBlank: Boolean = false) = ArgSpec(name, ArgType.Text, required, allowBlank)
    private fun i(name: String, min: Int, max: Int) = ArgSpec(name, ArgType.IntRange(min, max), true)

    private data class Meta(val approval: Approval, val effect: SideEffect, val idempotent: Boolean, val verify: VerifyMethod, val limits: String, val types: List<ArgSpec> = emptyList())

    private val meta: Map<String, Meta> = mapOf(
        "meeting.create" to Meta(Approval.CONFIRM_ON_SCREEN, SideEffect.ACCOUNT, false, VerifyMethod.RECORD, "Shows the meeting and writes it only on an explicit yes within 5 minutes; the venue is never invented and a generic place is asked about. The calendar provider is queried after the insert.",
            listOf(t("title", allowBlank = true), i("hour", 0, 23), i("minute", 0, 59), t("location", allowBlank = true), ArgSpec("date", ArgType.IsoDate, true))),
        "reminder.create" to Meta(Approval.NONE, SideEffect.NONE, true, VerifyMethod.ANSWER, "A model's call has no time, so JARVIS asks; real reminders come from the reminder dialogue."),
        "task.create" to Meta(Approval.NONE, SideEffect.LOCAL, false, VerifyMethod.RECORD, "Stored only; tasks have no due-date notification."),
        "communication.draft" to Meta(Approval.CONFIRM_ON_SCREEN, SideEffect.COMMUNICATION, false, VerifyMethod.HANDOFF_ONLY, "JARVIS prepares a draft; the user sends it in Messages/WhatsApp. Sending is not observable."),
        "calendar.cancel" to Meta(Approval.CONFIRM_ON_SCREEN, SideEffect.ACCOUNT, true, VerifyMethod.RECORD, "Cancels the active meeting plan after a confirmation dialog."),
        "reminder.cancel" to Meta(Approval.NONE, SideEffect.NONE, true, VerifyMethod.ANSWER, "Explains how to cancel; reminder cancellation runs in the reminder dialogue."),
        "task.list" to Meta(Approval.NONE, SideEffect.NONE, true, VerifyMethod.ANSWER, ""),
        "task.complete" to Meta(Approval.NONE, SideEffect.LOCAL, true, VerifyMethod.RECORD, ""),
        "set_timer" to Meta(Approval.NONE, SideEffect.LOCAL, false, VerifyMethod.RECORD_AND_ALARM, "With the Clock-app setting, the timer is a handoff and not observable.", listOf(i("duration_seconds", 1, 86_400))),
        "set_alarm" to Meta(Approval.NONE, SideEffect.HANDOFF, false, VerifyMethod.OS_STATE, "Opens Clock; Android's next-alarm time is the only state apps can read.", listOf(i("hour", 0, 23), i("minute", 0, 59), t("label", allowBlank = true))),
        "play_youtube" to Meta(Approval.NONE, SideEffect.HANDOFF, true, VerifyMethod.HANDOFF_ONLY, "Opens YouTube; playback is not observed."),
        "play_music" to Meta(Approval.NONE, SideEffect.HANDOFF, true, VerifyMethod.MEDIA_SESSION, "Playing is claimed only when a media session shows it.",
            listOf(t("query"), ArgSpec("app", ArgType.OneOf(setOf("spotify", "youtube_music")), false))),
        "media_control" to Meta(Approval.NONE, SideEffect.LOCAL, false, VerifyMethod.MEDIA_SESSION, "Media keys are fire-and-forget; replies say when unconfirmed.", listOf(ArgSpec("action", ArgType.OneOf(setOf("play", "pause", "next", "previous")), true))),
        "set_volume" to Meta(Approval.NONE, SideEffect.LOCAL, true, VerifyMethod.OS_STATE, "", listOf(i("level", 0, 100))),
        "flashlight" to Meta(Approval.NONE, SideEffect.LOCAL, true, VerifyMethod.OS_STATE, "", listOf(ArgSpec("enabled", ArgType.Bool, true))),
        "call_contact" to Meta(Approval.CONFIRM_ON_SCREEN, SideEffect.COMMUNICATION, false, VerifyMethod.HANDOFF_ONLY, "Always needs an on-screen confirmation; the call itself is not observed."),
        "navigate" to Meta(Approval.NONE, SideEffect.HANDOFF, true, VerifyMethod.HANDOFF_ONLY, "Opens Maps."),
        "set_favorite_song" to Meta(Approval.NONE, SideEffect.LOCAL, true, VerifyMethod.RECORD, ""),
        "play_favorite_song" to Meta(Approval.NONE, SideEffect.HANDOFF, true, VerifyMethod.MEDIA_SESSION, ""),
        "get_favorite_song" to Meta(Approval.NONE, SideEffect.NONE, true, VerifyMethod.ANSWER, ""),
        "clear_favorite_song" to Meta(Approval.NONE, SideEffect.LOCAL, true, VerifyMethod.RECORD, ""),
        "count_new_messages" to Meta(Approval.NONE, SideEffect.NONE, true, VerifyMethod.ANSWER, "Local notification records only."),
        "list_new_messages" to Meta(Approval.NONE, SideEffect.NONE, true, VerifyMethod.ANSWER, "Local notification records only."),
        "email_unread_count" to Meta(Approval.NONE, SideEffect.NONE, true, VerifyMethod.ANSWER, "Gmail read-only."),
        "email_today" to Meta(Approval.NONE, SideEffect.NONE, true, VerifyMethod.ANSWER, "Gmail read-only."),
        "summarize_emails" to Meta(Approval.CONFIRM_ON_SCREEN, SideEffect.NONE, true, VerifyMethod.ANSWER, "Sends snippets to a cloud model only after the consent dialog."),
        "summarize_new_messages" to Meta(Approval.CONFIRM_ON_SCREEN, SideEffect.NONE, true, VerifyMethod.ANSWER, "Sends message text to a cloud model only after the consent dialog."),
        "compose_email" to Meta(Approval.CONFIRM_ON_SCREEN, SideEffect.COMMUNICATION, false, VerifyMethod.HANDOFF_ONLY, "JARVIS drafts; the user reviews and sends from the mail app. Sending is not observable."),
        "daily_briefing" to Meta(Approval.NONE, SideEffect.NONE, true, VerifyMethod.ANSWER, ""),
        "get_weather" to Meta(Approval.NONE, SideEffect.NONE, true, VerifyMethod.ANSWER, ""),
        "remember_fact" to Meta(Approval.NONE, SideEffect.LOCAL, true, VerifyMethod.RECORD, "Secrets are refused."),
        "forget_memory" to Meta(Approval.CONFIRM_ON_SCREEN, SideEffect.LOCAL, true, VerifyMethod.RECORD, "Shows the one matching memory and deletes it only on an explicit yes within 5 minutes, and only if it hasn't changed since."),
        "list_memories" to Meta(Approval.NONE, SideEffect.NONE, true, VerifyMethod.ANSWER, "Ambiguous requests are asked about, not answered with everything.")
    )

    /** Plan steps (PlanTools). Their flags are tested to match the PlanTool instances. */
    private val planSpecs = listOf(
        ToolSpec("reminder.schedule", "Schedule a reminder at an exact time.",
            listOf(t("title"), ArgSpec("at", ArgType.EpochMs, true), ArgSpec("zone", ArgType.Zone, true)), listOf("USE_EXACT_ALARM", "POST_NOTIFICATIONS"),
            Approval.PLAN_APPROVAL, SideEffect.LOCAL, true, VerifyMethod.RECORD_AND_ALARM, "Delivery itself is checked by the reminder engine, not by the plan step.", "plan"),
        ToolSpec("timer.start", "Start a JARVIS timer.", listOf(i("seconds", 1, 86_400), t("label", required = false, allowBlank = true)), listOf("USE_EXACT_ALARM"),
            Approval.PLAN_APPROVAL, SideEffect.LOCAL, false, VerifyMethod.RECORD_AND_ALARM, "Not idempotent: an interrupted start needs the user, never a blind retry.", "plan"),
        ToolSpec("reminders.cancel_all", "Cancel every pending standalone reminder.", emptyList(), emptyList(),
            Approval.PLAN_APPROVAL, SideEffect.LOCAL, true, VerifyMethod.RECORD_AND_ALARM, "Removes the user's data, so it always needs approval of that exact step.", "plan")
    )

    val specs: List<ToolSpec> by lazy {
        ReasoningToolSchema.all().map { d ->
            val m = meta[d.name] ?: error("no registry metadata for ${d.name}")
            val typed = m.types.associateBy { it.name }
            val args = d.requiredFields.map { typed[it]?.copy(required = true) ?: t(it) } +
                d.optionalFields.map { typed[it]?.copy(required = false) ?: t(it, required = false) }
            ToolSpec(d.name, d.description, args, d.requiredPermissions, m.approval, m.effect, m.idempotent, m.verify, m.limits, "model")
        } + planSpecs
    }

    private val byId by lazy { specs.associateBy { it.id } }

    fun spec(id: String): ToolSpec? = byId[id]

    /** Validates a proposed call. Unknown tools and wrongly typed arguments never reach routing or execution. */
    fun check(id: String, args: Map<String, String>): ToolCheck {
        val spec = byId[id] ?: return ToolCheck.Unknown("JARVIS has no action called \"${id.take(40)}\"")
        val known = spec.args.associateBy { it.name }
        args.keys.firstOrNull { it !in known }?.let { return ToolCheck.Invalid(spec, "unexpected argument \"${it.take(30)}\"") }
        for (a in spec.args) {
            val v = args[a.name]
            if (v.isNullOrBlank()) {
                if (a.required && !(a.allowBlank && v != null)) return ToolCheck.Invalid(spec, "missing ${a.name}")
                continue
            }
            typeError(a, v.trim())?.let { return ToolCheck.Invalid(spec, it) }
        }
        return ToolCheck.Ok(spec, args)
    }

    private fun typeError(a: ArgSpec, v: String): String? = when (val type = a.type) {
        ArgType.Text -> null
        is ArgType.IntRange -> {
            val n = v.toDoubleOrNull()?.takeIf { it == Math.floor(it) }?.toLong()
            if (n != null && n in type.min..type.max) null else "${a.name} must be a whole number from ${type.min} to ${type.max}"
        }
        ArgType.Bool -> if (v.lowercase() in setOf("true", "false")) null else "${a.name} must be true or false"
        is ArgType.OneOf -> if (v.lowercase() in type.values) null else "${a.name} must be one of ${type.values.sorted().joinToString()}"
        ArgType.IsoDate -> if (runCatching { java.time.LocalDate.parse(v) }.isSuccess) null else "${a.name} must be a date like 2026-10-21"
        ArgType.EpochMs -> if (v.toLongOrNull()?.let { it > 0 } == true) null else "${a.name} must be a time in epoch milliseconds"
        ArgType.Zone -> if (runCatching { java.time.ZoneId.of(v) }.isSuccess) null else "${a.name} must be a time zone"
    }
}
