package com.jarvis.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Query("SELECT * FROM plans ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Plan>>
    @Query("SELECT * FROM plans WHERE status = 'active'")
    suspend fun activePlans(): List<Plan>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(plan: Plan)
    @Query("UPDATE plans SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)
}

@Dao
interface PlanItemDao {
    @Query("SELECT * FROM plan_items ORDER BY planId")
    fun observeAll(): Flow<List<PlanItem>>
    @Query("SELECT * FROM plan_item_dependencies")
    suspend fun dependencies(): List<PlanItemDependency>
    @Query("SELECT * FROM plan_items WHERE planId = :planId")
    suspend fun forPlan(planId: String): List<PlanItem>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: PlanItem)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDependency(dependency: PlanItemDependency)
    @Query("UPDATE plan_items SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)
}

@Dao
interface MonitoringRuleDao {
    @Query("SELECT * FROM monitoring_rules WHERE planItemId IN (SELECT id FROM plan_items WHERE status = 'active')")
    suspend fun activeRules(): List<MonitoringRule>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(rule: MonitoringRule)
    @Query("UPDATE monitoring_rules SET lastCheckedAt = :checkedAt WHERE id = :id")
    suspend fun markChecked(id: String, checkedAt: Long)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY startTime")
    fun observeAll(): Flow<List<Event>>
    @Query("SELECT * FROM events WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): Event?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(event: Event)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY triggerTime")
    fun observeAll(): Flow<List<Reminder>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(reminder: Reminder)
    @Query("UPDATE reminders SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY dueAt")
    fun observeAll(): Flow<List<Task>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(task: Task)
}

@Dao
interface TraceDao {
    @Query("SELECT * FROM decision_trace_entries ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DecisionTraceEntry>>
    @Insert suspend fun insert(entry: DecisionTraceEntry)
}

@Dao
interface PermissionDao {
    @Query("SELECT * FROM permission_grants")
    fun observeAll(): Flow<List<PermissionGrant>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(grant: PermissionGrant)
}
