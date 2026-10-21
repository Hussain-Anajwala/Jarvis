package com.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 0 item 3.2: exact → whole words → controlled near/phonetic suggestions, with the user always choosing. */
class ContactSpellingTest {
    private fun row(id: Long, name: String, number: String, label: String? = "Mobile") = ContactRow(id, name, number, label)

    private val yusufs = listOf(
        row(1, "Yusuf Anajwala", "+91 98000 00001"),
        row(2, "Yusuf Khan", "+91 98000 00002"),
        row(3, "Yusuf Bhai Office", "+91 98000 00003"),
        row(4, "Yusuf Sir", "+91 98000 00004"),
        row(5, "Ma ❤️", "+91 98000 00005"),
        row(6, "Mahesh", "+91 98000 00006")
    )

    @Test
    fun theFullNameResolvesAndAFirstNameSharedByFourPeopleIsAChoice() {
        assertEquals("Yusuf Anajwala", (ContactMatcher.match("Yusuf Anajwala", yusufs) as ContactResolution.Found).contact.displayName)
        val four = ContactMatcher.match("Yusuf", yusufs) as ContactResolution.Ambiguous
        assertEquals(4, four.options.size)
        assertFalse("an exact first-name match is not a guess", four.suggestions)
        assertEquals("Ma ❤️", (ContactMatcher.match("Ma", yusufs) as ContactResolution.Found).contact.displayName)
    }

    @Test
    fun transcriptionErrorsAreOfferedAsDidYouMeanNeverPickedAutomatically() {
        for (heard in listOf("Yousuf Anajwala", "Yusuf Anjwala", "Yousef Anajwala", "Yusuff Anajwalla")) {
            val result = ContactMatcher.match(heard, yusufs)
            assertTrue(heard, result is ContactResolution.Ambiguous)
            result as ContactResolution.Ambiguous
            assertTrue("$heard is a suggestion", result.suggestions)
            assertEquals(heard, listOf("Yusuf Anajwala"), result.options.map { it.displayName })
        }
        val misheard = ContactMatcher.match("Yousuf", yusufs) as ContactResolution.Ambiguous
        assertTrue(misheard.suggestions)
        assertEquals(4, misheard.options.size)
    }

    @Test
    fun unrelatedNamesStayUnmatched() {
        listOf("Yash", "Anil Anajwala", "Usha", "Ram").forEach {
            assertEquals(it, ContactResolution.NotFound, ContactMatcher.match(it, yusufs))
        }
    }

    @Test
    fun phoneticKeysCoverCommonTransliterations() {
        listOf("yousuf" to "yusuf", "mohammad" to "muhamad", "pharhan" to "farhan", "sheetal" to "shital", "zoya" to "joya")
            .forEach { (a, b) -> assertEquals("$a/$b", ContactMatcher.phonetic(a), ContactMatcher.phonetic(b)) }
        assertFalse(ContactMatcher.sounds("mahesh", "ma"))
    }

    @Test
    fun aChoiceIsPickedByOrdinalSurnameOrLastDigitsOverSeveralTurns() {
        val four = ContactMatcher.match("Yusuf", yusufs) as ContactResolution.Ambiguous
        val labels = four.options.map { "${it.displayName} ${it.normalizedPhoneNumber}" }
        assertEquals(ChoicePick.Option(1), ChoiceAnswers.pick("the second one", labels))
        val anajwala = four.options.indexOfFirst { it.displayName == "Yusuf Anajwala" }
        assertEquals(ChoicePick.Option(anajwala), ChoiceAnswers.pick("Anajwala", labels))
        assertEquals(ChoicePick.Option(four.options.indexOfFirst { it.displayName == "Yusuf Khan" }), ChoiceAnswers.pick("ending 0002", labels))
        assertEquals(ChoicePick.NotAnAnswer, ChoiceAnswers.pick("Yusuf", labels))
        assertEquals(ChoicePick.Cancel, ChoiceAnswers.pick("none of them", labels))
    }
}
