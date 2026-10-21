package com.jarvis

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.core.Experiment
import com.jarvis.core.ResearchExport
import com.jarvis.core.ResearchMetric
import com.jarvis.core.SourceType
import com.jarvis.ui.JarvisSpacing
import com.jarvis.ui.JarvisViewModel

private val SECTIONS = listOf(
    "routing" to "AI routing and evaluation",
    "language" to "English and Hinglish requests",
    "memory" to "Contextual memory",
    "pev" to "Plan → execute → verify",
    "tools" to "Tool registry and events",
    "efficiency" to "Efficiency",
    "privacy" to "Privacy and safety",
    "tests" to "Tests, lint, and build",
    "device" to "Checks on the phone",
    "feasibility" to "Feasibility (estimates)"
)

/**
 * Phase 7 research dashboard. Everything shown comes from the bundled evidence file (computed from the repository's
 * recorded artifacts) or from counts of this phone's own records. Each card says what kind of evidence it is; nothing
 * is combined into one overall score.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResearchScreen(vm: JarvisViewModel, modifier: Modifier) {
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { vm.writeExport(it, json = true) }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { vm.writeExport(it, json = false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(JarvisSpacing.Screen), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.List)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Research", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::closeResearch) { Text("Close") }
            }
            Text(
                "A Privacy-Aware, Adaptive AI Agent for Android with Persistent Task Execution and Verifiable Tool Use. " +
                    "Each result is labelled with how it was obtained; replays, simulations, estimates, and old runs are not new measurements.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when (val state = vm.evidenceState) {
            JarvisViewModel.EvidenceState.Loading -> item { Text("Loading…") }
            is JarvisViewModel.EvidenceState.Failed -> item { Text(state.reason, color = MaterialTheme.colorScheme.error) }
            is JarvisViewModel.EvidenceState.Loaded -> {
                val evidence = state.evidence
                item {
                    Text("Evidence built from commit ${evidence.commit ?: "unknown"} on ${evidence.generatedOn ?: "unknown date"} · ${evidence.experiments.size} entries",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { SourceLegend() }
                SECTIONS.forEach { (category, title) ->
                    val list = evidence.experiments.filter { it.category == category }
                    item { Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
                    if (category == "routing") item { RoutingBars(list) }
                    if (list.isEmpty()) item { Text("No data for this section.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(list, key = { it.id }) { ExperimentCard(it) }
                }
            }
        }
        item { Text("This phone's records", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
        item { DeviceRecordsCard(vm.deviceRecords) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Export", style = MaterialTheme.typography.titleMedium)
                    Text("What is exported: ${ResearchExport.CONTENTS}", style = MaterialTheme.typography.bodySmall)
                    Text("You choose where the file is saved; nothing is exported automatically or sent anywhere.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val ready = vm.evidenceState is JarvisViewModel.EvidenceState.Loaded
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = ready, onClick = { saveCsv.launch("jarvis-research.csv") }) { Text("Export CSV") }
                        OutlinedButton(enabled = ready, onClick = { saveJson.launch("jarvis-research.json") }) { Text("Export JSON") }
                    }
                    vm.exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceLegend() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Kinds of evidence", style = MaterialTheme.typography.titleSmall)
            SourceType.entries.forEach { Text("${it.label}: ${it.explanation}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExperimentCard(e: Experiment) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(e.title, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip(e.sourceType.label)
                Chip(e.runDate ?: "no date")
                Chip(e.sampleSize?.let { "n = $it" } ?: "n/a")
                Chip(e.status.replace('_', ' '))
            }
            Text(e.description, style = MaterialTheme.typography.bodySmall)
            e.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            e.metrics.forEach { MetricRow(it) }
            Text("Source: " + e.sources.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "94.8% (127/134 items)", "352 ms", "not measured" — never a zero for a missing value. */
internal fun formatMetric(m: ResearchMetric): String = when {
    m.unit == "text" -> m.text ?: "not measured"
    m.value == null -> "not measured"
    m.unit == "percent" -> "%.1f%%".format(m.value) + (if (m.numerator != null) " (${m.numerator}/${m.denominator} ${m.denominatorLabel.orEmpty()})".replace(" )", ")") else "")
    m.unit == "ms" -> "${ResearchExport.number(m.value)} ms"
    else -> ResearchExport.number(m.value)
}

@Composable
private fun MetricRow(m: ResearchMetric) {
    Column {
        Row {
            Text(m.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(formatMetric(m), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(m.definition, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Tool-selection accuracy for the runs on the same 134 original items, with each bar's evidence kind. */
@Composable
private fun RoutingBars(experiments: List<Experiment>) {
    val rows = experiments.filter { it.sampleSize == 134 }.mapNotNull { e -> e.metrics.firstOrNull { it.id == "tool_accuracy" }?.value?.let { e to it } }
    if (rows.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tool-selection accuracy, 134 original items (2026-10-21)", style = MaterialTheme.typography.titleSmall)
            rows.forEach { (e, v) ->
                Column {
                    Text("${e.title} — ${"%.1f".format(v)}% · ${e.sourceType.label}", style = MaterialTheme.typography.bodySmall)
                    Box(Modifier.fillMaxWidth().height(10.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))) {
                        Box(Modifier.fillMaxWidth((v / 100.0).toFloat()).height(10.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)))
                    }
                }
            }
            Text("The adaptive bar is a replay of the hybrid run's cloud answers, not a new run. Held-out results (23 items) are in their own cards.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceRecordsCard(records: com.jarvis.core.DeviceRecords?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(SourceType.ON_DEVICE_RECORD.explanation + ". Counts only, never content.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (records == null) { Text("Loading…"); return@Column }
            records.since?.let { Text("Privacy and routing records since ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}", style = MaterialTheme.typography.bodySmall) }
            listOf(
                "Plan runs by status" to records.planRunsByStatus,
                "Plan steps by status" to records.planStepsByStatus,
                "Privacy decisions" to records.privacyDecisions,
                "Where requests were answered" to records.routingTargets
            ).forEach { (title, counts) ->
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (counts.isEmpty()) Text("No records yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                counts.forEach { (k, v) -> Row { Text(k.replace('_', ' ').lowercase(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text("$v", style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }
}
