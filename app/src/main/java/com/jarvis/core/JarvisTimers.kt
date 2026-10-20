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
