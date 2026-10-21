package com.jarvis.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.core.LanguageNormalizer
import com.jarvis.core.ReminderCommand
import com.jarvis.core.ReminderCommands
import com.jarvis.core.TaskKinds
import com.jarvis.core.TaskReplies
import com.jarvis.core.TaskStates
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On the phone, in-memory Room seeded with standalone and meeting reminders: English, Hinglish and Hindi listing
 * questions read what is stored, by scope, the same way JarvisViewModel does, and change nothing.
 */
@RunWith(AndroidJUnit4::class)
class ReminderListingTest {
    @Test
    fun listingReadsSeededRemindersAndChangesNothing() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val now = ZonedDateTime.now().withHour(14).withMinute(0).withSecond(0).withNano(0)
            val ms = now.toInstant().toEpochMilli()
            fun at(days: Long, hour: Int) = now.plusDays(days).with(LocalTime.of(hour, 30)).toInstant().toEpochMilli()
            fun task(id: String, title: String, time: Long, status: String = TaskStates.SCHEDULED) =
                AgentTask(id, TaskKinds.REMINDER, title, "{}", time, now.zone.id, status, 0, 3, 120_000, null, null, "not_required", null, ms, ms)
            db.agentTaskDao().insert(task("t1", "review the DevOps project", at(0, 20)))
            db.agentTaskDao().insert(task("t2", "gym", at(1, 7)))
            db.agentTaskDao().insert(task("t3", "cancelled one", at(0, 21), TaskStates.CANCELLED))
            db.reminderDao().insert(Reminder("r1", "Leave for meeting", at(1, 9), null, "scheduled", "item1"))
            db.reminderDao().insert(Reminder("r2", "Old meeting", at(0, 18), null, "cancelled", "item2"))
            val before = db.agentTaskDao().all() to db.reminderDao().observeAll().first()

            suspend fun ask(text: String): String {
                val command = ReminderCommands.parse(LanguageNormalizer.normalize(text)) as ReminderCommand.List
                return TaskReplies.list(db.agentTaskDao().all(), db.goalDao().all(), now, db.reminderDao().observeAll().first(), command.scope)
            }
            listOf("List all my reminders", "Mere saare reminders batao", "मेरे सारे रिमाइंडर दिखाओ").forEach {
                val reply = ask(it)
                assertTrue("$it → $reply", reply.startsWith("You have 3 reminders:") && reply.contains("Leave for meeting") && !reply.contains("cancelled one"))
            }
            listOf("Show today's reminders", "Mere aaj jo bhi reminder hai, batao", "आज के सारे रिमाइंडर बताओ।").forEach {
                assertEquals(it, "You have 1 reminder for today: review the DevOps project at 8:30 PM today.", ask(it))
            }
            assertTrue(ask("Kal ka reminder kab hai?").startsWith("You have 2 reminders for tomorrow:"))
            // Reading never changes a row.
            assertEquals(before, db.agentTaskDao().all() to db.reminderDao().observeAll().first())
        } finally {
            db.close()
        }
    }
}
