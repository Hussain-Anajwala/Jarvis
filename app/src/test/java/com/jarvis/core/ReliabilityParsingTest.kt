package com.jarvis.core

import com.jarvis.data.AgentTask
import com.jarvis.data.Goal
import com.jarvis.data.Reminder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reliability fix, Priority B: Hindi input, reminder wording, dates that can't be read, listing scope, stop/resume. */
class ReliabilityParsingTest {
    // Wednesday 21 October 2026, 14:00 in Mumbai.
    private val now = ZonedDateTime.of(2026, 10, 21, 14, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
    private val ms = now.toInstant().toEpochMilli()

    private fun start(text: String) = ReminderDialogue.start(LanguageNormalizer.normalize(text), now)
    private fun ready(text: String) = start(text) as ReminderStep.Ready
    private fun asks(text: String) = (start(text) as ReminderStep.Ask).slot

    @Test
    fun devanagariBecomesTheHinglishTheParsersRead() {
        assertEquals("aaj shaam 8:30 baje mujhe project yaad dilana.", LanguageNormalizer.normalize("आज शाम ८:३० बजे मुझे प्रोजेक्ट याद दिलाना।"))
        assertEquals("kal subah 9 baje", LanguageNormalizer.normalize("कल सुबह नौ बजे"))
        assertEquals("8:30 baje", LanguageNormalizer.normalize("साढ़े आठ बजे"))
        assertEquals("8:15 baje", LanguageNormalizer.normalize("सवा आठ बजे"))
        assertEquals("8:45 baje", LanguageNormalizer.normalize("पौने नौ बजे"))
        assertEquals("45 minute", LanguageNormalizer.normalize("पैंतालीस मिनट"))
        // A number word that is not before a time unit is a word: "दिला दो" is "dila do", not "dila 2".
        assertEquals("yaad dila do", LanguageNormalizer.normalize("याद दिला दो"))
        // Words it doesn't know stay as written; Roman text is untouched.
        assertEquals("doodh lana yaad dilana", LanguageNormalizer.normalize("doodh lana yaad dilana"))
        assertEquals("दूध yaad dilana", LanguageNormalizer.normalize("दूध याद दिलाना"))
    }

    @Test
    fun hindiAndHinglishRemindersGetTheRightDayAndTime() {
        ready("आज शाम 8:30 बजे मुझे प्रोजेक्ट याद दिलाना।").let {
            assertEquals("project", it.title); assertEquals(now.with(LocalTime.of(20, 30)), it.at)
        }
        ready("कल सुबह नौ बजे मुझे जिम याद दिलाना").let {
            assertEquals("gym", it.title); assertEquals(now.plusDays(1).with(LocalTime.of(9, 0)), it.at)
        }
        ready("परसों शाम साढ़े पाँच बजे दवाई याद दिलाना").let { assertEquals(now.plusDays(2).with(LocalTime.of(17, 30)), it.at) }
        // "parso" (no n) is the day after tomorrow, never today.
        ready("Parso subah 7 baje gym yaad dilana").let { assertEquals(now.plusDays(2).with(LocalTime.of(7, 0)), it.at) }
        assertEquals(ReminderSlot.TITLE, asks("Parso shaam 5 baje mujhe yaad dilana"))
    }

    @Test
    fun createSetMakeAReminderAsksOnlyForWhatIsMissing() {
        assertEquals(ReminderSlot.TITLE, asks("Create a reminder for 8:30 PM"))
        assertEquals(ReminderSlot.TITLE, asks("Set a reminder for tomorrow at 6 AM"))
        ready("Create a reminder for 8:30 PM and label it DevOps project").let {
            assertEquals("DevOps project", it.title); assertEquals(now.with(LocalTime.of(20, 30)), it.at)
        }
        ready("Make a reminder called buy milk for 7 PM").let { assertEquals("buy milk", it.title) }
        ready("Set a reminder at 9:15 PM to take my medicine").let { assertEquals("take my medicine", it.title) }
        ready("gym ka reminder laga do kal subah 6 baje").let {
            assertEquals("gym", it.title); assertEquals(now.plusDays(1).with(LocalTime.of(6, 0)), it.at)
        }
        // The answer to "What should I remind you about?" completes it.
        val ask = start("Create a reminder for 8:30 PM") as ReminderStep.Ask
        val done = ReminderDialogue.answer(ask.draft, ask.slot, "review the DevOps evaluation", now) as ReminderStep.Ready
        assertEquals("review the DevOps evaluation", done.title)
        assertEquals(now.with(LocalTime.of(20, 30)), done.at)
    }

    @Test
    fun datesThatDontExistAreAskedAboutNeverReplacedByToday() {
        assertEquals(ReminderSlot.DAY, asks("Remind me on the 31st of November to file taxes"))
        assertEquals(ReminderSlot.DAY, asks("Remind me on February 30th at 5 pm to pay rent"))
        // 2027 is not a leap year.
        assertEquals(ReminderSlot.DAY, asks("Remind me on 29 Feb at 9 am to renew"))
        val ask = start("Remind me on the 31st of November at 5 pm to file taxes") as ReminderStep.Ask
        // A non-day answer is asked again; a real day keeps the time from the request.
        assertEquals(ReminderSlot.DAY, (ReminderDialogue.answer(ask.draft, ask.slot, "whenever", now) as ReminderStep.Ask).slot)
        val done = ReminderDialogue.answer(ask.draft, ask.slot, "30 November", now) as ReminderStep.Ready
        assertEquals(ZonedDateTime.of(2026, 11, 30, 17, 0, 0, 0, now.zone), done.at)
        assertEquals("file taxes", done.title)
        // A real date is used; one that has passed this year is next year's.
        assertEquals(LocalDate.of(2026, 12, 25), ready("Remind me on 25th December at 10 am to call grandma").at.toLocalDate())
        assertEquals(LocalDate.of(2027, 3, 5), ready("Remind me on March 5th at 10 am to renew the passport").at.toLocalDate())
        // "May" as a word is not read as the month.
        assertEquals(ReminderSlot.AMPM, asks("Remind me I may need to call at 5"))
    }

    @Test
    fun clockEdgeCasesNeverGuess() {
        assertEquals(ReminderSlot.AMPM, asks("Remind me at 7 to stretch"))
        assertEquals(ReminderSlot.AMPM, asks("Kal 6 baje doodh lane ki yaad dilana"))
        assertEquals(ReminderSlot.TIME, asks("Remind me at 25:00 to sleep"))
        assertEquals(ReminderSlot.TIME, asks("Remind me at 7:75 pm to sleep"))
        // Midnight today is tonight's midnight, not a time that has already passed.
        assertEquals(now.plusDays(1).with(LocalTime.MIDNIGHT), ready("Remind me today at midnight to lock up").at)
        assertEquals(now.plusDays(1).with(LocalTime.MIDNIGHT), ready("Remind me tonight at 12 to lock up").at)
        assertEquals(now.plusDays(1).with(LocalTime.MIDNIGHT), ready("Remind me at midnight to lock up").at)
        assertEquals(now.with(LocalTime.NOON).plusDays(1), ready("Remind me at noon to eat").at)
        // A time already past today, said with "today", is asked about instead of moved or kept in the past.
        assertEquals(ReminderSlot.TIME, asks("Remind me today at 9 AM to call the bank"))
    }

    @Test
    fun listingQuestionsHaveAScopeAndNeverCreate() {
        fun scope(text: String) = (ReminderCommands.parse(LanguageNormalizer.normalize(text)) as ReminderCommand.List).scope
        assertEquals("all", scope("List all my reminders"))
        assertEquals("today", scope("Show today's reminders"))
        assertEquals("all", scope("Mere saare reminders batao"))
        assertEquals("today", scope("Mere aaj jo bhi reminder hai, batao"))
        assertEquals("today", scope("आज के सारे रिमाइंडर बताओ।"))
        assertEquals("tomorrow", scope("Kal ka reminder kab hai?"))
        assertEquals("tomorrow", scope("What reminders do I have tomorrow?"))
        assertTrue(ReminderCommands.parse("Akhri reminder hata do").let { it is ReminderCommand.Cancel && it.latest })
        assertTrue(ReminderCommands.parse(LanguageNormalizer.normalize("अख़िरी रिमाइंडर हटाओ")).let { it is ReminderCommand.Cancel && it.latest })
    }

    private fun task(id: String, objective: String, at: Long?, status: String = TaskStates.SCHEDULED) =
        AgentTask(id, TaskKinds.REMINDER, objective, "{}", at, "Asia/Kolkata", status, 0, 3, 120_000, null, null, "not_required", null, ms - 1000, ms - 1000)

    @Test
    fun listingReadsBothStoresByScope() {
        val tonight = now.with(LocalTime.of(20, 30)).toInstant().toEpochMilli()
        val tomorrow = now.plusDays(1).with(LocalTime.of(9, 0)).toInstant().toEpochMilli()
        val tasks = listOf(task("a", "review the DevOps project", tonight), task("b", "gym", tomorrow), task("c", "old", tonight, TaskStates.CANCELLED))
        val meetings = listOf(Reminder("m1", "Leave for meeting", tomorrow + 3_600_000, null, "scheduled", "p1"),
            Reminder("m2", "Cancelled meeting", tonight, null, "cancelled", "p2"))
        val goals = listOf(Goal("g", "Prepare for exam", "active", null, null, ms, ms))
        val all = TaskReplies.list(tasks, goals, now, meetings, "all")
        assertTrue(all, all.startsWith("You have 3 reminders: review the DevOps project at 8:30 PM today; gym at 9:00 AM tomorrow; Leave for meeting at 10:00 AM tomorrow (meeting plan)."))
        assertTrue(all.contains("Plans: Prepare for exam (active)"))
        assertEquals("You have 1 reminder for today: review the DevOps project at 8:30 PM today.", TaskReplies.list(tasks, goals, now, meetings, "today"))
        assertTrue(TaskReplies.list(tasks, goals, now, meetings, "tomorrow").startsWith("You have 2 reminders for tomorrow:"))
        assertEquals("You have no reminders for today.", TaskReplies.list(emptyList(), goals, now, meetings, "today"))
        assertEquals("You have no pending reminders.", TaskReplies.list(emptyList(), emptyList(), now, emptyList(), "all"))
    }

    @Test
    fun aBareAnswerIsOnlyAnswerWords() {
        listOf("yes", "Haan", "haan ji", "yes please", "no thanks", "ok", "नहीं").forEach {
            assertTrue(it, ApprovalAnswer.isBare(LanguageNormalizer.normalize(it)))
        }
        listOf("Cancel the timer", "stop the music", "no, make it 8 PM", "ok set a timer").forEach { assertFalse(it, ApprovalAnswer.isBare(it)) }
    }

    @Test
    fun timerPhrasingsAreLocal() {
        fun seconds(text: String) = ((PhoneCommandParser.parse(text) as PhoneCommandParseResult.Parsed).step.parameters["duration_seconds"])
        assertEquals("2700", seconds("Set a 45 minute timer"))
        assertEquals("7200", seconds("start a 2-hour timer"))
        assertEquals("300", seconds("set a timer for 5 minutes"))
        assertEquals("1200", seconds("20 minute ka timer lagao"))
    }

    private fun timer(state: JarvisTimer.State) = JarvisTimer(1, "", 600, ms, state)

    @Test
    fun stopAndResumeActOnlyOnWhatIsRunning() {
        assertEquals(ContextualControl.Kind.STOP, ContextualControl.parse("Rok do"))
        assertEquals(ContextualControl.Kind.STOP, ContextualControl.parse(LanguageNormalizer.normalize("रोको")))
        assertEquals(ContextualControl.Kind.RESUME, ContextualControl.parse("Dobara shuru karo"))
        assertEquals(ContextualControl.Kind.RESUME, ContextualControl.parse("resume it"))
        assertNull(ContextualControl.parse("rok do mera alarm kal ka"))
        val stop = ContextualControl.Kind.STOP
        val resume = ContextualControl.Kind.RESUME
        assertEquals(ContextualControl.Target.DismissRinging, ContextualControl.resolve(stop, true, listOf(timer(JarvisTimer.State.RINGING)), 1, 0))
        assertEquals(ContextualControl.Target.StopSpeech, ContextualControl.resolve(stop, true, listOf(timer(JarvisTimer.State.RUNNING)), 0, 0))
        assertEquals(ContextualControl.Target.CancelTimer, ContextualControl.resolve(stop, false, listOf(timer(JarvisTimer.State.RUNNING)), 0, 0))
        assertEquals(ContextualControl.Target.PauseGoal, ContextualControl.resolve(stop, false, emptyList(), 1, 0))
        assertTrue(ContextualControl.resolve(stop, false, listOf(timer(JarvisTimer.State.RUNNING)), 1, 0) is ContextualControl.Target.Say)
        assertTrue(ContextualControl.resolve(stop, false, emptyList(), 0, 2) is ContextualControl.Target.Say)
        assertEquals(ContextualControl.Target.ResumeGoal, ContextualControl.resolve(resume, false, emptyList(), 0, 1))
        // Resume with nothing paused starts nothing (no timer by default).
        assertTrue(ContextualControl.resolve(resume, false, listOf(timer(JarvisTimer.State.RUNNING)), 1, 0) is ContextualControl.Target.Say)
    }
}

/** Reliability fix, Priority C: one timer per request, a truthful countdown, and replies that say what happened. */
class TimerReliabilityTest {
    private val t0 = 1_800_000_000_000L

    @Test
    fun theSameTimerMomentsLaterIsTheOneAlreadyRunning() {
        val (list, first) = TimerBook.start(emptyList(), "", 2700, t0)
        assertEquals(first, TimerBook.duplicateOf(list, "", 2700, t0 + 3_000))
        assertNull(TimerBook.duplicateOf(list, "", 2700, t0 + TimerBook.DUPLICATE_WINDOW_MS))
        assertNull(TimerBook.duplicateOf(list, "", 600, t0 + 3_000))
        assertNull(TimerBook.duplicateOf(list, "tea", 2700, t0 + 3_000))
        val cancelled = TimerBook.transition(list, first.id, JarvisTimer.State.CANCELLED, t0 + 1_000)
        assertNull(TimerBook.duplicateOf(cancelled, "", 2700, t0 + 3_000))
    }

    @Test
    fun countdownComesFromTheStoredEndTime() {
        val timer = JarvisTimer(1, "", 2700, t0, JarvisTimer.State.RUNNING)
        assertEquals("45:00", TimerBook.countdown(timer, t0))
        assertEquals("44:59", TimerBook.countdown(timer, t0 + 1_000))
        assertEquals("1:00:00", TimerBook.countdown(JarvisTimer(2, "", 3600, t0, JarvisTimer.State.RUNNING), t0))
        assertEquals("Ending…", TimerBook.countdown(timer, t0 + 2_700_000))
        assertEquals("Ringing", TimerBook.countdown(timer.copy(state = JarvisTimer.State.RINGING), t0 + 2_700_500))
        assertTrue(TimerBook.showCard(timer, t0))
        assertFalse(TimerBook.showCard(timer.copy(state = JarvisTimer.State.CANCELLED), t0))
        assertFalse(TimerBook.showCard(timer.copy(state = JarvisTimer.State.RINGING), t0 + 2_700_000 + TimerBook.RING_LIMIT_MS + 1))
    }

    @Test
    fun repliesSayWhatHappened() {
        val zone = ZoneId.of("Asia/Kolkata")
        val start = ZonedDateTime.of(2026, 10, 21, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
        val timer = JarvisTimer(1, "", 2700, start, JarvisTimer.State.RUNNING)
        assertEquals("Started your 45-minute timer. It ends at 2:45 PM. Say \"cancel the timer\" or tap Cancel to stop it.",
            TimerReplies.started(JarvisTimerEngine.Started(timer, duplicate = false, notificationsShown = true), exact = true, zone = zone))
        val degraded = TimerReplies.started(JarvisTimerEngine.Started(timer, duplicate = false, notificationsShown = false), exact = false, zone = zone)
        assertTrue(degraded, degraded.contains("may ring a little late") && degraded.contains("notifications for JARVIS are off"))
        assertTrue(TimerReplies.started(JarvisTimerEngine.Started(timer, duplicate = true, notificationsShown = true), true, zone).contains("already running"))
        assertEquals("Your 45-minute timer has been cancelled. It won't ring.", TimerReplies.stopped(timer, wasRinging = false))
        assertEquals("Stopped your 45-minute timer.", TimerReplies.stopped(timer, wasRinging = true))
    }
}
