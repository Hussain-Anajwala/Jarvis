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
    @Query("SELECT * FROM plan_items WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PlanItem?
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
    @Query("SELECT * FROM monitoring_rules WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): MonitoringRule?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(rule: MonitoringRule)
    @Query("UPDATE monitoring_rules SET lastCheckedAt = :checkedAt, lastKnownValue = :lastKnownValue WHERE id = :id")
    suspend fun markChecked(id: String, checkedAt: Long, lastKnownValue: String)
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
    @Query("UPDATE reminders SET triggerTime = :triggerTime WHERE id = :id")
    suspend fun updateTriggerTime(id: String, triggerTime: Long)

    /**
     * Removes reminders with no plan item. Only the old standalone-reminder code (removed 2026-10-21) created them;
     * the app shows and fires reminders only through their plan item, so these could never be seen, fired, or
     * deleted. Meeting reminders always have a plan item and are never touched. Returns how many were removed.
     */
    @Query("DELETE FROM reminders WHERE planItemId IS NULL")
    suspend fun deleteOrphans(): Int
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

@Dao
interface AutomationRuleDao {
    @Query("SELECT * FROM automation_rules ORDER BY status, patternDescription")
    fun observeAll(): Flow<List<AutomationRule>>
    @Query("SELECT * FROM automation_rules")
    suspend fun observeAllSnapshot(): List<AutomationRule>
    @Query("UPDATE automation_rules SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(rule: AutomationRule)
}

@Dao
interface NotificationRecordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(records: List<NotificationRecord>)
    @Query("SELECT * FROM notification_records WHERE read = 0 AND postedAt >= :since ORDER BY postedAt DESC")
    suspend fun unreadSince(since: Long): List<NotificationRecord>
    @Query("SELECT COUNT(*) FROM notification_records")
    fun observeCount(): Flow<Int>
    @Query("UPDATE notification_records SET read = 1 WHERE id IN (:ids)")
    suspend fun markRead(ids: List<String>)
    @Query("UPDATE notification_records SET read = 1 WHERE notificationKey = :key")
    suspend fun markReadByKey(key: String)
    @Query("DELETE FROM notification_records WHERE postedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
    @Query("DELETE FROM notification_records WHERE id NOT IN (SELECT id FROM notification_records ORDER BY postedAt DESC LIMIT :max)")
    suspend fun trimTo(max: Int)
    @Query("DELETE FROM notification_records")
    suspend fun clearAll()
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Memory>>
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    suspend fun all(): List<Memory>
    @Query("SELECT * FROM memories WHERE type = :type AND memoryKey = :key LIMIT 1")
    suspend fun findByKey(type: String, key: String): Memory?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(memory: Memory)
    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: String)
    @Query("DELETE FROM memories")
    suspend fun deleteAll()
    @Query("UPDATE memories SET lastUsedAt = :usedAt WHERE id IN (:ids)")
    suspend fun markUsed(ids: List<String>, usedAt: Long)
}

@Dao
interface AgentTaskDao {
    /** Aborts on a duplicate id, so the same task can never be stored twice. */
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(task: AgentTask)
    @Query("SELECT * FROM agent_tasks WHERE id = :id") suspend fun get(id: String): AgentTask?
    @Query("SELECT * FROM agent_tasks ORDER BY scheduledAt") fun observeAll(): Flow<List<AgentTask>>
    @Query("SELECT * FROM agent_tasks ORDER BY scheduledAt") suspend fun all(): List<AgentTask>
    @Query("SELECT * FROM agent_tasks WHERE goalId = :goalId ORDER BY scheduledAt") suspend fun forGoal(goalId: String): List<AgentTask>

    /** Moves a task only if it is still in one of [from]; returns 0 when another run already moved it. */
    @Query(
        "UPDATE agent_tasks SET status = :to, updatedAt = :now, result = COALESCE(:result, result), " +
            "attempts = attempts + :attemptDelta WHERE id = :id AND status IN (:from)"
    )
    suspend fun transition(id: String, from: List<String>, to: String, now: Long, result: String?, attemptDelta: Int): Int

    @Query("UPDATE agent_tasks SET scheduledAt = :at, updatedAt = :now WHERE id = :id")
    suspend fun setTime(id: String, at: Long, now: Long)

    @Query("UPDATE agent_tasks SET result = :result, updatedAt = :now WHERE id = :id")
    suspend fun setResult(id: String, result: String, now: Long)
}

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(goal: Goal)
    @Query("SELECT * FROM goals WHERE id = :id") suspend fun get(id: String): Goal?
    @Query("SELECT * FROM goals ORDER BY createdAt DESC") fun observeAll(): Flow<List<Goal>>
    @Query("SELECT * FROM goals ORDER BY createdAt DESC") suspend fun all(): List<Goal>
    @Query("UPDATE goals SET status = :status, updatedAt = :now WHERE id = :id") suspend fun setStatus(id: String, status: String, now: Long)
}

@Dao
interface PlanExecutionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertExecution(execution: PlanExecution)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSteps(steps: List<PlanStepRecord>)
    @Query("SELECT * FROM plan_executions WHERE id = :id") suspend fun execution(id: String): PlanExecution?
    @Query("SELECT * FROM plan_executions ORDER BY createdAt DESC") suspend fun executions(): List<PlanExecution>
    @Query("SELECT * FROM plan_executions ORDER BY createdAt DESC LIMIT 20") fun observeRecent(): Flow<List<PlanExecution>>
    @Query("SELECT * FROM plan_steps WHERE executionId = :executionId ORDER BY position") suspend fun steps(executionId: String): List<PlanStepRecord>
    @Query("SELECT * FROM plan_steps ORDER BY position") fun observeSteps(): Flow<List<PlanStepRecord>>
    @androidx.room.Update suspend fun updateExecution(execution: PlanExecution)
    @androidx.room.Update suspend fun updateStep(step: PlanStepRecord)
    /** Conditional status change; 0 means another run already moved it (no duplicate effect). */
    @Query("UPDATE plan_steps SET status = :to, attempts = attempts + :attemptDelta, startedAt = COALESCE(:startedAt, startedAt), updatedAt = :now WHERE id = :id AND status IN (:from)")
    suspend fun moveStep(id: String, from: List<String>, to: String, attemptDelta: Int, startedAt: Long?, now: Long): Int
}
