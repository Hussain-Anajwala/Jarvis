package com.jarvis.core

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** What the phone itself decides for one utterance, before any model is involved. */
data class Interpretation(val intent: String, val args: Map<String, String> = emptyMap())

/**
 * Answers to a pending proposal ("yes", "haan, wahi plan approve hai", "nahi, woh mat karna"). A yes or no is only ever
 * read relative to a proposal that is actually waiting; with nothing pending it approves nothing.
 */
object ApprovalAnswer {
    enum class Kind { YES, NO, UNCLEAR }
    private val yes = Regex(
        """^\s*(yes|yeah|yep|yup|sure|ok(ay)?|go ahead|do it|approve[ds]?|run it|haan|haa|ha|ji|theek hai|thik hai)\b""",
        RegexOption.IGNORE_CASE
    )
    /** "No" wins over "yes" words later in the sentence: "nahi, woh mat karna". */
    private val no = Regex("""^\s*(no|nope|nah|cancel|stop|don'?t|never ?mind|reject|nahi|nahin|mat)\b""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Kind = when {
        no.containsMatchIn(text) -> Kind.NO
        yes.containsMatchIn(text) -> Kind.YES
        else -> Kind.UNCLEAR
    }

    /** A short bare yes/no ("yes", "haan") that only makes sense as an answer. */
    fun isBare(text: String): Boolean = parse(text) != Kind.UNCLEAR && text.trim().split(Regex("""\s+""")).size <= 3
}

/**
 * The on-device decision chain, in the same order as the app (JarvisViewModel.handlePendingDialogue, then JarvisCore's
 * local handlers): pending approval → multi-step plan → reminder commands → goal → clock stop/snooze → memory question
 * → the adaptive router's local parse (phone commands, confident offline parses). "none" means the cloud. Used by the app for the
 * nothing-pending yes/no rule and by the evaluation, so the measured behaviour is the shipped behaviour.
 */
object OnDeviceIntents {
    suspend fun classify(text: String, now: ZonedDateTime, pending: String? = null): Interpretation {
        if (pending == "plan_approval") {
            return when (ApprovalAnswer.parse(text)) {
                ApprovalAnswer.Kind.YES -> Interpretation("approval.yes")
                ApprovalAnswer.Kind.NO -> Interpretation("approval.no")
                ApprovalAnswer.Kind.UNCLEAR -> Interpretation("approval.unclear")
            }
        }
        // A bare yes/no with nothing waiting approves or rejects nothing.
        if (pending == null && ApprovalAnswer.isBare(text)) return Interpretation("approval.nothing_pending")
        PlanParser.parse(text, now)?.let { parsed ->
            return parsed.missing?.let { Interpretation("clarify", mapOf("missing" to "time")) }
                ?: Interpretation("plan.multi", mapOf("steps" to parsed.steps.joinToString(",") { it.tool }))
        }
        ReminderCommands.parse(text)?.let { command ->
            return when (command) {
                is ReminderCommand.Create -> when (val step = ReminderDialogue.start(text, now)) {
                    is ReminderStep.Ready -> Interpretation("reminder.create", mapOf(
                        "title" to step.title, "hour" to "${step.at.hour}", "minute" to "${step.at.minute}",
                        "day" to day(step.at, now)))
                    is ReminderStep.Ask -> Interpretation("clarify", mapOf("missing" to when (step.slot) {
                        ReminderSlot.TITLE -> "title"; ReminderSlot.TIME -> "time"; ReminderSlot.AMPM -> "ampm"
                    }))
                    ReminderStep.Cancelled -> Interpretation("none")
                }
                ReminderCommand.List -> Interpretation("reminder.list")
                is ReminderCommand.Cancel -> Interpretation("reminder.cancel", mapOf("latest" to "${command.latest}"))
                is ReminderCommand.PauseGoal -> Interpretation("plan.pause")
                is ReminderCommand.ResumeGoal -> Interpretation("plan.resume")
            }
        }
        if (GoalDialogue.begin(text, now) != null) return Interpretation("goal.start")
        ClockCommandParser.parse(text)?.let { c ->
            return Interpretation(when (c) {
                is ClockCommand.CancelTimer -> "timer.cancel"
                ClockCommand.DismissRingingTimer -> "timer.cancel"
                ClockCommand.DismissRingingAlarm -> "alarm.dismiss"
                is ClockCommand.CancelAlarm -> "alarm.cancel"
                is ClockCommand.SnoozeAlarm -> "alarm.snooze"
            })
        }
        if (MemoryQueryPolicy.localQuestion(text) != null) return Interpretation("memory.question")
        // Then the adaptive router: a recognised phone command or a confident offline parse stays on the phone.
        val offline = OfflineRuleProvider().reason(ReasoningRequest(text, JarvisCore.AVAILABLE_TOOLS))
        val step = offline.steps.firstOrNull()?.let(ReasoningToolSchema::normalize)
        val route = AdaptiveRouter.decide(text, step, JarvisCore.AVAILABLE_TOOLS, cloudAvailable = true)
        if (route.target == RouteTarget.LOCAL_PARSER && step != null) {
            return if (step.toolName == "set_timer") Interpretation("timer.start", mapOf("seconds" to step.parameters["duration_seconds"].orEmpty()))
            else Interpretation("local:${step.toolName}")
        }
        return Interpretation("none")
    }

    private fun day(at: ZonedDateTime, now: ZonedDateTime): String = when (ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())) {
        0L -> "today"
        1L -> "tomorrow"
        2L -> "day_after"
        else -> at.toLocalDate().toString()
    }
}

/**
 * A correction to a waiting plan ("no, make it 8 pm", "nahi, shaam 7 baje"): when the plan has exactly one reminder,
 * that reminder's time is changed and the plan is shown again for approval. Anything less clear returns null, and the
 * answer is treated as a plain yes/no instead — a correction never adds a second action.
 */
object PlanCorrection {
    fun apply(steps: List<ProposedStep>, answer: String, now: java.time.ZonedDateTime): List<ProposedStep>? {
        val reminders = steps.withIndex().filter { it.value.tool == "reminder.schedule" }
        if (reminders.size != 1) return null
        val at = (ReminderTime.parse(answer, now) as? WhenParse.Exact)?.at ?: return null
        val (index, step) = reminders.single()
        return steps.toMutableList().also {
            it[index] = step.copy(args = step.args + mapOf("at" to at.toInstant().toEpochMilli().toString(), "zone" to at.zone.id))
        }
    }
}
