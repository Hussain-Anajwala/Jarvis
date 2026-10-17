package com.jarvis.core

import android.content.Context
import com.jarvis.data.AppDatabase
import com.jarvis.data.AutomationRule
import com.jarvis.data.Plan
import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
                    "Demo (seeded data): Every Monday, remind me to review the weekly report",
                    "suggested",
                    "habit-seed-1,habit-seed-2,habit-seed-3"
                )
            )
        }
        detectFromHistory(db)
    }

    /** Suggests (never auto-enables) routines found in the user's real plans; seeded demo plans are ignored. */
    suspend fun detectFromHistory(db: AppDatabase, now: Long = System.currentTimeMillis()) {
        val existing = db.automationRuleDao().observeAllSnapshot().map { it.patternDescription }.toSet()
        val plans = db.planDao().observeAll().first()
        HabitDetector.suggestions(plans, now).forEach { suggestion ->
            if (suggestion.description !in existing) {
                db.automationRuleDao().insert(
                    AutomationRule(UUID.randomUUID().toString(), suggestion.description, "suggested", suggestion.planIds.joinToString(","))
                )
            }
        }
    }
}

data class HabitSuggestion(val description: String, val planIds: List<String>)

object HabitDetector {
    private const val WINDOW_MS = 8L * 7 * 24 * 60 * 60 * 1000
    const val MIN_WEEKS = 3

    fun suggestions(plans: List<Plan>, now: Long): List<HabitSuggestion> {
        val zone = java.time.ZoneId.systemDefault()
        return plans
            .filter { it.sourceConversationRef != "habit-seed" && now - it.createdAt in 0..WINDOW_MS }
            .groupBy { normalize(it.goalText) to java.time.Instant.ofEpochMilli(it.createdAt).atZone(zone).dayOfWeek }
            .mapNotNull { (key, group) ->
                val weeks = group.map {
                    java.time.Instant.ofEpochMilli(it.createdAt).atZone(zone).get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                }.toSet()
                if (key.first.isBlank() || weeks.size < MIN_WEEKS) return@mapNotNull null
                val day = key.second.name.lowercase().replaceFirstChar(Char::uppercase)
                HabitSuggestion("Every $day: ${group.first().goalText.trim()} (seen in ${weeks.size} weeks)", group.map { it.id })
            }
    }

    private fun normalize(goal: String) = goal.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
