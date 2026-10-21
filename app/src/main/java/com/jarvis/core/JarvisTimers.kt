package com.jarvis.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A timer that JARVIS runs itself (unlike a Clock-app timer, which no app can cancel). Its durable record is the source
 * of truth for cancelling, dismissing, and recovering it after the app or phone restarts.
 */
data class JarvisTimer(
    val id: Int,
    val label: String,
    val seconds: Int,
    val startedAt: Long,
    val state: State,
    val endedAt: Long? = null
) {
    enum class State { RUNNING, RINGING, FINISHED, CANCELLED, MISSED }

    val endsAt: Long get() = startedAt + seconds * 1000L
    val active: Boolean get() = state == State.RUNNING || state == State.RINGING

    fun describe(now: Long): String = when (state) {
        State.RINGING -> "${ClockText.duration(seconds)} timer${labelSuffix()}, ringing now"
        else -> "${ClockText.duration(seconds)} timer${labelSuffix()}, ${remaining(now)} left"
    }

    private fun labelSuffix() = label.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""

    private fun remaining(now: Long): String {
        val left = ((endsAt - now).coerceAtLeast(0) + 999) / 1000
        return when {
            left >= 3600 -> "${left / 3600} h ${(left % 3600) / 60} min"
            left >= 60 -> "${left / 60} min ${left % 60} s"
            else -> "$left s"
        }
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).put("seconds", seconds)
        .put("startedAt", startedAt).put("state", state.name).apply { endedAt?.let { put("endedAt", it) } }

    companion object {
        fun fromJson(o: JSONObject) = JarvisTimer(
            o.getInt("id"), o.optString("label"), o.getInt("seconds"), o.getLong("startedAt"),
            State.valueOf(o.getString("state")), if (o.has("endedAt")) o.getLong("endedAt") else null
        )
        fun listToJson(timers: List<JarvisTimer>): String = JSONArray().apply { timers.forEach { put(it.toJson()) } }.toString()
        fun listFromJson(json: String?): List<JarvisTimer> = runCatching {
            val array = JSONArray(json ?: "[]")
            (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}

/** Pure bookkeeping for [JarvisTimer]s: only valid state changes happen, and old records are pruned. */
object TimerBook {
    /** A finished timer rings for at most this long before it is treated as done. */
    const val RING_LIMIT_MS = 10 * 60_000L
    private const val KEEP_INACTIVE = 30
    /** A timer that ended this recently still rings late rather than being reported missed. */
    const val LATE_RING_MS = 2 * 60_000L
    /** The same timer asked for again this soon (a repeated voice result, a double tap) is the one already running. */
    const val DUPLICATE_WINDOW_MS = 15_000L

    fun duplicateOf(timers: List<JarvisTimer>, label: String, seconds: Int, now: Long): JarvisTimer? = timers.firstOrNull {
        it.state == JarvisTimer.State.RUNNING && it.seconds == seconds && it.label == label.trim().take(40) && now - it.startedAt in 0 until DUPLICATE_WINDOW_MS
    }

    fun start(timers: List<JarvisTimer>, label: String, seconds: Int, now: Long): Pair<List<JarvisTimer>, JarvisTimer> {
        require(seconds in 1..86_400) { "timer must be 1 second to 24 hours" }
        val timer = JarvisTimer((timers.maxOfOrNull { it.id } ?: 0) + 1, label.trim().take(40), seconds, now, JarvisTimer.State.RUNNING)
        return prune(timers + timer) to timer
    }

    /** RUNNING → RINGING / CANCELLED; RINGING → FINISHED / CANCELLED. Anything else leaves the record unchanged. */
    fun transition(timers: List<JarvisTimer>, id: Int, to: JarvisTimer.State, now: Long): List<JarvisTimer> = timers.map { t ->
        if (t.id != id) return@map t
        val allowed = when (t.state) {
            JarvisTimer.State.RUNNING -> to == JarvisTimer.State.RINGING || to == JarvisTimer.State.CANCELLED || to == JarvisTimer.State.MISSED
            JarvisTimer.State.RINGING -> to == JarvisTimer.State.FINISHED || to == JarvisTimer.State.CANCELLED
            else -> false
        }
        if (allowed) t.copy(state = to, endedAt = if (to == JarvisTimer.State.RINGING) t.endedAt else now) else t
    }

    fun active(timers: List<JarvisTimer>) = timers.filter { it.active }

    /** What the countdown card shows: "44:59", "1:02:03", "Ringing", or "Ending…" if the alarm is a little late. */
    fun countdown(timer: JarvisTimer, now: Long): String {
        if (timer.state == JarvisTimer.State.RINGING) return "Ringing"
        val left = (timer.endsAt - now + 999) / 1000
        if (left <= 0) return "Ending…"
        val (h, m, s) = Triple(left / 3600, (left % 3600) / 60, left % 60)
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.US, "%d:%02d", m, s)
    }

    /** A card is shown while the timer runs and while it rings (up to [RING_LIMIT_MS]). */
    fun showCard(timer: JarvisTimer, now: Long): Boolean = timer.state == JarvisTimer.State.RUNNING ||
        (timer.state == JarvisTimer.State.RINGING && now - timer.endsAt <= RING_LIMIT_MS)

    data class Recovery(val timers: List<JarvisTimer>, val reschedule: List<JarvisTimer>, val missed: List<JarvisTimer>)

    /**
     * After a restart: running timers still in the future (or only just due) are scheduled again; ones whose time passed while nothing
     * could ring are MISSED (never silently "finished"); timers left ringing too long are FINISHED.
     */
    fun recover(timers: List<JarvisTimer>, now: Long): Recovery {
        var list = timers
        val reschedule = mutableListOf<JarvisTimer>()
        val missed = mutableListOf<JarvisTimer>()
        timers.forEach { t ->
            when {
                t.state == JarvisTimer.State.RUNNING && t.endsAt > now - LATE_RING_MS -> reschedule += t
                t.state == JarvisTimer.State.RUNNING -> { list = transition(list, t.id, JarvisTimer.State.MISSED, now); missed += t }
                t.state == JarvisTimer.State.RINGING && now - t.endsAt > RING_LIMIT_MS -> list = transition(list, t.id, JarvisTimer.State.FINISHED, now)
            }
        }
        return Recovery(list, reschedule, missed)
    }

    private fun prune(timers: List<JarvisTimer>): List<JarvisTimer> {
        val inactive = timers.filterNot { it.active }.sortedByDescending { it.startedAt }.drop(KEEP_INACTIVE).map { it.id }.toSet()
        return timers.filterNot { it.id in inactive }
    }
}

/** What JARVIS says about its own timers: only what the engine did and what Android allows. */
object TimerReplies {
    fun started(started: JarvisTimerEngine.Started, exact: Boolean, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val what = "${ClockText.duration(started.timer.seconds)} timer"
        if (started.duplicate) return "Your $what is already running (you started it a moment ago), so I didn't start a second one."
        return "Started your $what. It ends at ${ClockText.time(started.timer.endsAt, zone)}. Say \"cancel the timer\" or tap Cancel to stop it." +
            (if (!exact) " Android hasn't allowed exact alarms, so it may ring a little late." else "") +
            (if (!started.notificationsShown) " Notifications for JARVIS are off, so you won't hear it ring — turn them on in Settings." else "")
    }

    fun stopped(timer: JarvisTimer, wasRinging: Boolean): String {
        val what = "${ClockText.duration(timer.seconds)} timer"
        return if (wasRinging) "Stopped your $what." else "Your $what has been cancelled. It won't ring."
    }
}
