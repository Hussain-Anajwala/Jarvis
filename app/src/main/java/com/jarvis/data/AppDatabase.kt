package com.jarvis.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// TODO: Replace plain Room with SQLCipher and a Keystore-backed key before production.
@Database(
    entities = [
        UserProfile::class, Plan::class, PlanItem::class, Event::class,
        Reminder::class, Task::class, Memory::class, PermissionGrant::class,
        DecisionTraceEntry::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun planDao(): PlanDao
    abstract fun planItemDao(): PlanItemDao
    abstract fun eventDao(): EventDao
    abstract fun reminderDao(): ReminderDao
    abstract fun taskDao(): TaskDao
    abstract fun traceDao(): TraceDao
    abstract fun permissionDao(): PermissionDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context, AppDatabase::class.java, "jarvis.db"
        ).build()
    }
}
