package com.jarvis.core

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** On the phone, in-memory Room: a duplicate event is one row, handled once, and a restart handles leftovers once. */
@RunWith(AndroidJUnit4::class)
class EventStoreTest {
    private class Effects : EventEffects {
        val notified = mutableListOf<String>()
        override fun notifyLocal(key: String, title: String, text: String) { notified += key }
        override suspend fun record(text: String) = Unit
    }

    @Test
    fun duplicatesAreOneRowAndLeftoversAreHandledOnceAfterRestart() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val fx = Effects()
            var handled = 0
            val handlers = mapOf(EventTypes.PLAN_FINISHED to EventHandler { e, f -> handled++; f.notifyLocal("plan:${e.subjectId}", "t", "x"); "notified" })
            val bus = EventBus(RoomEventStore(db), handlers, fx)
            bus.emit(EventTypes.PLAN_FINISHED, "e1", "NEEDS_ATTENTION")
            assertEquals(EmitResult.Duplicate, bus.emit(EventTypes.PLAN_FINISHED, "e1", "NEEDS_ATTENTION"))
            // Stored before a crash, never handled:
            db.appEventDao().insert(com.jarvis.data.AppEvent("${EventTypes.PLAN_FINISHED}:e2:FAILED", EventTypes.PLAN_FINISHED, "e2", "FAILED", 5, null, null))
            val restarted = EventBus(RoomEventStore(db), handlers, fx)
            assertEquals(1, restarted.recover())
            assertEquals(0, restarted.recover())
            assertEquals(2, handled)
            assertEquals(listOf("plan:e1", "plan:e2"), fx.notified)
            assertEquals(2, db.appEventDao().countsByType().single().count)
        } finally {
            db.close()
        }
    }
}
