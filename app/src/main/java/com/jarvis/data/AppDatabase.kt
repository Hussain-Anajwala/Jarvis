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
        MonitoringRule::class
    ],
    version = 2,
    exportSchema = false
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

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS plan_item_dependencies (planItemId TEXT NOT NULL, dependsOnItemId TEXT NOT NULL, PRIMARY KEY(planItemId, dependsOnItemId))")
                database.execSQL("CREATE TABLE IF NOT EXISTS automation_rules (id TEXT NOT NULL PRIMARY KEY, patternDescription TEXT NOT NULL, status TEXT NOT NULL, createdFromPlanIds TEXT NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS monitoring_rules (id TEXT NOT NULL PRIMARY KEY, planItemId TEXT NOT NULL, conditionType TEXT NOT NULL, lastCheckedAt INTEGER, nextCheckAt INTEGER, lastKnownValue TEXT)")
            }
        }

        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context, AppDatabase::class.java, "jarvis.db"
        ).addMigrations(MIGRATION_1_2).build()
    }
}
