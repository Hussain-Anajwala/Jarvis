package com.jarvis.core

import com.jarvis.data.DecisionTraceEntry
import com.jarvis.data.TraceDao

/**
 * The shared sanitizer for user-derived text that JARVIS keeps or hands to another app: Activity/trace rows, plan and
 * reminder titles, calendar events (which the calendar app may sync to the cloud), tasks, captured notification
 * snippets, and memories. Secret values ([SensitiveData]) are replaced; dates, times, prices, flight numbers, and
 * ordinary words are kept. Pattern-based, so an unlabelled secret is not recognised (docs/TRD.md §6.1).
 */
object SafeStorage {
    fun text(value: String): String = SensitiveData.forStorage(value)
    fun optional(value: String?): String? = value?.let(::text)
    fun trace(entry: DecisionTraceEntry): DecisionTraceEntry =
        entry.copy(actionSummary = text(entry.actionSummary), outcome = text(entry.outcome))
}

/** The only way JARVIS writes a trace (a source test enforces this): the row is redacted first. */
suspend fun TraceDao.insertRedacted(entry: DecisionTraceEntry) = insert(SafeStorage.trace(entry))
