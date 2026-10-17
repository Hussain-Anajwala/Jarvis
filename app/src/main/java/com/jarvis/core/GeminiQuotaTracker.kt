package com.jarvis.core

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

interface GeminiQuotaTracker {
    fun tryAcquire(
        keySlot: String,
        modelName: String,
        dailyBudget: Int,
        minuteBudget: Int,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean
}

object NoopGeminiQuotaTracker : GeminiQuotaTracker {
    override fun tryAcquire(
        keySlot: String,
        modelName: String,
        dailyBudget: Int,
        minuteBudget: Int,
        nowEpochMs: Long
    ) = true
}

interface GeminiQuotaCounterStore {
    fun read(key: String): Int
    fun write(key: String, count: Int)
}

class MemoryGeminiQuotaCounterStore : GeminiQuotaCounterStore {
    private val values = ConcurrentHashMap<String, Int>()
    override fun read(key: String): Int = values[key] ?: 0
    override fun write(key: String, count: Int) {
        values[key] = count
    }
}

private class SharedPreferencesGeminiQuotaCounterStore(context: Context) : GeminiQuotaCounterStore {
    private val preferences =
        context.getSharedPreferences("gemini_request_budgets", Context.MODE_PRIVATE)

    override fun read(key: String): Int = preferences.getInt(key, 0)

    override fun write(key: String, count: Int) {
        check(preferences.edit().putInt(key, count).commit()) {
            "Could not persist local Gemini request budget."
        }
    }
}

class BudgetedGeminiQuotaTracker(
    private val counters: GeminiQuotaCounterStore
) : GeminiQuotaTracker {
    @Synchronized
    override fun tryAcquire(
        keySlot: String,
        modelName: String,
        dailyBudget: Int,
        minuteBudget: Int,
        nowEpochMs: Long
    ): Boolean {
        if (dailyBudget <= 0 || minuteBudget <= 0) return false
        val modelKey = "${keySlot}_${modelName.replace(Regex("[^A-Za-z0-9_.-]"), "_")}"
        val pacificDate = Instant.ofEpochMilli(nowEpochMs)
            .atZone(PACIFIC_ZONE)
            .toLocalDate()
            .toString()
        val minuteBucket = nowEpochMs / 60_000L
        val dayKey = "day_${modelKey}_$pacificDate"
        val minuteKey = "minute_${modelKey}_$minuteBucket"
        val dailyCount = counters.read(dayKey)
        val minuteCount = counters.read(minuteKey)
        if (dailyCount >= dailyBudget || minuteCount >= minuteBudget) return false
        counters.write(dayKey, dailyCount + 1)
        counters.write(minuteKey, minuteCount + 1)
        return true
    }

    companion object {
        val PACIFIC_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
    }
}

class SharedPreferencesGeminiQuotaTracker(context: Context) : GeminiQuotaTracker by
    BudgetedGeminiQuotaTracker(SharedPreferencesGeminiQuotaCounterStore(context))
