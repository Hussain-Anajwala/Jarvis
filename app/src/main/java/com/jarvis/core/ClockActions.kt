package com.jarvis.core

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.util.UUID
import kotlinx.coroutines.delay

/** JARVIS's own record of the alarms and timers it asked Clock to create (kept on the phone, newest 30). */
class ClockHistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("clock_history", Context.MODE_PRIVATE)

    fun all(): List<ClockItem> = ClockItem.listFromJson(prefs.getString(KEY, null))

    fun recordAlarm(hour: Int, minute: Int, label: String, now: Long = System.currentTimeMillis()) =
        add(ClockItem(UUID.randomUUID().toString(), ClockItem.Kind.ALARM, now, hour = hour, minute = minute, label = label))

    fun recordTimer(seconds: Int, now: Long = System.currentTimeMillis()) =
        add(ClockItem(UUID.randomUUID().toString(), ClockItem.Kind.TIMER, now, seconds = seconds))

    fun markCancelled(id: String, now: Long = System.currentTimeMillis()) {
        save(all().map { if (it.id == id) it.copy(cancelledAt = now) else it })
    }

    private fun add(item: ClockItem) = save((all() + item).takeLast(30))
    private fun save(items: List<ClockItem>) = prefs.edit().putString(KEY, ClockItem.listToJson(items)).apply()

    private companion object { const val KEY = "items" }
}

/** What really happened when JARVIS acted on Clock. Only [Done] may be reported as success. */
sealed interface ClockOutcome {
    val message: String
    data class Done(override val message: String) : ClockOutcome
    /** Clock was asked but Android gives no way to confirm the result. */
    data class Unconfirmed(override val message: String) : ClockOutcome
    data class Failed(override val message: String) : ClockOutcome
}

/**
 * Talks to the phone's Clock app with the public AlarmClock intents (Samsung Clock and Google Clock both handle them):
 * ACTION_DISMISS_ALARM (by time, or the one ringing), ACTION_DISMISS_TIMER (finished/ringing timers), ACTION_SHOW_TIMERS.
 * Opening Clock needs JARVIS on screen; from the background the intent is parked behind a notification instead.
 * An alarm cancellation is confirmed by Android's "next alarm" when that alarm was the next one; otherwise JARVIS says
 * it cannot confirm. Tested on Samsung Clock (One UI, Android 16): ACTION_DISMISS_ALARM by time does not turn off an
 * upcoming alarm (it only opens the alarm list), so JARVIS opens the list and says so instead of claiming success.
 */
class ClockActions(context: Context, private val history: ClockHistoryStore = ClockHistoryStore(context)) {
    /** The language of the current request (set by the screen). */
    var replyLang: ReplyLang = ReplyLang.EN
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    suspend fun dismissAlarmAt(hour: Int, minute: Int, item: ClockItem?): ClockOutcome {
        val time = ClockText.time(hour, minute)
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
            .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_IS_PM, hour >= 12)
        val before = nextAlarm()
        val wasNext = before != null && NextAlarm.isAt(before, hour, minute)
        launch(intent, "Clock")?.let { return it }
        if (wasNext) {
            // Android's "next alarm" is the one source of truth apps can read; wait for Clock to update it.
            repeat(10) {
                delay(500)
                if (nextAlarm() != before) {
                    item?.let { history.markCancelled(it.id) }
                    return ClockOutcome.Done("Cancelled your $time alarm.")
                }
            }
            showAlarms()
            return ClockOutcome.Failed(
                "Your Clock app didn't let me turn off the $time alarm; it is still set. I've opened your alarms: switch it off there."
            )
        }
        showAlarms()
        return ClockOutcome.Unconfirmed(
            "I asked Clock to turn off your $time alarm, but some Clock apps (including Samsung's) only do that for a ringing alarm, " +
                "and Android doesn't let apps read Clock's alarms to check. I've opened your alarms: if it's still on, switch it off there."
        )
    }

    /** The supported fallback: Clock's own alarm list, where the user can switch the alarm off. */
    private fun showAlarms() {
        runCatching { appContext.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    suspend fun dismissRingingAlarm(): ClockOutcome {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
            .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
        launch(intent, "Clock")?.let { return it }
        return ClockOutcome.Unconfirmed("I asked Clock to dismiss the ringing alarm. If it is still ringing, use the alarm screen.")
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun dismissRingingTimer(item: ClockItem?): ClockOutcome {
        // ACTION_DISMISS_TIMER is API 28; on older phones Clock simply doesn't handle it and the launch reports that.
        launch(Intent("android.intent.action.DISMISS_TIMER"), "Clock")?.let { return it }
        return ClockOutcome.Unconfirmed("I asked Clock to dismiss the finished timer. If it is still ringing, use the timer screen.")
    }

    suspend fun showTimers(item: ClockItem?, reason: String): ClockOutcome {
        launch(Intent(AlarmClock.ACTION_SHOW_TIMERS), "Clock's timers")?.let { return ClockOutcome.Failed("$reason ${it.message}") }
        val which = item?.let { "the ${ClockText.duration(it.seconds)} timer" } ?: "the timer"
        return ClockOutcome.Unconfirmed("$reason I've opened your timers in Clock: tap $which there to stop it.")
    }

    /** ACTION_SNOOZE_ALARM affects only a ringing alarm; Android reports nothing back, so this is never "done". */
    suspend fun snoozeAlarm(minutes: Int?): ClockOutcome {
        val intent = Intent(AlarmClock.ACTION_SNOOZE_ALARM)
        minutes?.let { intent.putExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, it) }
        launch(intent, "Clock")?.let { return it }
        val how = minutes?.let { " for $it minutes" } ?: ""
        return ClockOutcome.Unconfirmed("I asked Clock to snooze the ringing alarm$how. If it is still ringing, use the alarm screen. No new alarm was set.")
    }

    fun stopJarvisTimer(timer: JarvisTimer, engine: JarvisTimerEngine = JarvisTimerEngine(appContext)): ClockOutcome {
        val what = "${ClockText.duration(timer.seconds)} timer"
        return when (val result = engine.stop(timer.id)) {
            is JarvisTimerEngine.StopResult.Stopped ->
                ClockOutcome.Done(TimerReplies.stopped(timer, result.wasRinging, replyLang))
            is JarvisTimerEngine.StopResult.NotActive -> ClockOutcome.Failed("That $what had already ended, so there was nothing to cancel.")
            is JarvisTimerEngine.StopResult.NotVerified -> ClockOutcome.Failed("I tried to cancel the $what but couldn't confirm it stopped.")
        }
    }

    private fun nextAlarm(): Long? = alarmManager?.nextAlarmClock?.triggerTime

    /** Starts Clock, or explains why it could not; null means Clock was started. */
    private fun launch(intent: Intent, what: String): ClockOutcome? {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!BackgroundHandoff.canOpenAppsNow()) {
            return ClockOutcome.Unconfirmed(BackgroundHandoff.offer(appContext, intent, what))
        }
        return try {
            appContext.startActivity(intent)
            null
        } catch (_: ActivityNotFoundException) {
            ClockOutcome.Failed("Your Clock app doesn't support this request. Open Clock to change it.")
        } catch (_: SecurityException) {
            ClockOutcome.Failed("Android didn't allow opening Clock for this request.")
        }
    }
}

object NextAlarm {
    /** True if [triggerTime] is at [hour]:[minute] local time (the next occurrence of that alarm). */
    fun isAt(triggerTime: Long, hour: Int, minute: Int): Boolean {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = triggerTime }
        return c.get(java.util.Calendar.HOUR_OF_DAY) == hour && c.get(java.util.Calendar.MINUTE) == minute
    }
}
