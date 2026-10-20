package com.jarvis.core

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.jarvis.data.AgentTask
import com.jarvis.data.AppDatabase
import com.jarvis.data.Goal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RoomTaskStore(private val db: AppDatabase) : TaskStore {
    private val tasks = db.agentTaskDao()
    private val goals = db.goalDao()
    override suspend fun insert(task: AgentTask) = tasks.insert(task)
    override suspend fun get(id: String) = tasks.get(id)
    override suspend fun all() = tasks.all()
    override suspend fun transition(id: String, from: List<String>, to: String, now: Long, result: String?, attemptDelta: Int) =
        tasks.transition(id, from, to, now, result?.let(SafeStorage::text), attemptDelta) > 0
    override suspend fun setTime(id: String, at: Long, now: Long) = tasks.setTime(id, at, now)
    override suspend fun setResult(id: String, result: String, now: Long) = tasks.setResult(id, SafeStorage.text(result), now)
    override suspend fun insertGoal(goal: Goal) = goals.insert(goal)
    override suspend fun goal(id: String) = goals.get(id)
    override suspend fun goals() = goals.all()
    override suspend fun setGoalStatus(id: String, status: String, now: Long) = goals.setStatus(id, status, now)
}

/**
 * Exact alarms (AlarmManager.setExactAndAllowWhileIdle, allowed by USE_EXACT_ALARM / SCHEDULE_EXACT_ALARM): a reminder
 * "in 30 minutes" must ring then, which WorkManager does not promise (it may defer work by many minutes under Doze).
 * In deep Doze Android still limits an app to about one such alarm every ~9 minutes. Each task has its own
 * PendingIntent (the task id is in the intent data), so setting it again replaces it instead of adding a second alarm.
 */
class AlarmTaskScheduler(context: Context) : TaskScheduler {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)

    private fun exactAllowed() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms?.canScheduleExactAlarms() == true

    override fun schedule(taskId: String, at: Long): Boolean {
        val manager = alarms ?: throw IllegalStateException("no AlarmManager")
        val pending = pending(taskId, PendingIntent.FLAG_UPDATE_CURRENT)!!
        return if (exactAllowed()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending); true
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending); false
        }
    }

    override fun cancel(taskId: String) {
        pending(taskId, PendingIntent.FLAG_NO_CREATE)?.let { alarms?.cancel(it); it.cancel() }
    }

    override fun isScheduled(taskId: String) = pending(taskId, PendingIntent.FLAG_NO_CREATE) != null

    private fun pending(taskId: String, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        app, 0, Intent(app, JarvisTaskReceiver::class.java).setAction(JarvisTaskReceiver.ACTION_FIRE).setData(Uri.parse("jarvis-task://$taskId")),
        PendingIntent.FLAG_IMMUTABLE or flag
    )
}

class NotificationTaskNotifier(context: Context) : TaskNotifier {
    private val app = context.applicationContext

    override fun enabled() = NotificationManagerCompat.from(app).areNotificationsEnabled()

    override fun post(task: AgentTask): Boolean {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (task.kind == TaskKinds.GOAL_STEP) "Plan step" else "Reminder")
            .setContentText(task.objective)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
        if (task.kind == TaskKinds.GOAL_STEP) {
            val done = PendingIntent.getBroadcast(
                app, 0, Intent(app, JarvisTaskReceiver::class.java).setAction(JarvisTaskReceiver.ACTION_DONE).setData(Uri.parse("jarvis-task://${task.id}")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(Notification.Action.Builder(null, "Done", done).build())
        }
        return try {
            NotificationManagerCompat.from(app).notify(task.id, ID, builder.build())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    override fun dismiss(taskId: String) = NotificationManagerCompat.from(app).cancel(taskId, ID)

    override fun missed(tasks: List<AgentTask>) {
        NotificationPublisher.post(app, NotificationTier.NORMAL, "Missed reminder" + if (tasks.size > 1) "s" else "",
            tasks.joinToString("; ") { it.objective } + " — the phone was off or JARVIS was stopped at that time.")
    }

    private companion object {
        const val CHANNEL = "jarvis.reminders"
        const val ID = 18_001
    }
}

object TaskEngines {
    fun create(context: Context): TaskEngine {
        val db = AppDatabase.create(context)
        return TaskEngine(RoomTaskStore(db), AlarmTaskScheduler(context), NotificationTaskNotifier(context)) { line ->
            db.traceDao().insertRedacted(com.jarvis.data.DecisionTraceEntry(java.util.UUID.randomUUID().toString(), null, line, "low", "reminder", line, System.currentTimeMillis()))
        }
    }
}

/** Alarm fired, "Done" tapped, or the phone/clock changed (boot, app update, time or time-zone change). */
class JarvisTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val id = intent.data?.host
        scope.launch {
            try {
                val engine = TaskEngines.create(context)
                when (intent.action) {
                    ACTION_FIRE -> id?.let { engine.deliver(it) }
                    ACTION_DONE -> id?.let { engine.markDone(it); NotificationTaskNotifier(context).dismiss(it) }
                    else -> engine.recover()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.jarvis.task.FIRE"
        const val ACTION_DONE = "com.jarvis.task.DONE"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
