package com.jarvis.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1: meeting titles from the user's own words, and replies in the user's language without changing stored data. */
class LanguageAndTitleTest {
    private val now = ZonedDateTime.of(2026, 10, 21, 14, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    @Test
    fun theReplyLanguageFollowsTheUsersWords() {
        assertEquals(ReplyLang.EN, LocalReplies.languageOf("List all my reminders"))
        assertEquals(ReplyLang.HINGLISH, LocalReplies.languageOf("Mere saare reminders batao"))
        assertEquals(ReplyLang.HINGLISH, LocalReplies.languageOf("Haan"))
        assertEquals(ReplyLang.HINDI, LocalReplies.languageOf("मेरे आज के रिमाइंडर बताओ।"))
        // Technical English words in a Hindi sentence stay Hindi.
        assertEquals(ReplyLang.HINDI, LocalReplies.languageOf("मेरा DevOps project का रिमाइंडर हटाओ"))
        // A short neutral answer keeps the conversation's language; a full English sentence switches.
        assertEquals(ReplyLang.HINGLISH, LocalReplies.languageOf("gym", ReplyLang.HINGLISH))
        assertEquals(ReplyLang.EN, LocalReplies.languageOf("Remind me to call the bank tomorrow", ReplyLang.HINGLISH))
    }

    @Test
    fun storedDataIsNeverTranslated() {
        val at = now.plusDays(1).withHour(19).withMinute(0)
        val task = com.jarvis.data.AgentTask("t", TaskKinds.REMINDER, "DevOps project review", "{}", at.toInstant().toEpochMilli(), "Asia/Kolkata",
            TaskStates.SCHEDULED, 0, 3, 120_000, null, null, "not_required", null, 1, 1)
        val hg = TaskReplies.created(CreateResult.Scheduled(task, true, false), "DevOps project review", at, now, ReplyLang.HINGLISH)
        assertEquals("Theek hai, kal 7:00 PM pe aapko \"DevOps project review\" yaad dila dunga.", hg)
        val hi = TaskReplies.list(listOf(task), emptyList(), now, emptyList(), "all", ReplyLang.HINDI)
        assertEquals("आपके 1 रिमाइंडर हैं: DevOps project review — कल 7:00 PM।", hi)
        assertEquals("Aapka aaj ke liye koi reminder nahi hai.", TaskReplies.list(emptyList(), emptyList(), now, emptyList(), "today", ReplyLang.HINGLISH))
        val step = PlanStep("meeting.create", mapOf("title" to "Project review", "date" to "2026-10-22", "hour" to "10", "minute" to "0", "location" to "VJTI"))
        assertTrue(Confirmations.meetingProposal(step, ReplyLang.HINDI).contains("\"Project review\" को Thu 22 Oct, 10:00 AM, VJTI पर"))
        // English stays exactly as before.
        assertEquals("You have 1 reminder: DevOps project review at 7:00 PM tomorrow.", TaskReplies.list(listOf(task), emptyList(), now, emptyList(), "all"))
    }

    @Test
    fun reminderQuestionsAreAskedInTheUsersLanguage() {
        val ask = ReminderDialogue.start(LanguageNormalizer.normalize("Kal shaam 7 baje reminder lagao"), now) as ReminderStep.Ask
        assertEquals(ReminderSlot.TITLE, ask.slot)
        assertEquals("Aapko kis cheez ka reminder chahiye?", LocalReplies.reminderQuestion(ask, ReplyLang.HINGLISH))
        val ampm = ReminderDialogue.start("remind me at 7 to stretch", now) as ReminderStep.Ask
        assertEquals("7 बजे सुबह या शाम?", LocalReplies.reminderQuestion(ampm, ReplyLang.HINDI))
        assertEquals(ampm.question, LocalReplies.reminderQuestion(ampm, ReplyLang.EN))
    }

    @Test
    fun meetingTitlesAreTheUsersOwnWordsOrAsked() {
        fun title(text: String) = MeetingRequestParser.draft(LanguageNormalizer.normalize(text), now).title
        assertNull(title("I have a meeting tomorrow at 10 AM at college"))
        assertEquals("DevOps project evaluation meeting", title("I have a DevOps project evaluation meeting tomorrow at 10 AM at VJTI"))
        assertEquals("Meeting with Prof. Rao about the viva", title("I have a meeting with Prof. Rao about the viva tomorrow at 4 PM at college"))
        assertEquals("Project review meeting", title("Kal subah 10 baje VJTI mein project review meeting hai"))
        // A model's title is used only when the user said those words; a bare "Meeting" is not a title.
        val said = listOf("I have a project review tomorrow at 10 AM at VJTI")
        assertEquals("Project review", MeetingDetails.title("Project review", said))
        assertNull(MeetingDetails.title("Quarterly sync", said))
        assertNull(MeetingDetails.title("Meeting", said))
        assertNull(MeetingDetails.title("", said))
        assertEquals("Budget review", MeetingDetails.titleFromAnswer("it's budget review"))
        assertNull(MeetingDetails.titleFromAnswer("what time is it?"))
        assertTrue(MeetingDetails.saysNoTitle("no title"))
        // No day word: the next time that clock time comes round (shown in the proposal), never a silent "tomorrow".
        assertEquals(now.toLocalDate(), MeetingRequestParser.draft("Meeting at 6 PM at Dadar", now).date)
        assertEquals(now.toLocalDate().plusDays(1), MeetingRequestParser.draft("Meeting at 9 AM at Dadar", now).date)
    }
}
