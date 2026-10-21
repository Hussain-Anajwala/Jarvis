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
    fun migrate4To5KeepsMemoriesAsEditableFacts() {
        helper.createDatabase(dbName, 4).apply {
            execSQL("INSERT INTO memories (id, contentText, embeddingVector, sourceRef, createdAt, lastUsedAt, userDeletable) VALUES ('m1', 'Prof. Rao is my guide', NULL, 'manual', 42, NULL, 1)")
            execSQL("INSERT INTO notification_records (id, notificationKey, appPackage, appLabel, sender, textSnippet, postedAt, read) VALUES ('n1', 'k', 'p', 'App', 'Asha', 'hi', 7, 0)")
            close()
        }
        val migrated = helper.runMigrationsAndValidate(dbName, 5, true, AppDatabase.MIGRATION_4_5)
        migrated.query("SELECT contentText, type, memoryKey, updatedAt FROM memories WHERE id = 'm1'").use {
            it.moveToFirst()
            assertEquals("Prof. Rao is my guide", it.getString(0))
            assertEquals("fact", it.getString(1))
            assertEquals("", it.getString(2))
            assertEquals(42L, it.getLong(3))
        }
        migrated.query("SELECT COUNT(*) FROM notification_records").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
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

    @Test
    fun migrate5To6AddsTaskTablesAndKeepsPlansAndReminders() {
        helper.createDatabase(dbName, 5).apply {
            execSQL("INSERT INTO plans (id, goalText, status, createdAt, updatedAt, sourceConversationRef) VALUES ('p1', 'Meeting at college', 'active', 1, 2, NULL)")
            execSQL("INSERT INTO reminders (id, title, triggerTime, triggerLocationRef, status, planItemId) VALUES ('r1', 'Leave for Meeting at college', 100, NULL, 'scheduled', 'pi1')")
            execSQL("INSERT INTO reminders (id, title, triggerTime, triggerLocationRef, status, planItemId) VALUES ('r2', 'Meeting starts in 15 minutes', 200, NULL, 'cancelled', 'pi2')")
            execSQL("INSERT INTO memories (id, contentText, embeddingVector, sourceRef, createdAt, lastUsedAt, userDeletable, type, memoryKey, updatedAt) VALUES ('m1', 'My college is VIT', NULL, 'manual', 4, NULL, 1, 'fact', '', 4)")
            close()
        }
        val migrated = helper.runMigrationsAndValidate(dbName, 6, true, AppDatabase.MIGRATION_5_6)
        migrated.query("SELECT COUNT(*) FROM plans").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        migrated.query("SELECT id FROM reminders ORDER BY id").use {
            val ids = mutableListOf<String>(); while (it.moveToNext()) ids += it.getString(0)
            assertEquals(listOf("r1", "r2"), ids)
        }
        // No reminder without a plan item appears (the removed orphan stays gone).
        migrated.query("SELECT COUNT(*) FROM reminders WHERE planItemId IS NULL").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        migrated.query("SELECT COUNT(*) FROM memories").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        migrated.query("SELECT COUNT(*) FROM agent_tasks").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        migrated.query("SELECT COUNT(*) FROM goals").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        migrated.close()
    }

    @Test
    fun aFreshDatabaseAndTheFullUpgradePathBothRunTheTaskEngineQueries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        helper.createDatabase(dbName, 3).close()
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
        val fresh = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            for (db in listOf(upgraded, fresh)) {
                val dao = db.agentTaskDao()
                dao.insert(AgentTask("t1", "reminder", "stretch", "{}", 1_000L, "Asia/Kolkata", "scheduled", 0, 3, 120_000, null, null, "not_required", null, 1, 1))
                // The conditional update lets exactly one delivery through.
                assertEquals(1, dao.transition("t1", listOf("scheduled"), "running", 2, null, 0))
                assertEquals(0, dao.transition("t1", listOf("scheduled"), "running", 3, null, 0))
                assertEquals(1, dao.transition("t1", listOf("running"), "completed", 4, "delivered", 0))
                assertEquals("delivered", dao.get("t1")!!.result)
                db.goalDao().insert(Goal("g1", "Prepare for exam", "active", null, null, 1, 1))
                assertEquals(1, db.goalDao().all().size)
            }
        } finally {
            upgraded.close(); fresh.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun migrate6To7AddsPlanExecutionTablesAndKeepsTasksAndGoals() {
        helper.createDatabase(dbName, 6).apply {
            execSQL("INSERT INTO plans (id, goalText, status, createdAt, updatedAt, sourceConversationRef) VALUES ('p1', 'Meeting at college', 'active', 1, 2, NULL)")
            execSQL("INSERT INTO agent_tasks (id, kind, objective, inputsJson, scheduledAt, timeZone, status, attempts, maxAttempts, timeoutMs, result, originRequest, approval, goalId, createdAt, updatedAt) VALUES ('t1', 'reminder', 'stretch', '{}', 1000, 'Asia/Kolkata', 'scheduled', 0, 3, 120000, NULL, NULL, 'not_required', NULL, 1, 1)")
            execSQL("INSERT INTO goals (id, title, status, dueAt, originRequest, createdAt, updatedAt) VALUES ('g1', 'Prepare for exam', 'active', NULL, NULL, 1, 1)")
            close()
        }
        val migrated = helper.runMigrationsAndValidate(dbName, 7, true, AppDatabase.MIGRATION_6_7)
        migrated.query("SELECT status FROM agent_tasks WHERE id = 't1'").use { it.moveToFirst(); assertEquals("scheduled", it.getString(0)) }
        migrated.query("SELECT COUNT(*) FROM goals").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        migrated.query("SELECT COUNT(*) FROM plans").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        migrated.query("SELECT COUNT(*) FROM plan_executions").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        migrated.close()
    }

    @Test
    fun migrate7To8AddsTheEventLogAndKeepsPlanRuns() {
        helper.createDatabase(dbName, 7).apply {
            execSQL("INSERT INTO plan_executions (id, objective, status, stepsHash, approvalHash, approvedAt, summary, failureReason, createdAt, updatedAt) VALUES ('e1', 'two reminders', 'SUCCEEDED', 'h', 'h', 1, '2 verified', NULL, 1, 2)")
            execSQL("INSERT INTO goals (id, title, status, dueAt, originRequest, createdAt, updatedAt) VALUES ('g1', 'Prepare for exam', 'active', NULL, NULL, 1, 1)")
            close()
        }
        val migrated = helper.runMigrationsAndValidate(dbName, 8, true, AppDatabase.MIGRATION_7_8)
        migrated.query("SELECT status FROM plan_executions WHERE id = 'e1'").use { it.moveToFirst(); assertEquals("SUCCEEDED", it.getString(0)) }
        migrated.query("SELECT COUNT(*) FROM goals").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        migrated.query("SELECT COUNT(*) FROM app_events").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        migrated.close()
    }
}
