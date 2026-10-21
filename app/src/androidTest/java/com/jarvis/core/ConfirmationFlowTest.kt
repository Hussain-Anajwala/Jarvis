package com.jarvis.core

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.data.AppDatabase
import com.jarvis.data.Event
import com.jarvis.data.Memory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On the phone, in-memory Room, offline parser, no calendar provider and a fake travel agent: a meeting "at college"
 * asks which college; a meeting and a memory deletion are written only after "yes"; "no" and a changed memory leave
 * everything as it was. Nothing touches the user's real database, calendar or memories.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmationFlowTest {
    private class CountingTravel : TravelContextAgent {
        var calls = 0
        override suspend fun estimate(event: Event, fallbackMinutes: Int): TravelTimeEstimate { calls++; return TravelTimeEstimate(0, "unknown", "test") }
    }

    @Test
    fun meetingsAndMemoryDeletionsWaitForAYes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val travel = CountingTravel()
            val memory = MemoryRepository(db.memoryDao())
            val core = JarvisCore(db, OfflineRuleProvider(), calendarProvider = null, travel = travel, memory = memory)
            suspend fun events() = db.eventDao().observeAll().first().size

            val asked = core.handle("I have a meeting tomorrow at 10 AM at college")
            assertTrue(asked, asked.startsWith("Which college?"))
            val proposal = core.handle("VJTI, Matunga")
            assertTrue(proposal, proposal.startsWith("Add \"") && proposal.contains("at VJTI, Matunga") && proposal.endsWith("Say yes to add it, or no."))
            assertEquals(0, events())
            assertEquals(0, travel.calls)
            val added = core.handle("yes")
            assertTrue(added, added.contains("created for"))
            assertEquals(1, events())
            assertEquals(1, travel.calls)
            // The meeting reminder was kept although travel time is unknown, and it is not in the past.
            val reminder = db.reminderDao().observeAll().first().single()
            assertTrue(reminder.triggerTime!! > System.currentTimeMillis())
            // A second "yes" has nothing to approve.
            assertTrue(!core.awaitingConfirmation)

            core.handle("I have a meeting tomorrow at 4 PM at Dadar station")
            assertTrue(core.awaitingConfirmation)
            assertEquals("Okay, I didn't add the meeting.", core.handle("no"))
            assertEquals(1, events())

            val gym = Memory("m1", "my gym is in Dadar", null, "conversation", 1, null, true, "fact", "", 1)
            db.memoryDao().upsert(gym)
            val ask = core.handle("Forget that my gym is in Dadar")
            assertTrue(ask, ask.startsWith("Delete this memory: \"my gym is in Dadar\"?"))
            assertEquals(1, db.memoryDao().all().size)
            // Changed after it was shown: the yes is stale.
            db.memoryDao().upsert(gym.copy(contentText = "my gym is in Bandra", updatedAt = 2))
            assertEquals("That memory changed or was already removed, so I didn't delete anything.", core.handle("yes"))
            assertEquals(1, db.memoryDao().all().size)
            core.handle("Forget that my gym is in Bandra")
            assertTrue(core.handle("haan").startsWith("Forgot:"))
            assertEquals(0, db.memoryDao().all().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun aMisheardAnswerKeepsTheProposalAndAnExpiredYesAsksAgain() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            var now = System.currentTimeMillis()
            val memory = MemoryRepository(db.memoryDao())
            val core = JarvisCore(db, OfflineRuleProvider(), calendarProvider = null, travel = CountingTravel(), memory = memory, clock = { now })
            val gym = Memory("m1", "my gym is in Dadar", null, "conversation", 1, null, true, "fact", "", 1)
            db.memoryDao().upsert(gym)
            assertTrue(core.handle("Forget that my gym is in Dadar").startsWith("Delete this memory"))
            // Misheard twice: the same memory is asked about again, nothing deleted, not asked "which memory?".
            repeat(2) {
                now += 30_000
                val retry = core.handle("hmm")
                assertTrue(retry, retry.startsWith("I didn't catch a yes or no. Should I delete \"my gym is in Dadar\"?"))
                assertEquals(1, db.memoryDao().all().size)
            }
            // A speech recogniser's spelling of "haan" approves exactly that memory.
            assertTrue(core.handle("Han").startsWith("Forgot:"))
            assertEquals(0, db.memoryDao().all().size)

            db.memoryDao().upsert(gym)
            core.handle("Forget that my gym is in Dadar")
            now += Confirmations.TTL_MS + 1
            val expired = core.handle("yes")
            assertTrue(expired, expired.startsWith("That confirmation expired, so nothing was deleted. Delete this memory"))
            assertEquals(1, db.memoryDao().all().size)
            assertTrue(core.handle("yes").startsWith("Forgot:"))

            // A correction changes the meeting and asks again; only the yes writes it.
            core.handle("I have a meeting tomorrow at 10 AM at Dadar station")
            val changed = core.handle("no, make it 5 PM")
            assertTrue(changed, changed.startsWith("Changed. Add") && changed.contains("5:00 PM"))
            assertEquals(0, db.eventDao().observeAll().first().size)
            assertTrue(core.handle("yes").contains("created for"))
            val event = db.eventDao().observeAll().first().single()
            assertEquals(17, java.time.Instant.ofEpochMilli(event.startTime).atZone(java.time.ZoneId.systemDefault()).hour)
            // Nothing pending now: a bare yes does nothing.
            assertTrue(!core.awaitingConfirmation)
        } finally {
            db.close()
        }
    }
}
