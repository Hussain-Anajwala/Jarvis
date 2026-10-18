package com.jarvis.ui

import com.jarvis.data.DecisionTraceEntry
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityFeedTest {
    private var n = 0
    private fun trace(
        tool: String, outcome: String, summary: String = "summary", risk: String = "low",
        at: Long = 1_000L + n, model: String = "groq/openai/gpt-oss-120b", plan: String? = null
    ) = DecisionTraceEntry("id${n++}", plan, summary, risk, tool, outcome, at, model)

    @Test
    fun statusIsJudgedFromTheRecordedOutcomeAndNeverDefaultsToDone() {
        fun status(tool: String, outcome: String) = ActivityFeed.status(trace(tool, outcome))
        assertEquals(ActivityStatus.DONE, status("play_music", "playback confirmed by media session"))
        assertEquals(ActivityStatus.PROBLEM, status("play_music", "handed off; playback not started"))
        assertEquals("handed off but unverifiable is a note, not a success", ActivityStatus.NOTE, status("play_music", "handed off; playback not observable"))
        assertEquals(ActivityStatus.PROBLEM, status("call_contact", "phone permission denied"))
        assertEquals(ActivityStatus.PROBLEM, status("call_contact", "failed"))
        assertEquals(ActivityStatus.NOTE, status("call_contact", "cancelled by user"))
        assertEquals(ActivityStatus.PROBLEM, status("email_unread_count", "gmail access revoked"))
        assertEquals(ActivityStatus.PROBLEM, status("summarize_emails", "no cloud model available; nothing sent"))
        assertEquals(ActivityStatus.DONE, status("summarize_emails", "sent 4 email snippet(s) to groq after confirmation"))
        assertEquals("turning a setting off is a success", ActivityStatus.DONE, status("daily_briefing", "wake word listening turned off"))
        assertEquals(ActivityStatus.PROBLEM, status("count_new_messages", "notification access off"))
        assertEquals(ActivityStatus.DONE, status("background.plan_evaluation", "executed"))
        assertEquals(ActivityStatus.DONE, status("reasoning.request", "http_200"))
        assertEquals(ActivityStatus.PROBLEM, status("reasoning.request", "http_429"))
        assertEquals(ActivityStatus.PROBLEM, status("reasoning.request", "network_error"))
        assertEquals(ActivityStatus.NOTE, status("reasoning", "fallback: provider unavailable"))
        assertEquals("anything unrecognised stays neutral", ActivityStatus.NOTE, status("meeting.create", "some new outcome text"))
    }

    @Test
    fun messageBodiesAndOtherUserTextAreNeverShownInTheTitleOrDetail() {
        val secret = "Drafted + handed off: tell Maa the exam result is 91 percent"
        val event = ActivityFeed.from(trace("communication.handoff", "confirmed", summary = secret, risk = "medium"))
        assertEquals("Message draft handed off", event.title)
        assertFalse(secret.substringAfter(": ") in (event.detail.orEmpty() + event.title + event.meta))
        val mail = ActivityFeed.from(trace("compose_email", "draft prepared by groq", summary = "Subject: salary details"))
        assertFalse("salary" in (mail.title + mail.detail.orEmpty()))
        // Only tools whose summaries JARVIS itself wrote may show them.
        val check = ActivityFeed.from(trace("background.plan_evaluation", "executed", summary = "Background evaluation checked 0 active plan(s) and 0 monitoring rule(s)"))
        assertTrue("0 active plan(s)" in check.detail.orEmpty())
    }

    @Test
    fun backgroundChecksAndModelCallsAreSystemEventsAndEverythingElseIsAnAction() {
        assertEquals(ActivityCategory.SYSTEM, ActivityFeed.from(trace("background.plan_evaluation", "executed")).category)
        assertEquals(ActivityCategory.SYSTEM, ActivityFeed.from(trace("reasoning.request", "http_200")).category)
        assertEquals(ActivityCategory.ACTION, ActivityFeed.from(trace("play_music", "playback confirmed by media session")).category)
        assertEquals(ActivityCategory.ACTION, ActivityFeed.from(trace("travel.monitor", "updated")).category)
        assertEquals("the model choosing a tool is not the action itself", ActivityCategory.SYSTEM, ActivityFeed.from(trace("get_weather", "planned")).category)
        assertEquals(ActivityStatus.PROBLEM, ActivityFeed.status(trace("get_weather", "No recent device location")))
    }

    @Test
    fun filtersShowActionsByDefaultKeepProblemsVisibleAndAlwaysSortNewestFirst() {
        val events = listOf(
            trace("play_music", "playback confirmed by media session", at = 100),
            trace("background.plan_evaluation", "executed", at = 300),
            trace("reasoning.request", "http_429", at = 200),
            trace("call_contact", "phone permission denied", risk = "medium", at = 50, plan = "p1")
        ).map(ActivityFeed::from)
        assertEquals(listOf(100L, 50L), ActivityFeed.filter(events, ActivityFilter.ACTIONS, null, null).map { it.createdAt })
        assertEquals("a failed model call still shows under Problems", listOf(200L, 50L), ActivityFeed.filter(events, ActivityFilter.PROBLEMS, null, null).map { it.createdAt })
        assertEquals(listOf(300L, 200L, 100L, 50L), ActivityFeed.filter(events, ActivityFilter.EVERYTHING, null, null).map { it.createdAt })
        assertEquals(listOf(50L), ActivityFeed.filter(events, ActivityFilter.EVERYTHING, "medium", null).map { it.createdAt })
        assertEquals(listOf(50L), ActivityFeed.filter(events, ActivityFilter.EVERYTHING, null, "p1").map { it.createdAt })
    }

    @Test
    fun timestampsReadAsTodayYesterdayOrADate() {
        val zone = ZoneId.of("Asia/Kolkata")
        fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
        val now = at(2026, 10, 18, 17, 30)
        fun fmt(millis: Long) = ActivityFeed.time(millis, now, zone, Locale.ENGLISH)
        assertEquals("Today, 5:32 PM", fmt(at(2026, 10, 18, 17, 32)))
        assertEquals("Yesterday, 9:05 AM", fmt(at(2026, 10, 17, 9, 5)))
        assertEquals("Mon 12 Oct, 4:00 PM", fmt(at(2026, 10, 12, 16, 0)))
        assertEquals("3 Mar 2025, 11:15 AM", fmt(at(2025, 3, 3, 11, 15)))
    }

    @Test
    fun modelOutcomesAreReadableSentences() {
        assertEquals("The model was rate limited", ActivityFeed.humanOutcome("http_429"))
        assertEquals("The model returned an error (503)", ActivityFeed.humanOutcome("http_503"))
        assertEquals("The network request failed", ActivityFeed.humanOutcome("network_error"))
        val event = ActivityFeed.from(trace("reasoning.request", "http_429", model = "gemini/flash"))
        assertEquals("The model was rate limited", event.detail)
        assertEquals("gemini/flash · low risk", event.meta)
    }
}
