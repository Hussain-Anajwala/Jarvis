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
        """^\s*(yes|yeah|yea|yep|yup|yes+|ya|yah|sure|ok(ay)?|go ahead|do it|approve[ds]?|run it|haan(?:ji)?|haa|han(?:ji)?|ha|hanji|ji|ji haan|theek hai|thik hai)\b""",
        RegexOption.IGNORE_CASE
    )
    /** "No" wins over "yes" words later in the sentence: "nahi, woh mat karna". */
    private val no = Regex("""^\s*(no|nope|nah|naa|cancel|stop|don'?t|never ?mind|reject|nahi|nahin|nai|mat|ji nahi)\b""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Kind = when {
        no.containsMatchIn(text) -> Kind.NO
        yes.containsMatchIn(text) -> Kind.YES
        else -> Kind.UNCLEAR
    }

    private val answerWords = setOf(
        "yes", "yeah", "yea", "yep", "yup", "ya", "yah", "sure", "ok", "okay", "go", "ahead", "do", "it", "haan", "haanji", "haa", "ha", "han", "hanji",
        "ji", "theek", "thik", "hai", "no", "nope", "nah", "naa", "nahi", "nahin", "nai", "mat", "karo", "please", "thanks", "thank", "you", "sir",
        "jarvis", "of", "course", "right", "fine"
    )

    /**
     * A bare yes/no ("yes", "haan ji", "no thanks") that only makes sense as an answer: every word is an answer word.
     * "Cancel the timer" starts with a no-word but is a command, not a bare answer.
     */
    fun isBare(text: String): Boolean {
        if (parse(text) == Kind.UNCLEAR) return false
        val words = text.lowercase().replace(Regex("""[^\p{L}\s]"""), " ").trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        return words.size in 1..3 && words.all { it in answerWords }
    }
}

/** "Rok do" / "ruk jao" and "dobara shuru karo" / "resume it": stop or resume whatever is running, resolved against the app state. */
object ContextualControl {
    enum class Kind { STOP, RESUME }
    private val stop = Regex(
        """^\s*(?:please\s+|jarvis,?\s+)?(?:rok\s+do|rok\s+dijiye|roko|ruk\s+jao|ruko|rukiye|bas\s+karo|bas|thamo|stop\s+(?:it|that|this)|pause\s+(?:it|that|this))(?:\s+(?:please|na|abhi))?\s*[.!]?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val resume = Regex(
        """^\s*(?:please\s+|jarvis,?\s+)?(?:(?:dobara|phir\s+se|fir\s+se|wapas)\s+(?:shuru|chalu)|shuru|chalu|resume|continue|unpause|restart|start\s+again)(?:\s+(?:it|that|this|karo|kar\s+do|kijiye|karna|kar\s+dijiye))?(?:\s+please)?\s*[.!]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): Kind? = when {
        stop.containsMatchIn(text) -> Kind.STOP
        resume.containsMatchIn(text) -> Kind.RESUME
        else -> null
    }

    sealed interface Target {
        data object DismissRinging : Target
        data object StopSpeech : Target
        data object CancelTimer : Target
        data object PauseGoal : Target
        data object ResumeGoal : Target
        /** Nothing is done; [message] says why or asks which. */
        data class Say(val message: String) : Target
    }

    /**
     * What "stop" / "resume" refers to right now. Something ringing or being read aloud is what the user hears, so it
     * wins; otherwise exactly one kind of running thing is acted on (several timers or plans are asked about by their
     * own handlers), and a timer and a plan at once is a question. Resume never starts anything new.
     */
    fun resolve(kind: Kind, speaking: Boolean, timers: List<JarvisTimer>, activeGoals: Int, pausedGoals: Int): Target {
        if (kind == Kind.RESUME) return if (pausedGoals > 0) Target.ResumeGoal
            else Target.Say("Nothing is paused right now. Timers can't be paused; say \"set a 10 minute timer\" for a new one.")
        val running = timers.filter { it.state == JarvisTimer.State.RUNNING }
        return when {
            timers.any { it.state == JarvisTimer.State.RINGING } -> Target.DismissRinging
            speaking -> Target.StopSpeech
            running.isNotEmpty() && activeGoals > 0 -> Target.Say(
                "Do you mean your timer or your plan? Say \"cancel the timer\" or \"pause my plan\".")
            running.isNotEmpty() -> Target.CancelTimer
            activeGoals > 0 -> Target.PauseGoal
            else -> Target.Say("Nothing is running for me to stop.")
        }
    }
}

/**
 * The on-device decision chain, in the same order as the app (JarvisViewModel.handlePendingDialogue, then JarvisCore's
 * local handlers): pending approval → multi-step plan → reminder commands → goal → clock stop/snooze → memory question
 * → the adaptive router's local parse (phone commands, confident offline parses). "none" means the cloud. Used by the app for the
 * nothing-pending yes/no rule and by the evaluation, so the measured behaviour is the shipped behaviour.
 */
object OnDeviceIntents {
    suspend fun classify(raw: String, now: ZonedDateTime, pending: String? = null): Interpretation {
        val text = LanguageNormalizer.normalize(raw)
        if (pending == "plan_approval") {
            // As in JarvisViewModel.answerPlanApproval: a new time corrects the waiting plan; one without AM/PM is asked.
            when (val time = ReminderTime.parse(text, now)) {
                is WhenParse.Exact -> return Interpretation("approval.correct", mapOf("hour" to "${time.at.hour}"))
                is WhenParse.NeedsAmPm -> return Interpretation("clarify", mapOf("missing" to "ampm"))
                else -> Unit
            }
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
                        ReminderSlot.TITLE -> "title"; ReminderSlot.TIME -> "time"; ReminderSlot.AMPM -> "ampm"; ReminderSlot.DAY -> "day"
                    }))
                    ReminderStep.Cancelled -> Interpretation("none")
                }
                is ReminderCommand.List -> Interpretation("reminder.list", mapOf("scope" to command.scope))
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
        ContextualControl.parse(text)?.let { return Interpretation(if (it == ContextualControl.Kind.STOP) "stop.contextual" else "resume.contextual") }
        if (MemoryQueryPolicy.localQuestion(text) != null) return Interpretation("memory.question")
        // Then the adaptive router: a recognised phone command or a confident offline parse stays on the phone.
        val offline = OfflineRuleProvider().reason(ReasoningRequest(text, JarvisCore.AVAILABLE_TOOLS))
        val step = offline.steps.firstOrNull()?.let(ReasoningToolSchema::normalize)
        val route = AdaptiveRouter.decide(text, step, JarvisCore.AVAILABLE_TOOLS, cloudAvailable = true)
        if (route.target == RouteTarget.LOCAL_PARSER && step != null) {
            return when (step.toolName) {
                "set_timer" -> Interpretation("timer.start", mapOf("seconds" to step.parameters["duration_seconds"].orEmpty()))
                "forget_memory" -> Interpretation("memory.forget")
                else -> Interpretation("local:${step.toolName}")
            }
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
