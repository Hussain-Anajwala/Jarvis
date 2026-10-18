package com.jarvis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.ui.ActivityCategory
import com.jarvis.ui.ActivityEvent
import com.jarvis.ui.ActivityFeed
import com.jarvis.ui.ActivityFilter
import com.jarvis.ui.ActivityStatus
import com.jarvis.ui.JarvisSpacing
import com.jarvis.ui.JarvisViewModel

/** What JARVIS did and why: readable events with an honest status, newest first. */
@Composable
internal fun ActivityScreen(vm: JarvisViewModel, modifier: Modifier) {
    val traces by vm.traces.collectAsState()
    val plans by vm.plans.collectAsState()
    val events = remember(traces) { traces.take(MAX_EVENTS).map(ActivityFeed::from) }
    var show by rememberSaveable { mutableStateOf(ActivityFilter.ACTIONS) }
    var risk by rememberSaveable { mutableStateOf<String?>(null) }
    var planId by rememberSaveable { mutableStateOf<String?>(null) }
    var moreFilters by rememberSaveable { mutableStateOf(false) }
    val shown = remember(events, show, risk, planId) { ActivityFeed.filter(events, show, risk, planId) }
    val hiddenSystem = remember(events, show) { if (show == ActivityFilter.ACTIONS) events.count { it.category == ActivityCategory.SYSTEM } else 0 }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(JarvisSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.List)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Activity", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "What JARVIS did and why, newest first. Message and email text is never shown here.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.Compact)) {
                ActivityFilter.entries.forEach { option ->
                    FilterChip(selected = show == option, onClick = { show = option }, label = { Text(option.label) })
                }
            }
        }
        item {
            val active = (if (risk != null) 1 else 0) + (if (planId != null) 1 else 0)
            TextButton(onClick = { moreFilters = !moreFilters }) {
                Text(if (moreFilters) "Fewer filters" else if (active > 0) "More filters ($active on)" else "More filters")
            }
        }
        if (moreFilters) item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.Compact)) {
                FilterChip(selected = risk == null, onClick = { risk = null }, label = { Text("Any risk") })
                listOf("low", "medium", "high").forEach { level ->
                    FilterChip(selected = risk == level, onClick = { risk = level }, label = { Text(level.replaceFirstChar { it.uppercase() }) })
                }
            }
        }
        if (moreFilters && plans.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.Compact)) {
                FilterChip(selected = planId == null, onClick = { planId = null }, label = { Text("All plans") })
                plans.forEach { plan ->
                    FilterChip(
                        selected = planId == plan.id, onClick = { planId = plan.id },
                        label = { Text(plan.goalText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) }
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                val filtered = risk != null || planId != null || show != ActivityFilter.ACTIONS
                EmptyState(
                    title = if (events.isEmpty()) "Nothing here yet" else if (show == ActivityFilter.PROBLEMS && risk == null && planId == null) "No problems" else "No matching events",
                    body = when {
                        events.isEmpty() -> "When JARVIS creates a plan, plays music, or answers a request, it shows up here."
                        show == ActivityFilter.PROBLEMS && risk == null && planId == null -> "Nothing has failed recently."
                        else -> "Try a different filter."
                    },
                    action = if (filtered) "Show actions" to { show = ActivityFilter.ACTIONS; risk = null; planId = null } else null
                )
            }
        }
        items(shown, key = { it.id }) { event ->
            EventCard(event, plans.firstOrNull { it.id == event.planId }?.goalText)
        }
        if (hiddenSystem > 0) {
            item {
                TextButton(onClick = { show = ActivityFilter.EVERYTHING }) {
                    Text("$hiddenSystem background checks and model calls hidden — show everything")
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String, action: Pair<String, () -> Unit>?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { (label, run) -> TextButton(onClick = run) { Text(label) } }
    }
}

@Composable
private fun EventCard(event: ActivityEvent, planTitle: String?) {
    val time = ActivityFeed.time(event.createdAt)
    val statusColor = when (event.status) {
        ActivityStatus.DONE -> MaterialTheme.colorScheme.primary
        ActivityStatus.PROBLEM -> MaterialTheme.colorScheme.error
        ActivityStatus.NOTE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "${event.title}. ${event.status.label}. ${event.detail.orEmpty()} $time"
        },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(JarvisSpacing.Section), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(event.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                // Status is words plus colour, never colour alone.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.size(8.dp).background(statusColor, CircleShape))
                    Text(event.status.label, color = statusColor, style = MaterialTheme.typography.labelMedium)
                }
            }
            event.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            planTitle?.let { Text("Plan: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                listOf(time, event.meta).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private const val MAX_EVENTS = 300
