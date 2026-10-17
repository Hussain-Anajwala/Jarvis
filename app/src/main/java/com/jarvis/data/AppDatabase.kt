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
        MonitoringRule::class, NotificationRecord::class
    ],
    version = 4,
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

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        @Volatile private var instance: AppDatabase? = null

        /** One shared instance: the notification listener and the UI write the same database. */
        fun create(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "jarvis.db"
            ).addMigrations(*ALL_MIGRATIONS).build().also { instance = it }
        }
    }
}
