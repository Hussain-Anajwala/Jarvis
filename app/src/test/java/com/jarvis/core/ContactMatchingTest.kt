package com.jarvis.core

import android.Manifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatchingTest {
    private fun row(id: Long, name: String, number: String, label: String? = "Mobile") = ContactRow(id, name, number, label)

    @Test
    fun namesAreNormalisedForEmojiPunctuationSpacingCaseAndAccents() {
        assertEquals("ma", ContactNames.normalize("Ma ❤️"))
        assertEquals("ma", ContactNames.normalize("❤️Ma❤️"))
        assertEquals("dr asha rao", ContactNames.normalize("Dr.  Asha   Rao"))
        assertEquals("d souza", ContactNames.normalize("D'Souza"))
        assertEquals("jose", ContactNames.normalize("José"))
        assertEquals("rahul", ContactNames.normalize("  RAHUL 🙂👍🏽 "))
        assertEquals("papa", ContactNames.normalize("Papa 👨‍👧")) // ZWJ emoji sequence
        // Non-Latin names keep their vowel signs, so different names stay different.
        assertEquals("माँ", ContactNames.normalize("माँ ❤️"))
        assertTrue(ContactNames.normalize("मा") != ContactNames.normalize("म"))
        assertEquals("Ma", ContactNames.displayName("Ma ❤️"))
    }

    @Test
    fun anEmojiNameResolvesToThatPersonAndNotToSimilarNames() {
        val rows = listOf(row(1, "Ma ❤️", "+91 98765 43210"), row(2, "Mahesh", "+91 90000 00001"), row(3, "Amar", "99999 11111"))
        val result = ContactMatcher.match("Ma", rows)
        assertEquals(ContactResolution.Found(ResolvedContact("Ma ❤️", "+919876543210", "Mobile")), result)
    }

    @Test
    fun punctuationSpacesAndCaseDoNotMatter() {
        val rows = listOf(row(1, "Dr. Asha  Rao", "+14155550123"), row(2, "Ravi-Kumar", "555 0100"))
        assertTrue(ContactMatcher.match("dr asha rao", rows) is ContactResolution.Found)
        assertTrue(ContactMatcher.match("ASHA RAO", rows) is ContactResolution.Found)
        assertEquals("Ravi-Kumar", (ContactMatcher.match("ravi kumar", rows) as ContactResolution.Found).contact.displayName)
        assertEquals("Ravi-Kumar", (ContactMatcher.match("ravikumar", rows) as ContactResolution.Found).contact.displayName)
    }

    @Test
    fun anExactNameWinsOverPeopleWhoMerelyShareAWord() {
        val rows = listOf(row(1, "Rahul", "111 111 1111"), row(2, "Rahul Sharma", "222 222 2222"), row(3, "Rahul Verma", "333 333 3333"))
        assertEquals("Rahul", (ContactMatcher.match("Rahul", rows) as ContactResolution.Found).contact.displayName)
        val shared = ContactMatcher.match("Sharma", rows)
        assertEquals("Rahul Sharma", (shared as ContactResolution.Found).contact.displayName)
    }

    @Test
    fun severalPeopleWithTheSameFirstNameBecomeAChoiceWithNumbers() {
        val rows = listOf(row(1, "Asha Rao", "+14155550123"), row(2, "Asha Mehta", "+14155550199", "Work"), row(3, "Ravi", "555 0100"))
        val result = ContactMatcher.match("Asha", rows) as ContactResolution.Ambiguous
        assertEquals(listOf("Asha Mehta", "Asha Rao"), result.options.map { it.displayName })
        assertEquals(listOf("+14155550199", "+14155550123"), result.options.map { it.normalizedPhoneNumber })
        assertTrue(ContactAccess.question("call", "Asha", result.options).contains("1: Asha Mehta · Work · +14155550199"))
    }

    @Test
    fun onePersonWithSeveralNumbersIsAChoiceButDuplicatesOfOneNumberAreNot() {
        val twoNumbers = listOf(row(1, "Ma ❤️", "+91 98765 43210", "Mobile"), row(1, "Ma ❤️", "022 2400 0000", "Home"))
        val choice = ContactMatcher.match("Ma", twoNumbers) as ContactResolution.Ambiguous
        assertEquals(listOf("Home", "Mobile"), choice.options.map { it.numberLabel })
        assertTrue(ContactAccess.question("call", "Ma", choice.options).startsWith("Ma ❤️ has 2 numbers"))
        // The same person saved twice (phone + Google account) with the same number is one option.
        val duplicated = listOf(row(1, "Ma ❤️", "+91 98765 43210"), row(7, "Ma ❤️", "098765 43210"))
        assertTrue(ContactMatcher.match("Ma", duplicated) is ContactResolution.Found)
    }

    @Test
    fun missingContactsAndContactsWithoutNumbersAreNotFound() {
        val rows = listOf(row(1, "Asha Rao", ""), row(2, "Ravi", "555 0100"))
        assertEquals(ContactResolution.NotFound, ContactMatcher.match("Asha", rows))
        assertEquals(ContactResolution.NotFound, ContactMatcher.match("Zed", rows))
        assertEquals(ContactResolution.NotFound, ContactMatcher.match("❤️", rows))
    }

    @Test
    fun aNearSpellingIsOnlyEverOfferedAsAChoice() {
        val rows = listOf(row(1, "Priyanka", "555 0101"))
        val result = ContactMatcher.match("Priyanca", rows)
        assertTrue("one letter off is a suggestion, never an automatic pick", result is ContactResolution.Ambiguous)
        assertEquals(ContactResolution.NotFound, ContactMatcher.match("Pri", rows)) // too short to guess
    }

    @Test
    fun maIsOfferedMaaAsADidYouMeanChoiceNeverAnAutomaticPick() {
        val rows = listOf(row(1, "Maa ❤️", "+91 98765 43210"), row(2, "Mahesh", "+91 90000 00001"), row(3, "Ma", "111 2222 333").copy(contactId = 3, number = ""))
        val result = ContactMatcher.match("Ma", rows) as ContactResolution.Ambiguous
        assertTrue(result.suggestions)
        assertEquals(listOf("Maa ❤️"), result.options.map { it.displayName })
        assertTrue(ContactAccess.question("call", "Ma", result.options, result.suggestions).startsWith("I couldn't find \"Ma\" exactly. Did you mean"))
        // An exact name is never a suggestion, and "Ma" never suggests "Mahesh".
        assertTrue(ContactMatcher.match("Maa", rows) is ContactResolution.Found)
        assertEquals("ma", ContactMatcher.collapseRepeats("maa"))
        assertEquals("aditya", ContactMatcher.collapseRepeats("adittya"))
    }

    @Test
    fun spokenAndTypedAnswersPickFromTheList() {
        val labels = listOf("Asha Mehta · Work · +14155550199", "Asha Rao · Mobile · +14155550123")
        assertEquals(ChoicePick.Option(1), ChoiceAnswers.pick("the second one", labels))
        assertEquals(ChoicePick.Option(1), ChoiceAnswers.pick("2", labels))
        assertEquals(ChoicePick.Option(0), ChoiceAnswers.pick("first", labels))
        assertEquals(ChoicePick.Option(0), ChoiceAnswers.pick("number one", labels))
        assertEquals(ChoicePick.Option(1), ChoiceAnswers.pick("last", labels))
        assertEquals(ChoicePick.Option(1), ChoiceAnswers.pick("Asha Rao", labels))
        assertEquals(ChoicePick.Option(0), ChoiceAnswers.pick("the work number", labels))
        assertEquals(ChoicePick.Option(1), ChoiceAnswers.pick("ending 0123", labels))
        assertEquals(ChoicePick.Cancel, ChoiceAnswers.pick("never mind", labels))
        assertEquals(ChoicePick.NotAnAnswer, ChoiceAnswers.pick("Asha", labels)) // matches both
        assertEquals(ChoicePick.NotAnAnswer, ChoiceAnswers.pick("what is the weather like in Mumbai today", labels))
    }

    private class FakeHost(private val resolution: ContactResolution) : PhoneActionHost {
        val calls = mutableListOf<ResolvedContact>()
        override fun isGranted(permission: String) = true
        override suspend fun resolveContact(name: String) = resolution
        override suspend fun execute(step: PlanStep): PhoneToolExecution = PhoneToolExecution.Failure("unused")
        override suspend fun callConfirmedContact(contact: ResolvedContact): PhoneToolExecution {
            calls += contact
            return PhoneToolExecution.Success("Calling ${contact.displayName}.")
        }
        override suspend fun recordOutcome(toolName: String, outcome: String) = Unit
    }

    @Test
    fun callMaShowsTheCandidatesAndPickingTheSecondCallsThatPersonAfterConfirmation(): Unit = runBlocking {
        val first = ResolvedContact("Ma ❤️", "+919876543210", "Mobile")
        val second = ResolvedContact("Ma (Pune)", "+912024000000", "Home")
        val host = FakeHost(ContactResolution.Ambiguous(listOf(first, second)))
        val coordinator = PhoneActionCoordinator(host)
        coordinator.handle(PlanStep("call_contact", mapOf("name" to "Ma")))
        assertEquals(listOf(first, second), coordinator.callChoices)
        assertNull("nothing to confirm until a person is picked", coordinator.callConfirmationContact)
        assertTrue(coordinator.reply!!.contains("Which one should I call?"))
        coordinator.chooseCallContact(1)
        assertNull(coordinator.callChoices)
        assertEquals("the original call request continues with the pick", second, coordinator.callConfirmationContact)
        assertTrue("no call before confirmation", host.calls.isEmpty())
        coordinator.confirmCall()
        assertEquals(listOf(second), host.calls)
    }

    @Test
    fun cancellingTheListPlacesNoCall(): Unit = runBlocking {
        val host = FakeHost(ContactResolution.Ambiguous(listOf(ResolvedContact("A", "1"), ResolvedContact("A", "2"))))
        val coordinator = PhoneActionCoordinator(host)
        coordinator.handle(PlanStep("call_contact", mapOf("name" to "A")))
        coordinator.dismissCallChoice()
        assertNull(coordinator.callChoices)
        assertEquals("Call cancelled. No call was placed.", coordinator.reply)
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun deniedContactsExplainsHowToGrantAccess(): Unit = runBlocking {
        val host = object : PhoneActionHost by FakeHost(ContactResolution.NotFound) {
            override fun isGranted(permission: String) = permission != Manifest.permission.READ_CONTACTS
        }
        val coordinator = PhoneActionCoordinator(host)
        coordinator.handle(PlanStep("call_contact", mapOf("name" to "Ma")))
        assertEquals(Manifest.permission.READ_CONTACTS, coordinator.permissionRequest)
        coordinator.onPermissionResult(Manifest.permission.READ_CONTACTS, granted = false)
        assertTrue(coordinator.reply!!.contains("Settings → Connections and permissions"))
    }
}
