package com.jarvis.core

import com.jarvis.data.NotificationRecord
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMessagesTest {
    private fun record(id: String, app: String, sender: String, text: String, at: Long = 1_000L) =
        NotificationRecord(id, "key-$id", "pkg.$app", app, sender, text, at, false)

    @Test
    fun onlyMessageLikeNotificationsFromOtherAppsAreCaptured() {
        val own = "com.jarvis"
        assertTrue(NotificationCapturePolicy.isMessageLike("com.whatsapp", own, null, false, false, false))
        assertTrue(NotificationCapturePolicy.isMessageLike("com.example.chat", own, "msg", false, false, false))
        assertTrue(NotificationCapturePolicy.isMessageLike("com.example.chat", own, null, true, false, false))
        assertFalse(NotificationCapturePolicy.isMessageLike("com.example.news", own, "promo", false, false, false))
        assertFalse(NotificationCapturePolicy.isMessageLike(own, own, "msg", true, false, false))
        assertFalse(NotificationCapturePolicy.isMessageLike("com.whatsapp", own, "msg", true, true, false))
        assertFalse(NotificationCapturePolicy.isMessageLike("com.whatsapp", own, "msg", true, false, true))
    }

    @Test
    fun phoneNumberSendersAreMaskedAndSnippetsAreBounded() {
        assertEquals("Unknown number", NotificationCapturePolicy.sanitizeSender("+91 98765 43210"))
        assertEquals("Unknown number", NotificationCapturePolicy.sanitizeSender("(022) 555-0123"))
        assertEquals("Asha Rao", NotificationCapturePolicy.sanitizeSender("  Asha   Rao "))
        assertEquals("Unknown sender", NotificationCapturePolicy.sanitizeSender(null))
        assertEquals("Room 101", NotificationCapturePolicy.sanitizeSender("Room 101"))
        val long = "word ".repeat(60)
        val snippet = NotificationCapturePolicy.snippet(long)
        assertTrue(snippet.length <= NotificationCapturePolicy.SNIPPET_CHARS)
        assertTrue(snippet.endsWith("…"))
        assertEquals("a b", NotificationCapturePolicy.snippet(" a\n\n b "))
    }

    @Test
    fun recordsAreDedupedByContentAndBlankTextIsDropped() {
        val messages = listOf(
            CapturedMessage("Asha", "See you at 5", 100),
            CapturedMessage("Asha", "See you at 5", 100),
            CapturedMessage("Asha", "   ", 101)
        )
        val records = NotificationCapturePolicy.toRecords("k", "com.whatsapp", "WhatsApp", messages)
        assertEquals(3 - 1, records.size)
        assertEquals(1, records.map { it.id }.toSet().size)
        assertFalse(records.first().read)
    }

    @Test
    fun conversationHistoryFromBeforeCaptureStartedIsNeverStored() {
        val grantedAt = 10_000_000L
        val postTime = grantedAt + 60_000
        val messages = listOf(
            CapturedMessage("Asha", "from yesterday", grantedAt - 86_400_000), // before access was granted
            CapturedMessage("Asha", "older in thread", postTime - NotificationCapturePolicy.FRESH_WINDOW_MS - 1),
            CapturedMessage("Asha", "just now", postTime - 5_000)
        )
        val records = NotificationCapturePolicy.toRecords("k", "com.whatsapp", "WhatsApp", messages, postTime, grantedAt)
        assertEquals(listOf("just now"), records.map { it.textSnippet })
        // Before capture has started (no grant recorded yet) nothing is stored.
        assertTrue(NotificationCapturePolicy.toRecords("k", "com.whatsapp", "WhatsApp", messages, postTime, Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun countAndListAreLocalFilteredAndHonestAboutScope() {
        val records = listOf(
            record("1", "WhatsApp", "Asha", "hi", 3_000),
            record("2", "WhatsApp", "Ravi", "call me", 2_000),
            record("3", "Messages", "Asha", "otp", 1_000)
        )
        val all = NewMessageReplies.count(records, MessageFilter())
        assertTrue(all, all.startsWith("You have 3 new messages: 2 in WhatsApp, 1 in Messages."))
        assertTrue(all.contains("while notification access was on"))
        assertTrue(NewMessageReplies.count(records, MessageFilter(app = "whatsapp")).startsWith("You have 2 new messages in whatsapp"))
        assertTrue(NewMessageReplies.count(records, MessageFilter(contact = "asha")).startsWith("You have 2 new messages from asha"))
        assertTrue(NewMessageReplies.count(records, MessageFilter(contact = "Zed")).startsWith("No new messages from Zed"))

        val (listing, ids) = NewMessageReplies.list(records, MessageFilter(app = "WhatsApp"), Locale.US)
        assertEquals(listOf("1", "2"), ids)
        assertTrue(listing.contains("WhatsApp — Asha"))
        assertFalse(listing.contains("otp"))
    }

    @Test
    fun summaryPromptContainsOnlySenderNamesAndSnippets() {
        val prompt = NewMessageReplies.summaryPrompt(listOf(record("1", "WhatsApp", "Unknown number", "pay rent")))
        assertTrue(prompt.contains("- WhatsApp, Unknown number: pay rent"))
        assertFalse(prompt.contains("pkg."))
    }

    @Test
    fun messageQuestionsParseLocallyWithFilters() {
        fun step(text: String) = (PhoneCommandParser.parse(text) as PhoneCommandParseResult.Parsed).step
        assertEquals(PlanStep("count_new_messages", emptyMap()), step("Any new messages?"))
        assertEquals(PlanStep("count_new_messages", emptyMap()), step("do I have any new messages"))
        assertEquals(PlanStep("count_new_messages", mapOf("app" to "WhatsApp")), step("any new whatsapp messages"))
        assertEquals(PlanStep("count_new_messages", mapOf("contact" to "Asha")), step("how many messages from Asha"))
        assertEquals(PlanStep("count_new_messages", emptyMap()), step("koi naya message aaya?"))
        assertEquals(PlanStep("list_new_messages", mapOf("app" to "WhatsApp")), step("show my new messages on whatsapp"))
        assertEquals(PlanStep("summarize_new_messages", emptyMap()), step("summarize my messages"))
        // Drafting a message is not a notification query.
        assertEquals(PhoneCommandParseResult.NotPhoneCommand, PhoneCommandParser.parse("message Maa that I am late"))
    }

    @Test
    fun messageToolsRouteLocallyAndSummaryNeedsConfirmationFlow() {
        val tools = JarvisCore.AVAILABLE_TOOLS
        assertEquals(ToolRoute.Messages(PlanStep("count_new_messages", emptyMap())), ToolRouter.route(PlanStep("count_new_messages", emptyMap()), tools))
        // Blank optional arguments from a model are dropped, unknown ones rejected.
        assertEquals(
            ToolRoute.Messages(PlanStep("list_new_messages", mapOf("app" to "WhatsApp"))),
            ToolRouter.route(PlanStep("list_new_messages", mapOf("app" to "WhatsApp", "contact" to "")), tools)
        )
        assertTrue(ToolRouter.route(PlanStep("list_new_messages", mapOf("since" to "today")), tools) is ToolRoute.Reject)
        assertTrue(ToolRiskGate.requiresConfirmation("summarize_new_messages"))
        assertFalse(ToolRiskGate.requiresConfirmation("count_new_messages"))
        val schema = ReasoningToolSchema.toOpenAiJson(listOf("list_new_messages")).getJSONObject(0)
            .getJSONObject("function").getJSONObject("parameters")
        assertTrue(schema.getJSONObject("properties").has("contact"))
        assertEquals(0, schema.getJSONArray("required").length())
    }
}
