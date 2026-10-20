package com.jarvis.core

import com.jarvis.data.Memory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPolicyTest {
    private var n = 0
    private fun memory(text: String, type: String = "fact", key: String = "") =
        Memory("m${n++}", text, null, "test", 1L, null, true, type, key, n.toLong())

    private val saved = listOf(
        memory("Kesariya", "favorite", "favorite_song"),
        memory("Dr Rao is my guide", "relationship", "Dr Rao"),
        memory("Asha's birthday is on 12 March"),
        memory("My college is VIT Mumbai"),
        memory("I prefer tea over coffee", "preference")
    )

    @Test
    fun anAmbiguousPersonalQuestionAsksInsteadOfListingEverything() {
        for (ask in listOf("tell me about me", "what do you know about me", "what do you remember about me")) {
            assertEquals(ask, MemoryQuery.Clarify, MemoryQueryPolicy.decide(ask, "me"))
        }
        assertEquals(MemoryQuery.Clarify, MemoryQueryPolicy.decide("what do you remember", null))
        val reply = MemoryQueryPolicy.clarification(saved)
        assertTrue(reply.startsWith("I have 5 saved items"))
        // The clarification names kinds of things, never their content.
        listOf("Kesariya", "Rao", "Asha", "VIT", "tea").forEach { assertFalse(it, reply.contains(it)) }
    }

    @Test
    fun anExplicitReviewListsEverythingAndATopicListsOnlyItsMatches() {
        listOf("show all my memories", "list everything you remember", "show me everything you've saved", "open the memory tab").forEach {
            assertEquals(it, MemoryQuery.ReviewAll, MemoryQueryPolicy.decide(it, null))
        }
        assertEquals(MemoryQuery.Topic("Asha"), MemoryQueryPolicy.decide("what do you remember about Asha", "Asha"))
        val shown = MemoryRetriever.relevant("Asha", saved, k = 10)
        assertEquals(listOf("Asha's birthday is on 12 March"), shown.map { it.contentText })
    }

    @Test
    fun paraphrasesFindTheRightMemoryAndUnrelatedOnesStayOut() {
        assertEquals(listOf("Dr Rao is my guide"), MemoryRetriever.relevant("who is my mentor", saved).map { it.contentText })
        assertEquals("My college is VIT Mumbai", MemoryRetriever.relevant("which university do I go to", saved).first().contentText)
        assertEquals("Kesariya", MemoryRetriever.relevant("play my favourite track", saved).first().contentText)
        assertTrue("nothing unrelated is sent with a weather question", MemoryRetriever.relevant("what's the weather tomorrow", saved).isEmpty())
    }

    @Test
    fun credentialsAndIdNumbersAreNeverStoredOrRetrieved() {
        listOf(
            "my bank PIN is 4821", "my Gmail password is hunter2", "the OTP is 552901", "my card number is 4111 1111 1111 1111",
            "my Aadhaar is 2345 6789 0123", "PAN ABCDE1234F", "api key sk_live_1234567890abcdefXYZ"
        ).forEach { assertTrue(it, SensitiveData.isSecret(it)) }
        listOf("my mentor is Dr Rao", "call me at 5 pm", "I was born in 2003", "flight AI 2025 at 10:30", "my favourite song is Kesariya")
            .forEach { assertFalse("false positive: $it", SensitiveData.isSecret(it)) }
        val withSecret = saved + memory("my wifi password is coffee123")
        assertTrue(MemoryRetriever.relevant("what is my wifi password", withSecret).none { "password" in it.contentText })
        assertFalse(SensitiveData.luhn("4111111111111112"))
    }
}
