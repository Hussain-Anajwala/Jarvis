package com.jarvis.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device: proves each exported schema step keeps existing user data. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate3To4KeepsPlansTracesAndMemories() {
        helper.createDatabase(dbName, 3).apply {
            execSQL("INSERT INTO plans (id, goalText, status, createdAt, updatedAt, sourceConversationRef) VALUES ('p1', 'Meeting at college', 'active', 1, 2, NULL)")
            execSQL("INSERT INTO decision_trace_entries (id, planId, actionSummary, riskLevel, toolName, outcome, createdAt, modelName) VALUES ('t1', 'p1', 'created', 'low', 'meeting.create', 'success', 3, 'groq/model')")
            execSQL("INSERT INTO memories (id, contentText, embeddingVector, sourceRef, createdAt, lastUsedAt, userDeletable) VALUES ('m1', 'fact', NULL, 'manual', 4, NULL, 1)")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 4, true, AppDatabase.MIGRATION_3_4)
        migrated.query("SELECT goalText FROM plans WHERE id = 'p1'").use {
            it.moveToFirst(); assertEquals("Meeting at college", it.getString(0))
        }
        migrated.query("SELECT modelName FROM decision_trace_entries WHERE id = 't1'").use {
            it.moveToFirst(); assertEquals("groq/model", it.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM memories").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        migrated.query("SELECT COUNT(*) FROM notification_records").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        migrated.close()
    }

    @Test
    fun roomOpensMigratedDatabaseWithAllMigrationsAndNotificationDaoWorks() = runBlocking {
        helper.createDatabase(dbName, 3).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val dao = db.notificationRecordDao()
            dao.insertAll(listOf(NotificationRecord("r1", "k1", "com.whatsapp", "WhatsApp", "Asha", "hi", 10, false)))
            dao.insertAll(listOf(NotificationRecord("r1", "k1", "com.whatsapp", "WhatsApp", "Asha", "hi", 10, false)))
            assertEquals(1, dao.unreadSince(0).size)
            dao.markReadByKey("k1")
            assertEquals(0, dao.unreadSince(0).size)
            dao.deleteOlderThan(11)
            dao.clearAll()
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}
