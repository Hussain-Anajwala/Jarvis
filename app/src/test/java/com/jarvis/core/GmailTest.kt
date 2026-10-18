package com.jarvis.core

import java.util.Locale
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailTest {
    private fun client(server: MockWebServer, token: String? = "tok", invalidated: MutableList<String> = mutableListOf()) =
        GmailClient(
            accessToken = { token },
            invalidateToken = { invalidated += it },
            baseUrl = server.url("/gmail/v1/users/me/").toString(),
            sleeper = {}
        )

    @Test
    fun unreadCountReadsInboxLabelWithBearerToken() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"INBOX","messagesUnread":7}"""))
            server.start()
            assertEquals(GmailResult.Success(7), client(server).unreadCount())
            val request = server.takeRequest()
            assertEquals("/gmail/v1/users/me/labels/INBOX", request.requestUrl?.encodedPath)
            assertEquals("Bearer tok", request.getHeader("Authorization"))
        }
    }

    @Test
    fun todaysEmailsListsThenFetchesMetadataOnly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"messages":[{"id":"abc1"},{"id":"../bad"}]}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"id":"abc1","snippet":"Can you send the report &amp; slides?","internalDate":"1760700000000",
                    "payload":{"headers":[{"name":"From","value":"\"Prof Rao\" <rao@college.edu>"},{"name":"Subject","value":"Report"}]}}"""
                )
            )
            server.start()
            val result = client(server).todaysEmails(1_760_659_200)
            val emails = (result as GmailResult.Success).value
            assertEquals(1, emails.size)
            assertEquals("Prof Rao", emails[0].senderName)
            assertEquals("rao@college.edu", emails[0].senderAddress)
            assertEquals("Can you send the report & slides?", emails[0].snippet)
            val list = server.takeRequest()
            assertEquals("in:inbox after:1760659200", list.requestUrl?.queryParameter("q"))
            val detail = server.takeRequest()
            assertEquals("metadata", detail.requestUrl?.queryParameter("format"))
            assertEquals(2, server.requestCount) // the invalid id was never requested
        }
    }

    @Test
    fun authScopeRateAndServerErrorsAreTyped() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"Request had insufficient authentication scopes."}}"""))
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("""{"messagesUnread":2}"""))
            server.enqueue(MockResponse().setBody("not json"))
            server.start()
            val invalidated = mutableListOf<String>()
            val gmail = client(server, invalidated = invalidated)
            assertEquals(GmailError.NOT_AUTHORIZED, (gmail.unreadCount() as GmailResult.Failure).error)
            assertEquals(listOf("tok"), invalidated)
            assertEquals(GmailError.INSUFFICIENT_SCOPE, (gmail.unreadCount() as GmailResult.Failure).error)
            assertEquals(GmailError.RATE_LIMITED, (gmail.unreadCount() as GmailResult.Failure).error) // after one retry
            assertEquals(GmailResult.Success(2), gmail.unreadCount()) // 503 retried once, then succeeded
            assertEquals(GmailError.MALFORMED, (gmail.unreadCount() as GmailResult.Failure).error)
        }
    }

    @Test
    fun missingTokenAndNetworkFailureNeverCrash() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.start()
            assertEquals(GmailError.NOT_AUTHORIZED, (client(server, token = null).unreadCount() as GmailResult.Failure).error)
            assertEquals(0, server.requestCount)
            assertEquals(GmailError.NETWORK, (client(server).unreadCount() as GmailResult.Failure).error)
        }
    }

    @Test
    fun sendersAreSplitAndPromptsNeverContainAddresses() {
        assertEquals("Asha" to "a@x.com", GmailClient.splitSender("Asha <a@x.com>"))
        assertEquals("Unknown sender" to "a@x.com", GmailClient.splitSender("a@x.com"))
        assertEquals("Unknown sender" to "a@x.com", GmailClient.splitSender("<a@x.com>"))
        assertNull(GmailClient.parseMessage("x", JSONObject("""{"snippet":"no payload"}""")))
        assertEquals("Unknown sender" to "a@x.com", GmailClient.splitSender("\"a@x.com\" <a@x.com>"))
        val leaky = EmailSummary("2", "Bank", null, "Mail ops@bank.com", "call +91 98765 43210 or 1800-123-4567 by 5", 0)
        val masked = EmailReplies.summaryPrompt(listOf(leaky))
        assertFalse(masked.contains("@"))
        assertFalse(masked.contains("98765"))
        assertTrue(masked.contains("[email]") && masked.contains("[number]") && masked.contains("by 5"))
        val email = EmailSummary("1", "Asha", "a@x.com", "Hi", "see attached", 0)
        val prompt = EmailReplies.summaryPrompt(listOf(email))
        assertTrue(prompt.contains("Asha: Hi — see attached"))
        assertFalse(prompt.contains("@"))
        assertTrue(EmailReplies.today(listOf(email), Locale.US).contains("Asha"))
        assertFalse(EmailReplies.today(listOf(email), Locale.US).contains("a@x.com"))
        assertEquals("You have 1 unread email in your Gmail inbox.", EmailReplies.unread(1))
    }

    @Test
    fun composerPromptHasNoAddressAndDraftParsingFallsBackSafely() {
        val prompt = EmailComposer.prompt("Prof. Rao", "ask for an extension on the report", null, null, null)
        assertTrue(prompt.contains("Prof. Rao"))
        assertFalse(prompt.contains("@"))
        assertEquals(EmailDraft("Extension request", "Dear Prof. Rao,\n\nCould I have two more days?\n\nRegards"),
            EmailComposer.parse("Subject: Extension request\n\nDear Prof. Rao,\n\nCould I have two more days?\n\nRegards"))
        assertNull(EmailComposer.parse("Sure! Here is an email for you."))
        val template = EmailComposer.template("Prof. Rao", "ask for an extension", "Hussain", null)
        assertTrue(template.body.startsWith("Dear Prof. Rao,"))
        assertTrue(template.body.endsWith("Regards,\nHussain"))
    }

    @Test
    fun emailRequestsParseLocallyAndRouteThroughTheUiWithRiskGates() {
        fun step(text: String) = (PhoneCommandParser.parse(text) as PhoneCommandParseResult.Parsed).step
        assertEquals("email_unread_count", step("How many unread emails do I have?").toolName)
        assertEquals("email_unread_count", step("check my email").toolName)
        assertEquals("email_today", step("show today's emails").toolName)
        assertEquals("email_today", step("what emails did I get today").toolName)
        assertEquals("summarize_emails", step("summarize my emails").toolName)
        assertEquals(
            PlanStep("compose_email", mapOf("recipient_name" to "Prof. Rao", "request" to "asking for an extension on the report")),
            step("Write an email to Prof. Rao asking for an extension on the report")
        )
        assertEquals("compose_email", step("email Asha about tomorrow's viva").toolName)
        assertTrue(PhoneCommandParser.parse("write an email to someone about lunch") is PhoneCommandParseResult.Clarification)
        // Texting is still a message draft, not email.
        assertEquals(PhoneCommandParseResult.NotPhoneCommand, PhoneCommandParser.parse("message Maa that I am late"))

        val tools = JarvisCore.AVAILABLE_TOOLS
        assertEquals(ToolRoute.Email(PlanStep("email_today", emptyMap())), ToolRouter.route(PlanStep("email_today", emptyMap()), tools))
        assertTrue(ToolRiskGate.requiresConfirmation("compose_email"))
        assertTrue(ToolRiskGate.requiresConfirmation("summarize_emails"))
        assertFalse(ToolRiskGate.requiresConfirmation("email_unread_count"))
        assertTrue(ToolRouter.route(PlanStep("compose_email", mapOf("recipient_name" to "Asha")), tools) is ToolRoute.Reject)
        assertTrue(ToolRouter.route(PlanStep("send_email", mapOf("to" to "a@x.com")), tools + "send_email") is ToolRoute.Reject)
    }

    @Test
    fun contactEmailMatchingPrefersExactAndStopsOnAmbiguity() {
        val rows = listOf("Asha Rao" to "asha@x.com", "Asha Mehta" to "mehta@y.com", "Ravi" to "ravi@z.com", "Ravi" to "no-at-sign")
        assertEquals(
            EmailResolution.Ambiguous(listOf(EmailResolution.Found("Asha Rao", "asha@x.com"), EmailResolution.Found("Asha Mehta", "mehta@y.com"))),
            EmailContactMatcher.choose("Asha", rows)
        )
        assertEquals(EmailResolution.Found("Asha Rao", "asha@x.com"), EmailContactMatcher.choose("asha rao", rows))
        assertEquals(EmailResolution.Found("Ravi", "ravi@z.com"), EmailContactMatcher.choose("Ravi", rows))
        assertEquals(EmailResolution.NotFound, EmailContactMatcher.choose("Zed", rows))
    }

    @Test
    fun mailtoEncodesDraftAndRejectsInjectedAddresses() {
        val uri = EmailComposer.mailto("rao@college.edu", EmailDraft("Hi & bye", "Line 1\nLine 2"))
        assertEquals("mailto:rao@college.edu?subject=Hi%20%26%20bye&body=Line%201%0ALine%202", uri)
        assertNull(EmailComposer.mailto("rao@college.edu?bcc=evil@x.com", EmailDraft("a", "b")))
        assertNull(EmailComposer.mailto("not an address", EmailDraft("a", "b")))
    }
}
