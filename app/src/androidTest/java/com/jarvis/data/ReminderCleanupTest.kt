package com.jarvis.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device (in-memory database): the orphan cleanup removes only reminders with no plan item. */
@RunWith(AndroidJUnit4::class)
class ReminderCleanupTest {
    @Test
    fun orphanedStandaloneRemindersAreRemovedAndMeetingRemindersStay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.reminderDao()
            dao.insert(Reminder("meeting", "Leave for Meeting at college", 1_000L, null, "scheduled", "plan-item-1"))
            dao.insert(Reminder("cancelled", "Meeting starts in 15 minutes", 2_000L, null, "cancelled", "plan-item-2"))
            dao.insert(Reminder("orphan", "remind me to stretch", 3_000L, null, "scheduled", null))

            assertEquals(1, dao.deleteOrphans())
            assertEquals(listOf("meeting", "cancelled"), dao.observeAll().first().map { it.id })
            // Running it again (every app start) changes nothing.
            assertEquals(0, dao.deleteOrphans())
            assertEquals(2, dao.observeAll().first().size)
        } finally {
            db.close()
        }
    }
}
