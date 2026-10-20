package com.jarvis.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2: each routing rule, in priority order, with the rule recorded. */
class AdaptiveRouterTest {
    private val tools = JarvisCore.AVAILABLE_TOOLS

    private fun route(text: String, cloud: Boolean = true, answering: Boolean = false): RouteDecision = runBlocking {
        val offline = OfflineRuleProvider().reason(ReasoningRequest(text, tools))
        AdaptiveRouter.decide(text, offline.steps.firstOrNull(), tools, cloud, answering)
    }

    @Test
    fun secretsAndOnDeviceHandlersNeverReachAModel() {
        assertEquals(RouteTarget.ON_DEVICE, route("my gmail password is hunter2").target)
        assertTrue(route("my gmail password is hunter2").rule.startsWith("privacy"))
        for (text in listOf("turn the alarm off", "snooze it for five minutes", "cancel the timer", "write an email to my manager", "tell me about me")) {
            assertEquals(text, RouteTarget.ON_DEVICE, route(text).target)
        }
    }

    @Test
    fun recognisedPhoneCommandsAndConfidentParsesStayLocal() {
        assertEquals(RouteTarget.LOCAL_PARSER, route("Set a timer for 10 minutes").target)
        assertEquals(RouteTarget.LOCAL_PARSER, route("Turn on the flashlight").target)
    }

    @Test
    fun freeTextDraftsAndUnclearRequestsGoToTheCloud() {
        assertEquals(RouteTarget.CLOUD, route("What should I cook for dinner tonight?").target)
        val draft = route("Text Rahul saying I'll be there in ten minutes")
        assertTrue(draft.rule, draft.target == RouteTarget.CLOUD || draft.rule.contains("phone command"))
    }

    @Test
    fun withoutACloudEverythingIsLocalAndAnAnswerToAQuestionKeepsTheModelsContext() {
        assertEquals(RouteTarget.LOCAL_PARSER, route("What should I cook for dinner tonight?", cloud = false).target)
        val answer = route("at 5 pm", answering = true)
        assertTrue(answer.rule, answer.target != RouteTarget.LOCAL_PARSER || answer.rule.contains("phone command"))
    }

    @Test
    fun aConfidentParseMustPassTheSchemaCheck() {
        // A draft with no body is never "confident", even though the parser proposed it.
        val blank = AdaptiveRouter.decide("text Rahul", PlanStep("communication.draft", mapOf("recipient" to "Rahul", "body" to "")), tools, true)
        assertEquals(RouteTarget.CLOUD, blank.target)
        val invalid = AdaptiveRouter.decide("whatever", PlanStep("set_alarm", mapOf("hour" to "?", "minute" to "0")), tools, true)
        assertEquals(RouteTarget.CLOUD, invalid.target)
    }
}
