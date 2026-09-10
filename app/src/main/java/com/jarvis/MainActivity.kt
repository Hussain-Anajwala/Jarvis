package com.jarvis

import android.Manifest
import android.os.Build
import android.os.Bundle
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.core.Capability
import com.jarvis.core.PermissionLayer
import com.jarvis.data.Plan

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JarvisApp() }
    }
}

@Composable
private fun JarvisApp(vm: com.jarvis.ui.JarvisViewModel = viewModel()) {
    var tab by remember { mutableStateOf(0) }
    MaterialTheme {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("Home", "Plans", "Permissions").forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = {}, label = { Text(label) })
                }
            }
        }) { padding ->
            when (tab) {
                0 -> HomeScreen(vm, Modifier.padding(padding))
                1 -> PlansScreen(vm, Modifier.padding(padding))
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
    }
}

@Composable
private fun HomeScreen(vm: com.jarvis.ui.JarvisViewModel, modifier: Modifier) {
    var input by remember { mutableStateOf("") }
    val traces by vm.traces.collectAsState()
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("JARVIS", style = MaterialTheme.typography.headlineLarge)
        Text("Local-first personal assistant", style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("JARVIS says", style = MaterialTheme.typography.labelLarge)
            Text(vm.reply, Modifier.padding(top = 8.dp))
        } }
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text("Tell JARVIS what to do") })
        Button(onClick = { vm.submit(input); input = "" }, Modifier.fillMaxWidth()) { Text("Run request") }
        Text("Decision Trace", style = MaterialTheme.typography.titleMedium)
        traces.take(3).forEach { trace ->
            Text("• ${trace.actionSummary} [${trace.riskLevel} / ${trace.outcome}]")
        }
    }
}

@Composable
private fun PlansScreen(vm: com.jarvis.ui.JarvisViewModel, modifier: Modifier) {
    val plans by vm.plans.collectAsState()
    val planItems by vm.planItems.collectAsState()
    val reminders by vm.reminders.collectAsState()
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Plans", style = MaterialTheme.typography.headlineMedium) }
        if (plans.isEmpty()) item { Text("No plans yet. Ask JARVIS to create a meeting.") }
        items(plans) { plan ->
            PlanCard(plan, planItems.filter { it.planId == plan.id }, reminders)
        }
    }
}

@Composable
private fun PlanCard(plan: Plan, items: List<com.jarvis.data.PlanItem>, reminders: List<com.jarvis.data.Reminder>) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Text(plan.goalText, style = MaterialTheme.typography.titleMedium)
        Text("Status: ${plan.status}")
        Text("${items.size} linked PlanItems")
        items.forEach { item ->
            val reminderStatus = reminders.firstOrNull { it.id == item.refId }?.status
            Text("${item.itemType}: ${item.status}${reminderStatus?.let { " (reminder $it)" } ?: ""}")
        }
    } }
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
