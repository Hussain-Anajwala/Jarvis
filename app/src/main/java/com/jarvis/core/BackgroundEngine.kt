package com.jarvis.core

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.data.AppDatabase
import com.jarvis.data.DecisionTraceEntry
import kotlin.math.abs
import java.util.UUID
import java.util.concurrent.TimeUnit

object BackgroundEngine {
    private const val WORK_NAME = "jarvis-plan-evaluation"

    fun schedule(context: Context, intervalMinutes: Long = 15L) {
        val request = PeriodicWorkRequestBuilder<PlanEvaluationWorker>(
            intervalMinutes.coerceAtLeast(15L), TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_NAME-startup-${System.currentTimeMillis()}",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PlanEvaluationWorker>().build()
        )
    }
}

class PlanEvaluationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val db = AppDatabase.create(applicationContext)
        val now = System.currentTimeMillis()
        val activePlans = db.planDao().activePlans()
        val rules = db.monitoringRuleDao().activeRules()
        val travelAgent = OsrmTravelContextAgent()
        rules.forEach { rule ->
            if (rule.conditionType != "travel_time") {
                db.monitoringRuleDao().markChecked(rule.id, now, rule.lastKnownValue ?: "")
                return@forEach
            }
            val reminderItem = db.planItemDao().findById(rule.planItemId)
            val eventItem = reminderItem
                ?.let { db.planItemDao().forPlan(it.planId) }
                ?.firstOrNull { it.itemType == "calendar_event" }
            val event = eventItem?.refId?.let { db.eventDao().findById(it) }
            if (event == null) {
                db.monitoringRuleDao().markChecked(rule.id, now, rule.lastKnownValue ?: "")
                return@forEach
            }
            val estimate = travelAgent.estimate(event, 30)
            val previousMinutes = rule.lastKnownValue
                ?.substringBefore("|")
                ?.toIntOrNull()
            val changedMeaningfully = previousMinutes != null &&
                abs(previousMinutes - estimate.minutes) >= 15
            if (changedMeaningfully && reminderItem.refId != null) {
                db.reminderDao().updateTriggerTime(
                    reminderItem.refId,
                    event.startTime - (estimate.minutes + 15) * 60 * 1000L
                )
                db.traceDao().insert(
                    DecisionTraceEntry(
                        UUID.randomUUID().toString(),
                        reminderItem.planId,
                        "Travel time changed from ${previousMinutes} min to ${estimate.minutes} min; departure reminder recalculated (${estimate.freshness})",
                        "medium",
                        "travel.monitor",
                        "updated",
                        now
                    )
                )
            }
            db.monitoringRuleDao().markChecked(
                rule.id,
                now,
                "${estimate.minutes}|${estimate.freshness}"
            )
        }
        if (activePlans.isNotEmpty()) {
            NotificationPublisher.post(
                applicationContext,
                NotificationTier.NORMAL,
                "JARVIS checked your plans",
                "${activePlans.size} active plan(s) evaluated in the background."
            )
        }
        db.traceDao().insert(
            DecisionTraceEntry(
                UUID.randomUUID().toString(),
                null,
                "Background evaluation checked ${activePlans.size} active plan(s) and ${rules.size} monitoring rule(s)",
                "low",
                "background.plan_evaluation",
                "executed",
                now
            )
        )
        db.close()
        return Result.success()
    }
}
