package com.jarvis

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.content.pm.PackageManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.core.Capability
import com.jarvis.core.BackgroundEngine
import com.jarvis.core.NotificationPublisher
import com.jarvis.core.PermissionLayer
import com.jarvis.data.Plan
import com.jarvis.core.HabitRoutineEngine
import com.jarvis.core.CommunicationRequestParser
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BackgroundEngine.schedule(this)
        NotificationPublisher.createChannels(this)
        lifecycleScope.launch { HabitRoutineEngine.seedAndDetect(this@MainActivity) }
        setContent { JarvisApp() }
    }
}

@Composable
private fun JarvisApp(vm: com.jarvis.ui.JarvisViewModel = viewModel()) {
    var tab by remember { mutableStateOf(0) }
    MaterialTheme {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("Home", "Plans", "History", "Automations", "Permissions").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = {}, label = { Text(label) })
                }
            }
        }) { padding ->
            when (tab) {
                0 -> HomeScreen(vm, Modifier.padding(padding))
                1 -> PlansScreen(vm, Modifier.padding(padding))
                2 -> DecisionHistoryScreen(vm, Modifier.padding(padding))
                3 -> AutomationScreen(vm, Modifier.padding(padding))
                else -> PermissionScreen(Modifier.padding(padding))
            }

        }
        if (vm.confirmCancel) {
            AlertDialog(
                onDismissRequest = vm::dismissCancellation,
                title = { Text("Cancel meeting?") },
                text = { Text("This medium-risk action will also cancel the linked departure reminder.") },
                confirmButton = { Button(onClick = vm::confirmCancellation) { Text("Cancel meeting") } },
                dismissButton = { TextButton(onClick = vm::dismissCancellation) { Text("Keep it") } }
            )
        }

        vm.communicationDraft?.let { draft ->
            AlertDialog(
                onDismissRequest = vm::dismissCommunication,
                title = { Text(if (vm.editingCommunicationDraft) "Edit draft" else "Send this draft?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("To: ${vm.communicationRecipient.orEmpty()}")
                        if (vm.editingCommunicationDraft) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = vm::updateCommunicationDraft,
                                label = { Text("Message") }
                            )
                        } else {
                            Text(draft)
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = vm::confirmCommunication) {
                        Text(if (vm.editingCommunicationDraft) "Send" else "Open communication app")
                    }
                },
                dismissButton = {
                    if (vm.editingCommunicationDraft) {
                        TextButton(onClick = vm::dismissCommunication) { Text("Cancel") }
                    } else {
                        TextButton(onClick = vm::beginCommunicationEdit) { Text("Keep editing") }
                    }
                }
            )
        }
        vm.communicationResolutionError?.let { message ->
            AlertDialog(
                onDismissRequest = vm::dismissCommunicationResolutionError,
                title = { Text("Recipient unavailable") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = vm::dismissCommunicationResolutionError) { Text("OK") }
                }
            )
        }
    }
}

@Composable
private fun HomeScreen(vm: com.jarvis.ui.JarvisViewModel, modifier: Modifier) {
    var input by remember { mutableStateOf("") }
    var pendingCommunicationRequest by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pendingRequest = pendingCommunicationRequest
        pendingCommunicationRequest = null
        if (granted && pendingRequest != null) {
            vm.submit(pendingRequest)
        } else if (!granted) {
            vm.reportContactsPermissionDenied()
        }
    }
    val traces by vm.traces.collectAsState()
    val pulse = rememberInfiniteTransition(label = "jarvis-pulse").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "jarvis-pulse-alpha"
    )
    val navy = Color(0xFF08111F)
    val accent = Color(0xFF35A7FF)
    Box(modifier.fillMaxSize().background(navy)) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("JARVIS", color = Color.White, style = MaterialTheme.typography.headlineLarge)
                Text("●", color = accent.copy(alpha = pulse.value), style = MaterialTheme.typography.headlineSmall)
            }
            Text("LOCAL-FIRST OPERATING SYSTEM", color = accent, style = MaterialTheme.typography.labelMedium)
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("JARVIS says", color = accent, style = MaterialTheme.typography.labelLarge)
                Text(vm.reply, Modifier.padding(top = 8.dp))
            } }
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text("Tell JARVIS what to do") })
        Button(onClick = {
            val request = input
            input = ""
            if (CommunicationRequestParser.isCommunicationIntent(request) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingCommunicationRequest = request
                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            } else {
                vm.submit(request)
            }
        }, Modifier.fillMaxWidth()) { Text("Run request") }
        OutlinedButton(onClick = vm::recheckTravel, Modifier.fillMaxWidth()) {
            Text("Re-check travel now")
        }
            Text("Decision Trace", color = accent, style = MaterialTheme.typography.titleMedium)
            traces.take(3).forEach { trace ->
                Text("• ${trace.actionSummary} [${trace.riskLevel} / ${trace.outcome}] ${trace.createdAt.asTraceTime()}", color = Color(0xFFB8C7D9))
            }
        }
    }
}

private fun Long.asTraceTime(): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(this))

@Composable
private fun PlansScreen(vm: com.jarvis.ui.JarvisViewModel, modifier: Modifier) {
    val plans by vm.plans.collectAsState()
    val planItems by vm.planItems.collectAsState()
    val reminders by vm.reminders.collectAsState()
    val activePlans = plans.filter { it.status == "active" }
    val inactivePlans = plans.filter { it.status != "active" }
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Plans", style = MaterialTheme.typography.headlineMedium) }
        if (plans.isEmpty()) item { Text("No plans yet. Ask JARVIS to create a meeting.") }
        if (activePlans.isNotEmpty()) item { Text("Active", style = MaterialTheme.typography.titleLarge) }
        items(activePlans, key = { it.id }) { plan ->
            PlanCard(plan, planItems.filter { it.planId == plan.id }, reminders, vm)
        }
        if (inactivePlans.isNotEmpty()) item { Text("Completed / Cancelled", style = MaterialTheme.typography.titleLarge) }
        items(inactivePlans, key = { it.id }) { plan ->
            PlanCard(plan, planItems.filter { it.planId == plan.id }, reminders, vm)
        }
    }
}

@Composable
private fun PlanCard(
    plan: Plan,
    items: List<com.jarvis.data.PlanItem>,
    reminders: List<com.jarvis.data.Reminder>,
    vm: com.jarvis.ui.JarvisViewModel
) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Text(plan.goalText, style = MaterialTheme.typography.titleMedium)
        Text("Status: ${plan.status}")
        Text("${items.size} linked PlanItems")
        items.forEach { item ->
            val reminderStatus = reminders.firstOrNull { it.id == item.refId }?.status
            Text("${item.itemType}: ${item.status}${reminderStatus?.let { " (reminder $it)" } ?: ""}")
        }
        if (plan.status == "active") {
            Button(onClick = {
                Log.d("JarvisCancellation", "Plans card Cancel onClick plan=${plan.id}")
                vm.requestCancellation(plan.id)
            }) {
                Text("Cancel")
            }
        }
    } }
}

@Composable
private fun DecisionHistoryScreen(vm: com.jarvis.ui.JarvisViewModel, modifier: Modifier) {
    val traces by vm.traces.collectAsState()
    val plans by vm.plans.collectAsState()
    var riskFilter by remember { mutableStateOf("all") }
    var planFilter by remember { mutableStateOf<String?>(null) }
    val filtered = traces.filter { trace ->
        (riskFilter == "all" || trace.riskLevel == riskFilter) &&
            (planFilter == null || trace.planId == planFilter)
    }
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Decision History", style = MaterialTheme.typography.headlineMedium)
            Text("Why JARVIS took each action, newest first.")
        }
        item {
            Text("Risk level", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all", "low", "medium", "high").forEach { risk ->
                    OutlinedButton(onClick = { riskFilter = risk }) {
                        Text(if (risk == riskFilter) "selected: $risk" else risk)
                    }
                }
            }
        }
        item {
            Text("Plan", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { planFilter = null }) {
                    Text(if (planFilter == null) "selected: all" else "all")
                }
                plans.forEach { plan ->
                    OutlinedButton(onClick = { planFilter = plan.id }) {
                        Text(if (planFilter == plan.id) "selected: ${plan.goalText}" else plan.goalText)
                    }
                }
            }
        }
        if (filtered.isEmpty()) item { Text("No decision trace entries match these filters.") }
        items(filtered) { trace ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(trace.actionSummary, style = MaterialTheme.typography.titleMedium)
                    Text("${trace.riskLevel} risk · ${trace.outcome} · ${trace.createdAt.asTraceTime()}")
                    Text("Tool: ${trace.toolName}")
                    Text("Plan: ${trace.planId ?: "Background / unlinked"}")
                }
            }
        }
    }
}

@Composable
private fun AutomationScreen(vm: com.jarvis.ui.JarvisViewModel, modifier: Modifier) {
    val rules by vm.automationRules.collectAsState()
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Automations", style = MaterialTheme.typography.headlineMedium) }
        if (rules.isEmpty()) item { Text("No routine suggestions yet.") }
        items(rules, key = { it.id }) { rule ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(rule.patternDescription, style = MaterialTheme.typography.titleMedium)
                    Text("Status: ${rule.status}")
                    if (rule.status == "suggested") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.approveAutomation(rule.id) }) { Text("Approve") }
                            TextButton(onClick = { vm.dismissAutomation(rule.id) }) { Text("Dismiss") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionLayer = remember { PermissionLayer(context) }
    var requested by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { requested = null }
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Permission Center", style = MaterialTheme.typography.headlineMedium)
        Text("JARVIS only uses capabilities you explicitly grant.")
        Capability.entries.forEach { capability ->
            val granted = permissionLayer.isGranted(capability)
            Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(capability.label, style = MaterialTheme.typography.titleMedium)
                    Text(capability.description)
                    Text(if (granted) "Granted" else "Not granted")
                }
                OutlinedButton(onClick = {
                    requested = capability.label
                    launcher.launch(capability.permissions)
                }) { Text(if (granted) "Review" else "Grant") }
            } }
        }
    }
}
