package com.jarvis.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// TODO: Replace plain Room with SQLCipher and a Keystore-backed key before production.
@Database(
    entities = [
        UserProfile::class, Plan::class, PlanItem::class, Event::class,
        Reminder::class, Task::class, Memory::class, PermissionGrant::class,
        DecisionTraceEntry::class, PlanItemDependency::class, AutomationRule::class,
        MonitoringRule::class, NotificationRecord::class, AgentTask::class, Goal::class,
        PlanExecution::class, PlanStepRecord::class, AppEvent::class
    ],
    version = 8,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun planDao(): PlanDao
    abstract fun planItemDao(): PlanItemDao
    abstract fun monitoringRuleDao(): MonitoringRuleDao
    abstract fun eventDao(): EventDao
    abstract fun reminderDao(): ReminderDao
    abstract fun taskDao(): TaskDao
    abstract fun traceDao(): TraceDao
    abstract fun permissionDao(): PermissionDao
    abstract fun automationRuleDao(): AutomationRuleDao
    abstract fun notificationRecordDao(): NotificationRecordDao
    abstract fun memoryDao(): MemoryDao
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun goalDao(): GoalDao
    abstract fun planExecutionDao(): PlanExecutionDao
    abstract fun appEventDao(): AppEventDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS plan_item_dependencies (planItemId TEXT NOT NULL, dependsOnItemId TEXT NOT NULL, PRIMARY KEY(planItemId, dependsOnItemId))")
                database.execSQL("CREATE TABLE IF NOT EXISTS automation_rules (id TEXT NOT NULL PRIMARY KEY, patternDescription TEXT NOT NULL, status TEXT NOT NULL, createdFromPlanIds TEXT NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS monitoring_rules (id TEXT NOT NULL PRIMARY KEY, planItemId TEXT NOT NULL, conditionType TEXT NOT NULL, lastCheckedAt INTEGER, nextCheckAt INTEGER, lastKnownValue TEXT)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE decision_trace_entries ADD COLUMN modelName TEXT NOT NULL DEFAULT 'system'"
                )
            }
        }

        /** M3: adds the retention-limited notification store; existing tables are untouched. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notification_records` (`id` TEXT NOT NULL, `notificationKey` TEXT NOT NULL, " +
                        "`appPackage` TEXT NOT NULL, `appLabel` TEXT NOT NULL, `sender` TEXT NOT NULL, `textSnippet` TEXT NOT NULL, " +
                        "`postedAt` INTEGER NOT NULL, `read` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_records_postedAt` ON `notification_records` (`postedAt`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_records_notificationKey` ON `notification_records` (`notificationKey`)")
            }
        }

        /** M5: typed, keyed, editable memories. Existing rows are kept as facts with updatedAt = createdAt. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `memories` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'fact'")
                database.execSQL("ALTER TABLE `memories` ADD COLUMN `memoryKey` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `memories` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE `memories` SET `updatedAt` = `createdAt`")
            }
        }

        /** Phase 5: adds durable tasks and goals. Additive only: no existing table or row is changed. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                MIGRATION_5_6_SQL.forEach(database::execSQL)
            }
        }

        /** Copied from the exported schema app/schemas/.../6.json so the migrated and fresh databases are identical. */
        internal val MIGRATION_5_6_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `agent_tasks` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `objective` TEXT NOT NULL, `inputsJson` TEXT NOT NULL, `scheduledAt` INTEGER, `timeZone` TEXT, `status` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `maxAttempts` INTEGER NOT NULL, `timeoutMs` INTEGER NOT NULL, `result` TEXT, `originRequest` TEXT, `approval` TEXT NOT NULL, `goalId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_agent_tasks_status` ON `agent_tasks` (`status`)",
            "CREATE INDEX IF NOT EXISTS `index_agent_tasks_goalId` ON `agent_tasks` (`goalId`)",
            "CREATE TABLE IF NOT EXISTS `goals` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `status` TEXT NOT NULL, `dueAt` INTEGER, `originRequest` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )

        /** Phase 6: adds plan executions and their steps. Additive only. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                MIGRATION_6_7_SQL.forEach(database::execSQL)
            }
        }

        /** Copied from app/schemas/.../7.json. */
        internal val MIGRATION_6_7_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `plan_executions` (`id` TEXT NOT NULL, `objective` TEXT NOT NULL, `status` TEXT NOT NULL, `stepsHash` TEXT NOT NULL, `approvalHash` TEXT, `approvedAt` INTEGER, `summary` TEXT, `failureReason` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_plan_executions_status` ON `plan_executions` (`status`)",
            "CREATE TABLE IF NOT EXISTS `plan_steps` (`id` TEXT NOT NULL, `executionId` TEXT NOT NULL, `position` INTEGER NOT NULL, `tool` TEXT NOT NULL, `argsJson` TEXT NOT NULL, `postcondition` TEXT NOT NULL, `requiresApproval` INTEGER NOT NULL, `retrySafe` INTEGER NOT NULL, `status` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `effectRef` TEXT, `result` TEXT, `evidence` TEXT, `error` TEXT, `startedAt` INTEGER, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_plan_steps_executionId` ON `plan_steps` (`executionId`)"
        )

        /** Phase 8B: adds the observed-event log. Additive only. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                MIGRATION_7_8_SQL.forEach(database::execSQL)
            }
        }

        /** Copied from app/schemas/.../8.json. */
        internal val MIGRATION_7_8_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `app_events` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `subjectId` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `handledAt` INTEGER, `outcome` TEXT, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_app_events_handledAt` ON `app_events` (`handledAt`)"
        )

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)

        @Volatile private var instance: AppDatabase? = null

        /** One shared instance: the notification listener and the UI write the same database. */
        fun create(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "jarvis.db"
            ).addMigrations(*ALL_MIGRATIONS).build().also { instance = it }
        }
    }
}
