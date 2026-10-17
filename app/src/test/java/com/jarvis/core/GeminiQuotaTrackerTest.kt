package com.jarvis.core

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiQuotaTrackerTest {
    @Test
    fun limitsRequestsPerKeyModelAndMinute() {
        val tracker = BudgetedGeminiQuotaTracker(MemoryGeminiQuotaCounterStore())
        val minute = 1_797_000_000_000L

        repeat(4) {
            assertTrue(tracker.tryAcquire("key-1", "gemini-flash-latest", 18, 4, minute))
        }
        assertFalse(tracker.tryAcquire("key-1", "gemini-flash-latest", 18, 4, minute))
        assertTrue(tracker.tryAcquire("key-1", "gemini-3.7-flash", 18, 4, minute))
        assertTrue(tracker.tryAcquire("key-2", "gemini-flash-latest", 18, 4, minute))
        assertTrue(tracker.tryAcquire("key-1", "gemini-flash-latest", 18, 4, minute + 60_000L))
    }

    @Test
    fun resetsDailyBudgetAtMidnightPacific() {
        val tracker = BudgetedGeminiQuotaTracker(MemoryGeminiQuotaCounterStore())
        val zone = ZoneId.of("America/Los_Angeles")
        val midnight = LocalDate.parse("2026-10-17").atStartOfDay(zone).toInstant().toEpochMilli()
        val justBeforeMidnight = midnight - 1

        repeat(18) {
            assertTrue(tracker.tryAcquire("key-1", "gemini-flash-latest", 18, 100, justBeforeMidnight))
        }
        assertFalse(tracker.tryAcquire("key-1", "gemini-flash-latest", 18, 100, justBeforeMidnight))
        assertTrue(tracker.tryAcquire("key-1", "gemini-flash-latest", 18, 100, midnight))
    }
}
