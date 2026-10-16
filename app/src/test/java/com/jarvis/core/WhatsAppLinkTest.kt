package com.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsAppLinkTest {
    @Test
    fun createsDirectChatLinkWithEncodedDraft() {
        assertEquals(
            "https://wa.me/919876543210?text=Project%20review%20%26%20notes",
            WhatsAppLink.create("+919876543210", "Project review & notes")
        )
    }

    @Test
    fun refusesToGuessMissingInternationalCountryCode() {
        assertNull(WhatsAppLink.create("9876543210", "Draft text"))
    }
}
