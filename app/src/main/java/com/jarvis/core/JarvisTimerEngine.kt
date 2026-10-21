package com.jarvis.core

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * Runs JARVIS's own timers: a durable record (SharedPreferences), an exact AlarmManager alarm per timer, and an
 * insistent notification with a Stop button when it rings. Unlike a Clock-app timer, JARVIS can cancel these and check
 * that the cancellation took effect (the alarm's PendingIntent no longer exists and the record says CANCELLED).
 */
class JarvisTimerEngine(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("jarvis_timers", Context.MODE_PRIVATE)
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    /** Settings: JARVIS runs timers itself (default), or hands them to the Clock app as before. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    fun all(): List<JarvisTimer> = synchronized(LOCK) { JarvisTimer.listFromJson(prefs.getString(KEY_TIMERS, null)) }
    fun active(): List<JarvisTimer> = TimerBook.active(all())

    /** Whether Android will ring the timer on time; without the exact-alarm permission it may be minutes late. */
    fun exact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager?.canScheduleExactAlarms() == true

    fun start(seconds: Int, label: String = "", now: Long = System.currentTimeMillis()): JarvisTimer = startOnce(seconds, label, now).timer

    /** [duplicate]: the same timer was started moments ago and is returned instead of a second one. */
    data class Started(val timer: JarvisTimer, val duplicate: Boolean, val notificationsShown: Boolean)

    fun startOnce(seconds: Int, label: String = "", now: Long = System.currentTimeMillis()): Started {
        val (timer, duplicate) = synchronized(LOCK) {
            TimerBook.duplicateOf(all(), label, seconds, now)?.let { return@synchronized it to true }
            val (list, timer) = TimerBook.start(all(), label, seconds, now)
            save(list)
            timer to false
        }
        if (!duplicate) schedule(timer)
        return Started(timer, duplicate, postCountdown(timer))
    }

    /** False when Android won't show JARVIS notifications: the timer then rings only as a record in the app. */
    fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    sealed interface StopResult {
        data class Stopped(val timer: JarvisTimer, val wasRinging: Boolean) : StopResult
        data class NotActive(val timer: JarvisTimer?) : StopResult
        data class NotVerified(val timer: JarvisTimer) : StopResult
    }

    /** Cancels a running timer or silences a ringing one, then checks that nothing is left scheduled or ringing. */
    fun stop(id: Int, now: Long = System.currentTimeMillis()): StopResult {
        val before = all().firstOrNull { it.id == id }
        if (before == null || !before.active) return StopResult.NotActive(before)
        val wasRinging = before.state == JarvisTimer.State.RINGING
        firePending(id, PendingIntent.FLAG_NO_CREATE)?.let { pending ->
            alarmManager?.cancel(pending)
            pending.cancel()
        }
        NotificationManagerCompat.from(appContext).cancel(notificationId(id))
        val to = if (wasRinging) JarvisTimer.State.FINISHED else JarvisTimer.State.CANCELLED
        val after = synchronized(LOCK) { TimerBook.transition(all(), id, to, now).also(::save) }.first { it.id == id }
        val cleared = firePending(id, PendingIntent.FLAG_NO_CREATE) == null && !after.active
        return if (cleared) StopResult.Stopped(after, wasRinging) else StopResult.NotVerified(after)
    }

    /** True while this timer's alarm is set with Android (used to verify a plan step). */
    fun isArmed(id: Int): Boolean = firePending(id, PendingIntent.FLAG_NO_CREATE) != null

    /** The alarm fired: mark it ringing and post the alarm notification. */
    fun ring(id: Int, now: Long = System.currentTimeMillis()) {
        val timer = synchronized(LOCK) { TimerBook.transition(all(), id, JarvisTimer.State.RINGING, now).also(::save) }
            .firstOrNull { it.id == id && it.state == JarvisTimer.State.RINGING } ?: return
        postRinging(timer)
    }

    /** After boot or app start: reschedule future timers and report ones that passed while the phone was off. */
    fun recover(now: Long = System.currentTimeMillis()): List<JarvisTimer> {
        val recovery = synchronized(LOCK) { TimerBook.recover(all(), now).also { save(it.timers) } }
        recovery.reschedule.forEach { schedule(it); postCountdown(it) }
        if (recovery.missed.isNotEmpty()) {
            NotificationPublisher.post(
                appContext, NotificationTier.NORMAL, "Timer missed",
                recovery.missed.joinToString("; ") { "${ClockText.duration(it.seconds)} timer ended while the phone was off" }
            )
        }
        return recovery.missed
    }

    private fun schedule(timer: JarvisTimer) {
        val pending = firePending(timer.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val manager = alarmManager ?: return
        if (exact()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.endsAt, pending)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.endsAt, pending)
        }
    }

    /**
     * While a timer runs: a quiet ongoing notification counting down to its end, with Cancel. It is replaced by the
     * ringing notification (same id) and removed when the timer is stopped. Returns false if it can't be shown.
     */
    private fun postCountdown(timer: JarvisTimer): Boolean {
        if (!notificationsAllowed()) return false
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(RUNNING_CHANNEL, "Running timers", NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getBroadcast(
            appContext, notificationId(timer.id),
            Intent(appContext, JarvisTimerReceiver::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, timer.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.let {
            PendingIntent.getActivity(appContext, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = Notification.Builder(appContext, RUNNING_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("${ClockText.duration(timer.seconds)} timer${timer.label.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}")
            .setContentText("Ends at ${ClockText.time(timer.endsAt)}")
            .setWhen(timer.endsAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Notification.CATEGORY_STOPWATCH else Notification.CATEGORY_PROGRESS)
            .apply { open?.let { setContentIntent(it) } }
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .setTimeoutAfter((timer.endsAt - System.currentTimeMillis()).coerceAtLeast(1_000))
            .build()
        return try {
            NotificationManagerCompat.from(appContext).notify(notificationId(timer.id), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun postRinging(timer: JarvisTimer) {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Timers", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
            }
        )
        val stop = PendingIntent.getBroadcast(
            appContext, notificationId(timer.id),
            Intent(appContext, JarvisTimerReceiver::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, timer.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(appContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Timer done")
            .setContentText("${ClockText.duration(timer.seconds)} timer${timer.label.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}. Say \"stop the timer\" or tap Stop.")
            .setCategory(Notification.CATEGORY_ALARM)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setDeleteIntent(stop)
            .setTimeoutAfter(TimerBook.RING_LIMIT_MS)
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
        try {
            NotificationManagerCompat.from(appContext).notify(notificationId(timer.id), notification)
        } catch (_: SecurityException) {
            // Notifications denied: the timer still shows as ringing in JARVIS.
        }
    }

    private fun firePending(id: Int, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        appContext, id,
        Intent(appContext, JarvisTimerReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or flag
    )

    private fun save(list: List<JarvisTimer>) = prefs.edit().putString(KEY_TIMERS, JarvisTimer.listToJson(list)).apply()

    companion object {
        private val LOCK = Any()
        private const val KEY_TIMERS = "timers"
        private const val KEY_ENABLED = "enabled"
        private const val CHANNEL = "jarvis.timer"
        private const val RUNNING_CHANNEL = "jarvis.timer.running"
        const val ACTION_FIRE = "com.jarvis.timer.FIRE"
        const val ACTION_STOP = "com.jarvis.timer.STOP"
        const val EXTRA_ID = "timer_id"
        fun notificationId(id: Int) = 17_000 + id
    }
}

class JarvisTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = JarvisTimerEngine(context)
        val id = intent.getIntExtra(JarvisTimerEngine.EXTRA_ID, -1)
        when (intent.action) {
            JarvisTimerEngine.ACTION_FIRE -> engine.ring(id)
            JarvisTimerEngine.ACTION_STOP -> engine.stop(id)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> engine.recover()
        }
    }
}
