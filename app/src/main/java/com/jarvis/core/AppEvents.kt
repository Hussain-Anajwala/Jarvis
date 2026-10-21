package com.jarvis.core

import com.jarvis.data.AppEvent

/** The observed state changes JARVIS records. Anything else is rejected. */
object EventTypes {
    const val PLAN_FINISHED = "plan.finished"
    const val TASK_FAILED = "task.failed"
    const val TASK_MISSED = "task.missed"
    const val GOAL_COMPLETED = "goal.completed"
    val ALL = setOf(PLAN_FINISHED, TASK_FAILED, TASK_MISSED, GOAL_COMPLETED)
}

interface EventStore {
    /** False when an event with this id already exists (a duplicate delivery). */
    suspend fun insertIfAbsent(event: AppEvent): Boolean
    suspend fun unhandled(): List<AppEvent>
    /** False when it was already marked handled (another run got there first). */
    suspend fun markHandled(id: String, at: Long, outcome: String): Boolean
}

/**
 * Everything an event handler is allowed to do: post a local notification under a stable key (posting again replaces
 * it) or add an Activity record. Handlers get no tools, no network, no messaging, and cannot emit events, so an event
 * can never call, send, change an account, or start a loop.
 */
interface EventEffects {
    fun notifyLocal(key: String, title: String, text: String)
    suspend fun record(text: String)
}

fun interface EventHandler {
    /** Reacts to [event] after checking the current state (a cancelled plan or goal gets no reaction). Returns what it did. */
    suspend fun handle(event: AppEvent, effects: EventEffects): String
}

sealed interface EmitResult {
    data class Handled(val outcome: String) : EmitResult
    data object Duplicate : EmitResult
    data class Rejected(val reason: String) : EmitResult
}

/**
 * Phase 8B event bus. An event is emitted by the component that observed the change (TaskEngine, PlanExecutor), never
 * by a model. It is stored first under a deterministic id, so a second delivery is a no-op; then its handler runs once
 * and the event is marked handled. Events left unhandled by a crash are handled on the next start.
 */
class EventBus(
    private val store: EventStore,
    private val handlers: Map<String, EventHandler>,
    private val effects: EventEffects,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val safeId = Regex("""^[A-Za-z0-9#:._-]{1,100}$""")
    private val safeStatus = Regex("""^[A-Za-z_]{1,24}$""")

    suspend fun emit(type: String, subjectId: String, status: String): EmitResult {
        if (type !in EventTypes.ALL) return EmitResult.Rejected("unknown event type")
        if (!safeId.matches(subjectId) || !safeStatus.matches(status)) return EmitResult.Rejected("event payload is not a plain id and status")
        val event = AppEvent("$type:$subjectId:$status", type, subjectId, status, clock(), null, null)
        if (!store.insertIfAbsent(event)) return EmitResult.Duplicate
        return EmitResult.Handled(dispatch(event))
    }

    /** After a restart: events stored but not yet handled are handled now (handlers check current state first). */
    suspend fun recover(): Int = store.unhandled().count { dispatch(it); true }

    private suspend fun dispatch(event: AppEvent): String {
        val outcome = try {
            handlers[event.type]?.handle(event, effects) ?: "no handler"
        } catch (e: Exception) {
            "handler failed (${e.javaClass.simpleName})"
        }
        store.markHandled(event.id, clock(), SafeStorage.text(outcome).take(200))
        return outcome
    }
}
