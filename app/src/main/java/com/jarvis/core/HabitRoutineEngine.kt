package com.jarvis.core

import android.content.Context
import com.jarvis.data.AppDatabase
import com.jarvis.data.AutomationRule
import com.jarvis.data.Plan
import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object HabitRoutineEngine {
    suspend fun seedAndDetect(context: Context) = withContext(Dispatchers.IO) {
        val db = AppDatabase.create(context)
        if (db.planDao().activePlans().isEmpty() &&
            db.automationRuleDao().observeAllSnapshot().isEmpty()
        ) {
            val now = System.currentTimeMillis()
            repeat(3) { index ->
                val date = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, -(index + 1) * 7)
                    set(Calendar.HOUR_OF_DAY, 9)
                    set(Calendar.MINUTE, 0)
                }.timeInMillis
                db.planDao().insert(
                    Plan(
                        UUID.randomUUID().toString(),
                        "Seeded historical Monday report reminder",
                        "completed",
                        date,
                        date,
                        "habit-seed"
                    )
                )
            }
            db.automationRuleDao().insert(
                AutomationRule(
                    UUID.randomUUID().toString(),
                    "Every Monday, remind me to review the weekly report",
                    "suggested",
                    "habit-seed-1,habit-seed-2,habit-seed-3"
                )
            )
        }
        db.close()
    }
}
