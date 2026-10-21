package com.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 0 item 3.4: a recipient JARVIS can't look up gets asked for, parsed, read back, and validated. */
class EmailAddressTest {
    @Test
    fun spokenAddressesBecomeValidAddresses() {
        assertEquals("asha.rao@gmail.com", SpokenEmail.parse("asha dot rao at gmail dot com"))
        assertEquals("asha.rao@gmail.com", SpokenEmail.parse("it's asha dot rao at the rate gmail dot com"))
        assertEquals("hr_team-2@company.co.in", SpokenEmail.parse("hr underscore team dash two at company dot co dot in"))
        assertEquals("anna@g.com", SpokenEmail.parse("a double n a at g dot com"))
        assertEquals("asha@gmail.com", SpokenEmail.parse("Asha@Gmail.com."))
        assertEquals("asha@gmail.com", SpokenEmail.parse("asha at g mail dot com"))
    }

    @Test
    fun incompleteOrMalformedAddressesAreRejected() {
        listOf("asha rao", "asha at gmail", "asha at at gmail dot com", "at gmail dot com", "asha dot dot rao at gmail dot com", "my manager")
            .forEach { assertNull(it, SpokenEmail.parse(it)) }
        assertFalse(SpokenEmail.isValid("a..b@x.com"))
        assertTrue(SpokenEmail.isValid("a.b+tag@x.io"))
    }

    private val leave = EmailRequest(EmailRequest.Kind.LEAVE, dates = "tomorrow", reason = "a fever", recipient = "my manager")

    @Test
    fun aRoleRecipientIsAskedForThenConfirmedBeforeDrafting() {
        val ask = EmailDialogue.askAddress(leave, "I can't look up an address for your manager.")
        assertEquals(EmailSlot.ADDRESS, ask.slot)
        val confirm = EmailDialogue.answer(ask.request, EmailSlot.ADDRESS, "priya dot shah at acme dot com") as EmailStep.Ask
        assertEquals(EmailSlot.CONFIRM_ADDRESS, confirm.slot)
        assertTrue(confirm.question.contains("priya.shah@acme.com"))
        val ready = EmailDialogue.answer(confirm.request, EmailSlot.CONFIRM_ADDRESS, "yes") as EmailStep.Ready
        assertEquals("priya.shah@acme.com", ready.request.address)
        assertEquals("my manager", ready.request.recipient)
    }

    @Test
    fun noAsksAgainACorrectionIsReadBackAndSkipLeavesItBlank() {
        val confirm = EmailDialogue.answer(EmailDialogue.askAddress(leave, "").request, EmailSlot.ADDRESS, "priya at acme dot com") as EmailStep.Ask
        val again = EmailDialogue.answer(confirm.request, EmailSlot.CONFIRM_ADDRESS, "no") as EmailStep.Ask
        assertEquals(EmailSlot.ADDRESS, again.slot)
        assertNull(again.request.addressCandidate)
        val corrected = EmailDialogue.answer(confirm.request, EmailSlot.CONFIRM_ADDRESS, "no, it's priya dot s at acme dot com") as EmailStep.Ask
        assertEquals(EmailSlot.CONFIRM_ADDRESS, corrected.slot)
        assertEquals("priya.s@acme.com", corrected.request.addressCandidate)
        val unclear = EmailDialogue.answer(confirm.request, EmailSlot.CONFIRM_ADDRESS, "hmm") as EmailStep.Ask
        assertEquals(EmailSlot.CONFIRM_ADDRESS, unclear.slot)
        assertEquals("", (EmailDialogue.answer(confirm.request, EmailSlot.ADDRESS, "skip") as EmailStep.Ready).request.address)
        // A garbled answer is asked again, never drafted to.
        val bad = EmailDialogue.answer(confirm.request, EmailSlot.ADDRESS, "priya at acme") as EmailStep.Ask
        assertEquals(EmailSlot.ADDRESS, bad.slot)
        assertEquals(EmailStep.Cancelled, EmailDialogue.answer(confirm.request, EmailSlot.CONFIRM_ADDRESS, "cancel"))
    }
}
