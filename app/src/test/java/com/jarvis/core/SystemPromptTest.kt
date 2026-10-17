package com.jarvis.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptTest {
    @Test
    fun promptGivesTodaysDateSoRelativeDatesResolveWithoutAskingForAFormat() {
        val prompt = JarvisSystemPrompt.build(ZonedDateTime.of(2026, 10, 17, 22, 0, 0, 0, ZoneId.of("Asia/Kolkata")))
        assertTrue(prompt.contains("Today is Saturday, 2026-10-17"))
        assertTrue(prompt.contains("Asia/Kolkata"))
        assertTrue(prompt.contains("never ask the user for a date format"))
        assertTrue(prompt.contains("Never guess a time, place, or recipient"))
        assertTrue(prompt.contains("Use the event the user names as the title"))
    }
}
