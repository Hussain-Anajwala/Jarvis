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
        assertTrue(prompt.contains("Friday 2026-10-23"))
        assertTrue(prompt.contains("Sunday 2026-10-18"))
        assertTrue(prompt.contains("call calendar.cancel directly"))
        assertTrue(prompt.contains("ask whether it is AM or PM"))
    }

    @Test
    fun argumentParserAcceptsNullOptionalsAndNumbersButRejectsUnknownKeys() {
        val (counted, error) = ReasoningToolSchema.parseArguments(
            "count_new_messages", org.json.JSONObject("""{"app":null,"contact":"Asha"}""")
        )
        assertTrue(error == null && counted == mapOf("contact" to "Asha"))
        val (timer, _) = ReasoningToolSchema.parseArguments("set_timer", org.json.JSONObject("""{"duration_seconds":600}"""))
        assertTrue(timer == mapOf("duration_seconds" to "600"))
        val (flash, _) = ReasoningToolSchema.parseArguments("flashlight", org.json.JSONObject("""{"enabled":true}"""))
        assertTrue(flash == mapOf("enabled" to "true"))
        assertTrue(ReasoningToolSchema.parseArguments("set_timer", org.json.JSONObject("""{"duration_seconds":600,"x":1}""")).second != null)
        assertTrue(ReasoningToolSchema.parseArguments("set_timer", org.json.JSONObject("""{"duration_seconds":null}""")).second != null)
        assertTrue(ReasoningToolSchema.parseArguments("set_timer", org.json.JSONObject("""{"duration_seconds":[1]}""")).second != null)
        val openAi = ReasoningToolSchema.toOpenAiJson(listOf("count_new_messages")).getJSONObject(0)
            .getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        assertTrue(openAi.getJSONObject("app").getJSONArray("type").toString() == """["string","null"]""")
    }
}
