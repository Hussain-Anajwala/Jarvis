package com.jarvis.core

import com.jarvis.data.DecisionTraceEntry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4.1: no path — foreground or background — stores a secret value. Synthetic sentinels only. */
class SafeStorageTest {
    private val otp = "552918"
    private val sources = File("src/main/java")

    @Test
    fun everyTraceWriteInTheAppGoesThroughTheRedactingInsert() {
        val offenders = sources.walkTopDown().filter { it.extension == "kt" && it.name != "SafeStorage.kt" }
            .filter { file -> Regex("""traceDao\(\)\s*\.\s*insert\(""").containsMatchIn(file.readText()) }
            .map { it.name }.toList()
        assertEquals("trace rows must be written with insertRedacted", emptyList<String>(), offenders)
        // Background engines (plan checks, travel re-checks, briefing) use it too.
        listOf("core/BackgroundEngine.kt", "core/Briefing.kt", "core/PlanEngine.kt", "core/JarvisCore.kt").forEach {
            assertTrue(it, File(sources, "com/jarvis/$it").readText().contains("insertRedacted("))
        }
    }

    @Test
    fun userTextIsSanitisedWhereverItIsStoredOrHandedToTheCalendar() {
        mapOf(
            "core/PlanEngine.kt" to listOf("SafeStorage.text(goal)", "SafeStorage.text(reminderTitle)"),
            "agents/Agents.kt" to listOf("SafeStorage.text(call.parameters[\"title\"] ?: \"Meeting\")", "SafeStorage.text(call.parameters[\"location\"]", "SafeStorage.text(call.parameters[\"title\"] ?: \"New task\")"),
            "core/NotificationMessages.kt" to listOf("snippet(SafeStorage.text(message.text))"),
            "core/PersonalMemory.kt" to listOf("SafeStorage.text(classified.value)", "SafeStorage.text(value.trim())")
        ).forEach { (file, needles) ->
            val text = File(sources, "com/jarvis/$file").readText()
            needles.forEach { assertTrue("$file must use $it", text.contains(it)) }
        }
    }

    @Test
    fun aTraceKeepsItsMeaningButNotTheValue() {
        val entry = DecisionTraceEntry("t", null, "Created meeting \"Bank visit, PIN is 739204\" at 10:30", "low", "plan.create", "OTP $otp received", 1L)
        val safe = SafeStorage.trace(entry)
        assertFalse(safe.actionSummary.contains("739204"))
        assertFalse(safe.outcome.contains(otp))
        assertTrue(safe.actionSummary.contains("at 10:30") && safe.actionSummary.contains("Bank visit"))
    }

    @Test
    fun capturedBankMessagesLoseOnlyTheCode() {
        val records = NotificationCapturePolicy.toRecords(
            "k", "com.bank", "Bank",
            listOf(
                CapturedMessage("HDFC Bank", "$otp is your OTP for txn of Rs 1,250 at Amazon. Do not share.", 10L),
                CapturedMessage("Asha", "Flight AI 2025 lands at 10:30, gate 4", 11L)
            )
        )
        assertFalse(records.any { it.textSnippet.contains(otp) })
        assertTrue(records.first().textSnippet.contains("Rs 1,250 at Amazon"))
        assertEquals("Flight AI 2025 lands at 10:30, gate 4", records.last().textSnippet)
    }

    @Test
    fun ordinaryTextIsStoredUnchanged() {
        listOf("Meeting at college tomorrow 10:00", "Pay ₹2,499 by 21/10/2026", "Flight AI 2025, seat 12A", "Call Asha about the 2026 batch")
            .forEach { assertEquals(it, it, SafeStorage.text(it)) }
    }
}
