package com.jarvis.core

import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Cancelling or silencing alarms and timers. Creating them stays with the existing set_alarm / set_timer tools. */
sealed interface ClockCommand {
    /** Turn off an upcoming alarm: at a stated time, or the one JARVIS set most recently ([latest]). */
    data class CancelAlarm(val hour: Int?, val minute: Int?, val latest: Boolean) : ClockCommand
    data object DismissRingingAlarm : ClockCommand
    data class CancelTimer(val latest: Boolean) : ClockCommand
    data object DismissRingingTimer : ClockCommand
    /** Snooze the ringing alarm, optionally for [minutes]. */
    data class SnoozeAlarm(val minutes: Int?) : ClockCommand
}

object ClockCommandParser {
    // "turn the alarm off", "alarm off", Hinglish "alarm band karo" / "timer hatao" all count as stop words.
    private val stopWords = Regex(
        """\b(cancel|delete|remove|stop|dismiss|disable|silence|kill|clear|off|shut\s+up|band\s+kar(o|do|\s+do)?|band|hatao|hata\s+do|bandh)\b""",
        RegexOption.IGNORE_CASE
    )
    private val snooze = Regex("""\bsnooze\b""", RegexOption.IGNORE_CASE)
    private val snoozeMinutes = Regex("""\b(\d{1,2})\s*(?:more\s+)?(?:min(?:ute)?s?)\b""", RegexOption.IGNORE_CASE)
    private val alarm = Regex("""\balarms?\b""", RegexOption.IGNORE_CASE)
    private val timer = Regex("""\btimers?\b""", RegexOption.IGNORE_CASE)
    private val ringing = Regex("""\b(ringing|going off|sounding|beeping)\b""", RegexOption.IGNORE_CASE)
    private val latest = Regex("""\b(just|i\s+(?:just\s+)?(?:set|started|made|created)|last|latest|recent|ago|earlier|you\s+set)\b""", RegexOption.IGNORE_CASE)
    private val time12 = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(a\.?\s?m\.?|p\.?\s?m\.?)""", RegexOption.IGNORE_CASE)
    private val time24 = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")

    private val creating = Regex("""^\s*(?:please\s+|hey\s+jarvis,?\s+)?(?:set|create|start|add|make|put)\b""", RegexOption.IGNORE_CASE)

    fun parse(text: String): ClockCommand? {
        if (snooze.containsMatchIn(text) && alarm.containsMatchIn(text).or(!timer.containsMatchIn(text))) {
            if (creating.containsMatchIn(text)) return null
            return ClockCommand.SnoozeAlarm(snoozeMinutes.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..60 })
        }
        // "When does my alarm go off?" is a question, not a request to turn anything off.
        val withoutGoOff = text.replace(Regex("""\b(go|goes|went|gone)\s+off\b""", RegexOption.IGNORE_CASE), " ")
        if (!stopWords.containsMatchIn(withoutGoOff)) return null
        val question = Regex("""^\s*(what|when|which|does|do|is|are|how|will|did)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (question && !Regex("""\b(cancel|delete|remove|stop|dismiss|disable)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        // "Set an alarm to remind me to stop…" creates an alarm; it is not a cancellation.
        if (creating.containsMatchIn(text)) return null
        val isAlarm = alarm.containsMatchIn(text)
        val isTimer = timer.containsMatchIn(text)
        if (isAlarm == isTimer) return null // neither, or both (ambiguous): not a clock command
        // "stop the alarm" / "alarm band karo" with nothing else is about the one ringing now.
        val ringingNow = ringing.containsMatchIn(text) ||
            Regex("""^\s*(?:please\s+)?(?:stop|dismiss|silence|shut\s+up)\s+(?:the\s+|that\s+)?(?:alarm|timer)\s*[.!]?\s*$""", RegexOption.IGNORE_CASE).matches(text) ||
            Regex("""^\s*(?:the\s+)?(?:alarm|timer)\s+(?:band\s+kar(?:o|do|\s+do)?|bandh|hatao|off)\s*[.!]?\s*$""", RegexOption.IGNORE_CASE).matches(text) ||
            Regex("""^\s*(?:please\s+)?(?:turn|switch)\s+(?:off\s+)?(?:the\s+|that\s+)?(?:alarm|timer)(?:\s+off)?\s*[.!]?\s*$""", RegexOption.IGNORE_CASE).matches(text)
        if (isTimer) return if (ringingNow) ClockCommand.DismissRingingTimer else ClockCommand.CancelTimer(latest.containsMatchIn(text))
        if (ringingNow) return ClockCommand.DismissRingingAlarm
        time12.find(text)?.let { m ->
            val raw = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifBlank { "0" }.toInt()
            if (raw in 1..12 && minute in 0..59) {
                val pm = m.groupValues[3].startsWith("p", ignoreCase = true)
                return ClockCommand.CancelAlarm((raw % 12) + if (pm) 12 else 0, minute, latest = false)
            }
        }
        time24.find(text)?.let { m -> return ClockCommand.CancelAlarm(m.groupValues[1].toInt(), m.groupValues[2].toInt(), latest = false) }
        return ClockCommand.CancelAlarm(null, null, latest.containsMatchIn(text))
    }
}

/** The last line of defence: an utterance that asks to stop, cancel, or snooze must never create an alarm or timer. */
object ClockIntentGuard {
    private val stopping = Regex(
        """\b(cancel|delete|remove|stop|dismiss|disable|silence|snooze|turn(ed)?\s+(it\s+|the\s+\w+\s+)?off|switch\s+off|shut\s+(off|up)|band\s+kar(o|do)?|bandh|hatao)\b|\b(alarm|timer)\s+off\b""",
        RegexOption.IGNORE_CASE
    )
    private val creating = Regex("""^\s*(?:please\s+|hey\s+jarvis,?\s+)?(?:set|create|start|add|make|put|wake\s+me)\b""", RegexOption.IGNORE_CASE)

    /** True when [utterance] asks to stop something and does not start with a request to create one. */
    fun blocksCreation(utterance: String): Boolean = stopping.containsMatchIn(utterance) && !creating.containsMatchIn(utterance)

    const val REPLY = "That sounded like a request to stop an alarm or timer, so I didn't set a new one. " +
        "Say \"cancel my 7 AM alarm\", \"stop the timer\", or \"snooze the alarm\"."
}

/** An alarm or timer JARVIS asked Clock to create. Android has no API to list Clock's alarms, so JARVIS remembers its own. */
data class ClockItem(
    val id: String,
    val kind: Kind,
    val createdAt: Long,
    val hour: Int = 0,
    val minute: Int = 0,
    val label: String = "",
    val seconds: Int = 0,
    val cancelledAt: Long? = null
) {
    enum class Kind { ALARM, TIMER }

    /** A one-time alarm goes off at the next [hour]:[minute] after it was set. */
    fun alarmTriggerAt(): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = createdAt
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= createdAt) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun timerEndsAt(): Long = createdAt + seconds * 1000L

    fun describe(now: Long): String = when (kind) {
        Kind.ALARM -> "${ClockText.time(hour, minute)} alarm" + (label.takeIf { it.isNotBlank() && it != "JARVIS alarm" }?.let { " ($it)" } ?: "") +
            ", set ${ClockText.ago(now - createdAt)}"
        Kind.TIMER -> "${ClockText.duration(seconds)} timer, started ${ClockText.ago(now - createdAt)}"
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind.name).put("createdAt", createdAt).put("hour", hour)
        .put("minute", minute).put("label", label).put("seconds", seconds).apply { cancelledAt?.let { put("cancelledAt", it) } }

    companion object {
        fun fromJson(o: JSONObject) = ClockItem(
            o.getString("id"), Kind.valueOf(o.getString("kind")), o.getLong("createdAt"), o.optInt("hour"), o.optInt("minute"),
            o.optString("label"), o.optInt("seconds"), if (o.has("cancelledAt")) o.getLong("cancelledAt") else null
        )

        fun listToJson(items: List<ClockItem>): String = JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()
        fun listFromJson(json: String?): List<ClockItem> = runCatching {
            val array = JSONArray(json ?: "[]")
            (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}

object ClockText {
    fun time(hour: Int, minute: Int): String = String.format(Locale.US, "%d:%02d %s", if (hour % 12 == 0) 12 else hour % 12, minute, if (hour < 12) "AM" else "PM")
    fun time(epochMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        java.time.Instant.ofEpochMilli(epochMs).atZone(zone).let { time(it.hour, it.minute) }
    fun duration(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600}-hour"
        seconds % 60 == 0 -> "${seconds / 60}-minute"
        else -> "$seconds-second"
    }
    fun ago(ms: Long): String {
        val minutes = (ms / 60_000).toInt()
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }
}

/** What JARVIS will do for a clock command, decided from the command and its own records only. */
sealed interface ClockPlan {
    /** Ask Clock to turn off the alarm at this time (ACTION_DISMISS_ALARM, time search). [item] is JARVIS's record, if any. */
    data class DismissAlarmAt(val hour: Int, val minute: Int, val item: ClockItem?) : ClockPlan
    data object DismissRingingAlarm : ClockPlan
    /** Android can dismiss a timer that has finished and is ringing (ACTION_DISMISS_TIMER). */
    data class DismissRingingTimer(val item: ClockItem?) : ClockPlan
    /** No public API cancels a running Clock timer: open Clock's timers so the user can stop it. */
    data class ShowTimers(val item: ClockItem?, val reason: String) : ClockPlan
    data class Choose(val items: List<ClockItem>, val question: String) : ClockPlan
    data class Explain(val message: String) : ClockPlan
    /** ACTION_SNOOZE_ALARM: Clock snoozes the ringing alarm (it does nothing if none is ringing). */
    data class SnoozeAlarm(val minutes: Int?) : ClockPlan
    /** A timer JARVIS runs itself: cancelled (or silenced, if ringing) by its id, and the result is verified. */
    data class StopJarvisTimer(val timer: JarvisTimer) : ClockPlan
    data class ChooseJarvisTimer(val timers: List<JarvisTimer>, val question: String) : ClockPlan
}

object ClockPlanner {
    /** A finished timer is treated as ringing for this long (Clock keeps ringing until dismissed). */
    private const val RINGING_WINDOW_MS = 10 * 60_000L

    fun plan(command: ClockCommand, history: List<ClockItem>, now: Long, jarvisTimers: List<JarvisTimer> = emptyList()): ClockPlan {
        val live = history.filter { it.cancelledAt == null }
        val ownTimers = TimerBook.active(jarvisTimers).sortedByDescending { it.startedAt }
        return when (command) {
            ClockCommand.DismissRingingAlarm -> ClockPlan.DismissRingingAlarm
            is ClockCommand.SnoozeAlarm -> ClockPlan.SnoozeAlarm(command.minutes)
            ClockCommand.DismissRingingTimer -> ownTimers.firstOrNull { it.state == JarvisTimer.State.RINGING }?.let { ClockPlan.StopJarvisTimer(it) }
                ?: ClockPlan.DismissRingingTimer(live.filter { it.kind == ClockItem.Kind.TIMER }.maxByOrNull { it.createdAt })
            is ClockCommand.CancelAlarm -> planAlarm(command, live.filter { it.kind == ClockItem.Kind.ALARM && it.alarmTriggerAt() > now }, now)
            is ClockCommand.CancelTimer -> when {
                // JARVIS's own timers can really be cancelled, so they come first.
                ownTimers.size == 1 || (ownTimers.isNotEmpty() && command.latest) -> ClockPlan.StopJarvisTimer(ownTimers.first())
                ownTimers.size > 1 -> ClockPlan.ChooseJarvisTimer(ownTimers, "You have ${ownTimers.size} timers running. Which one? " +
                    ownTimers.mapIndexed { i, t -> "${i + 1}: ${t.describe(now)}" }.joinToString("; ") + ".")
                else -> planTimer(command, live.filter { it.kind == ClockItem.Kind.TIMER && now < it.timerEndsAt() + RINGING_WINDOW_MS }, now)
            }
        }
    }

    /** The plan for one item the user picked from a list. */
    fun planFor(item: ClockItem, now: Long): ClockPlan = when (item.kind) {
        ClockItem.Kind.ALARM -> ClockPlan.DismissAlarmAt(item.hour, item.minute, item)
        ClockItem.Kind.TIMER -> if (now >= item.timerEndsAt()) ClockPlan.DismissRingingTimer(item)
        else ClockPlan.ShowTimers(item, "Android doesn't let other apps cancel a running Clock timer, so I can't stop it myself.")
    }

    private fun planAlarm(command: ClockCommand.CancelAlarm, upcoming: List<ClockItem>, now: Long): ClockPlan {
        if (command.hour != null && command.minute != null) {
            val record = upcoming.firstOrNull { it.hour == command.hour && it.minute == command.minute }
            return ClockPlan.DismissAlarmAt(command.hour, command.minute, record)
        }
        val newestFirst = upcoming.sortedByDescending { it.createdAt }
        return when {
            newestFirst.isEmpty() -> ClockPlan.Explain(
                "I don't have a record of an upcoming alarm that I set. Tell me its time, for example \"cancel my 7 AM alarm\"."
            )
            command.latest || newestFirst.size == 1 -> newestFirst.first().let { ClockPlan.DismissAlarmAt(it.hour, it.minute, it) }
            else -> ClockPlan.Choose(newestFirst, "I set ${newestFirst.size} alarms. Which one should I cancel? " +
                newestFirst.mapIndexed { i, item -> "${i + 1}: ${item.describe(now)}" }.joinToString("; ") + ".")
        }
    }

    private fun planTimer(command: ClockCommand.CancelTimer, recent: List<ClockItem>, now: Long): ClockPlan {
        val newestFirst = recent.sortedByDescending { it.createdAt }
        val target = when {
            newestFirst.isEmpty() -> null
            command.latest || newestFirst.size == 1 -> newestFirst.first()
            else -> return ClockPlan.Choose(newestFirst, "I started ${newestFirst.size} timers. Which one? " +
                newestFirst.mapIndexed { i, item -> "${i + 1}: ${item.describe(now)}" }.joinToString("; ") + ".")
        }
        return when {
            target != null && now >= target.timerEndsAt() -> ClockPlan.DismissRingingTimer(target)
            else -> ClockPlan.ShowTimers(
                target,
                "Android doesn't let other apps cancel a running Clock timer, so I can't stop it myself."
            )
        }
    }
}
