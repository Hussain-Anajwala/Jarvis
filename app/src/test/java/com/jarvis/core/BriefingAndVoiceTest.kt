package com.jarvis.core

import com.jarvis.data.Event
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefingAndVoiceTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 19)
    private fun at(hour: Int, day: LocalDate = today) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun inputs(
        events: List<Event>? = listOf(Event("1", 0, "Viva", at(10), at(11), "College", true), Event("2", 0, "Gym", at(18, today.plusDays(1)), 0, null, true)),
        travel: TravelTimeEstimate? = TravelTimeEstimate(25, "live", "OSRM from device location"),
        weather: WeatherNow? = WeatherNow(29.0, "partly cloudy", 26.0, 33.0, 40),
        weatherProblem: String? = null,
        tasks: Int? = 2,
        unread: Int? = null,
        emailProblem: String? = null,
        messages: Int? = null,
        messagesProblem: String? = null
    ) = BriefingInputs(today, events, travel, weather, weatherProblem, tasks, unread, emailProblem, messages, messagesProblem)

    @Test
    fun briefingIncludesOnlyEnabledSectionsAndTodaysEvents() {
        val (text, sections) = BriefingAssembler.assemble(BriefingSettings(enabled = true), inputs(), zone, Locale.US)
        assertTrue(text, text.startsWith("Good morning. Briefing for Mon 19 Oct:"))
        assertTrue(text.contains("Calendar: 1 today — 10:00 AM Viva."))
        assertFalse(text.contains("Gym"))
        assertTrue(text.contains("Travel to your next meeting: 25 min (live)."))
        assertTrue(text.contains("Weather: 29°C, partly cloudy."))
        assertTrue(text.contains("Tasks: 2 open."))
        assertFalse(text.contains("Email") || text.contains("Messages"))
        assertEquals(SectionStatus.OFF, sections.first { it.name == "Email" }.status)
    }

    @Test
    fun failuresAndEmptyResultsAreStatedNotHidden() {
        val settings = BriefingSettings(enabled = true, unreadEmail = true, newMessages = true)
        val (text, sections) = BriefingAssembler.assemble(
            settings,
            inputs(
                events = emptyList(), travel = null, weather = null, weatherProblem = "location permission off", tasks = null,
                emailProblem = "Gmail authorization expired. Connect Gmail again.", messagesProblem = "notification access off"
            ),
            zone, Locale.US
        )
        assertTrue(text.contains("Calendar: nothing scheduled today."))
        assertTrue(text.contains("Weather: unavailable (location permission off)."))
        assertTrue(text.contains("Tasks: unavailable."))
        assertTrue(text.contains("Email: unavailable (Gmail authorization expired. Connect Gmail again.)."))
        assertTrue(text.contains("Messages: unavailable (notification access off)."))
        assertEquals(SectionStatus.UNAVAILABLE, sections.first { it.name == "Weather" }.status)
        val estimated = BriefingAssembler.assemble(BriefingSettings(), inputs(travel = TravelTimeEstimate(30, "estimated", "no recent device location")), zone, Locale.US).first
        assertTrue(estimated.contains("30 min (estimated: no recent device location)"))
        val allOff = BriefingSettings(calendar = false, travel = false, weather = false, tasks = false)
        assertTrue(BriefingAssembler.assemble(allOff, inputs(), zone, Locale.US).first.endsWith("all briefing sections are turned off."))
    }

    @Test
    fun scheduleRunsOncePerDayAtTheChosenTime() {
        val now = ZonedDateTime.of(2026, 10, 19, 6, 0, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 10, 19, 7, 30, 0, 0, zone), BriefingSchedule.nextRun(now, 7, 30))
        assertEquals(ZonedDateTime.of(2026, 10, 20, 7, 30, 0, 0, zone), BriefingSchedule.nextRun(now.withHour(7).withMinute(30), 7, 30))
        assertEquals(ZonedDateTime.of(2026, 10, 20, 5, 0, 0, 0, zone), BriefingSchedule.nextRun(now, 5, 0))
        assertTrue(BriefingSchedule.shouldDeliver(today, null))
        assertTrue(BriefingSchedule.shouldDeliver(today, "2026-10-18"))
        assertFalse(BriefingSchedule.shouldDeliver(today, "2026-10-19"))
        assertFalse(BriefingSettings().enabled)
        assertFalse(BriefingSettings().unreadEmail || BriefingSettings().newMessages)
    }

    @Test
    fun briefingRequestsParseLocally() {
        for (text in listOf("good morning", "Good morning JARVIS", "give me my briefing", "brief me", "whats on my day", "how does my day look")) {
            assertEquals(text, PlanStep("daily_briefing", emptyMap()), (PhoneCommandParser.parse(text) as PhoneCommandParseResult.Parsed).step)
        }
        assertEquals(ToolRoute.Briefing, ToolRouter.route(PlanStep("daily_briefing", emptyMap()), JarvisCore.AVAILABLE_TOOLS))
    }

    @Test
    fun bareRemindMeAsksWhatToRemind() = kotlinx.coroutines.runBlocking {
        val response = OfflineRuleProvider().reason(ReasoningRequest("Remind me", JarvisCore.AVAILABLE_TOOLS))
        assertTrue(response.steps.isEmpty())
        assertEquals("What should I remind you about?", response.reply)
        val real = OfflineRuleProvider().reason(ReasoningRequest("Remind me to take medicine", JarvisCore.AVAILABLE_TOOLS))
        assertEquals("reminder.create", real.steps.single().toolName)
    }

    @Test
    fun voiceIsOptionalAndEveryFailureFallsBackToText() {
        assertEquals(VoiceStart.Listen, VoicePolicy.onMicTapped(permissionGranted = true, recognitionAvailable = true, permanentlyDenied = false))
        assertEquals(VoiceStart.RequestPermission, VoicePolicy.onMicTapped(false, true, false))
        assertTrue(VoicePolicy.onMicTapped(true, recognitionAvailable = false, permanentlyDenied = false) is VoiceStart.Unavailable)
        assertTrue((VoicePolicy.onMicTapped(false, true, permanentlyDenied = true) as VoiceStart.Unavailable).message.contains("type"))
        assertTrue((VoicePolicy.onPermissionResult(false) as VoiceStart.Unavailable).message.contains("type your request"))
        assertEquals(VoiceStart.Listen, VoicePolicy.onPermissionResult(true))
        for (code in 1..12) assertTrue(VoicePolicy.errorMessage(code).isNotBlank())
        assertTrue(VoicePolicy.errorMessage(7).contains("didn't catch"))
        assertEquals("set a timer", VoicePolicy.bestTranscript(listOf("  ", " set a timer ")))
        assertEquals(null, VoicePolicy.bestTranscript(emptyList()))
        assertEquals("Today: one two", VoicePolicy.speakable("Today:\n• one\n• two"))
        assertTrue(VoicePolicy.speakable("x".repeat(700)).length <= 601)
    }
}
