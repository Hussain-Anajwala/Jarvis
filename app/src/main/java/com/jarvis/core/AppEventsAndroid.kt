package com.jarvis.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.jarvis.data.AppDatabase
import com.jarvis.data.AppEvent
import com.jarvis.data.DecisionTraceEntry
import java.util.UUID

class RoomEventStore(db: AppDatabase) : EventStore {
    private val dao = db.appEventDao()
    override suspend fun insertIfAbsent(event: AppEvent) = dao.insert(event) != -1L
    override suspend fun unhandled() = dao.unhandled()
    override suspend fun markHandled(id: String, at: Long, outcome: String) = dao.markHandled(id, at, outcome) > 0
}

/** The only effects handlers get on the phone: a local notification (stable tag = key, so repeats replace) or an Activity row. */
class AndroidEventEffects(context: Context, private val db: AppDatabase) : EventEffects {
    private val app = context.applicationContext
    override fun notifyLocal(key: String, title: String, text: String) {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Plan updates", NotificationManager.IMPORTANCE_DEFAULT))
        val n = Notification.Builder(app, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(SafeStorage.text(title)).setContentText(SafeStorage.text(text)).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(app).notify(key, 19_001, n) } catch (_: SecurityException) { }
    }

    override suspend fun record(text: String) {
        db.traceDao().insertRedacted(DecisionTraceEntry(UUID.randomUUID().toString(), null, text, "low", "event", text, System.currentTimeMillis()))
    }

    private companion object { const val CHANNEL = "jarvis.plan_updates" }
}

/** The app's handlers. Each re-reads current state, so a plan or goal cancelled in the meantime gets no reaction. */
object AppEventHandlers {
    fun create(db: AppDatabase): Map<String, EventHandler> = mapOf(
        EventTypes.PLAN_FINISHED to EventHandler { e, fx ->
            val run = db.planExecutionDao().execution(e.subjectId) ?: return@EventHandler "plan no longer exists"
            if (run.status != e.status) return@EventHandler "plan changed since (now ${run.status}); nothing to do"
            when (e.status) {
                ExecStates.NEEDS_ATTENTION, ExecStates.FAILED -> {
                    fx.notifyLocal("plan:${run.id}", "A plan needs your attention", "Open Plans to see which step didn't finish or couldn't be confirmed.")
                    fx.record("plan ${run.id.take(8)} ended ${e.status.lowercase()}; notified once")
                    "notified"
                }
                else -> { fx.record("plan ${run.id.take(8)} ended ${e.status.lowercase()}"); "recorded" }
            }
        },
        EventTypes.TASK_FAILED to EventHandler { e, fx ->
            // Notifications are off or refused when this happens, so another notification would not help: record only.
            fx.record("reminder ${e.subjectId.take(8)} couldn't be shown (${db.agentTaskDao().get(e.subjectId)?.result ?: "unknown reason"})")
            "recorded"
        },
        EventTypes.TASK_MISSED to EventHandler { e, fx ->
            // The reminder engine already posted one "Missed reminder" notification; do not repeat it.
            fx.record("reminder ${e.subjectId.take(8)} was missed while the phone was off or JARVIS was stopped")
            "recorded"
        },
        EventTypes.GOAL_COMPLETED to EventHandler { e, fx ->
            val goal = db.goalDao().get(e.subjectId) ?: return@EventHandler "goal no longer exists"
            if (goal.status != "completed") return@EventHandler "goal is ${goal.status}; nothing to do"
            fx.notifyLocal("goal:${goal.id}", "Plan complete", "Every session of \"${goal.title}\" is marked done.")
            fx.record("goal ${goal.id.take(8)} completed; notified once")
            "notified"
        }
    )
}

object AppEventBus {
    fun create(context: Context): EventBus {
        val db = AppDatabase.create(context)
        return EventBus(RoomEventStore(db), AppEventHandlers.create(db), AndroidEventEffects(context, db))
    }
}
