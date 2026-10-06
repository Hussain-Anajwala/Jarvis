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
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.jarvis.core.MeetingRequestParser
import com.jarvis.ui.JarvisColors
import com.jarvis.ui.JarvisSpacing
import com.jarvis.ui.JarvisTheme
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BackgroundEngine.schedule(this)
        NotificationPublisher.createChannels(this)
        lifecycleScope.launch { HabitRoutineEngine.seedAndDetect(this@MainActivity) }
        window.statusBarColor = JarvisColors.Background.toArgb()
        window.navigationBarColor = JarvisColors.Background.toArgb()
        setContent { JarvisTheme { JarvisApp() } }
    }
}

@Composable
private fun JarvisApp(vm: com.jarvis.ui.JarvisViewModel = viewModel()) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        containerColor = JarvisColors.Background,
        bottomBar = {
            NavigationBar(
                containerColor = JarvisColors.Surface,
                contentColor = JarvisColors.TextPrimary,
                modifier = Modifier.border(
                    BorderStroke(1.dp, JarvisColors.Border),
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
            ) {
                listOf("Home", "Plans", "History", "Automations", "Permissions").forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {},
                        label = {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = JarvisColors.Primary,
                            selectedTextColor = JarvisColors.Primary,
                            indicatorColor = JarvisColors.Primary.copy(alpha = 0.12f),
                            unselectedIconColor = JarvisColors.TextTertiary,
                            unselectedTextColor = JarvisColors.TextSecondary
                        )
                    )
                }
            }
        }
    ) { padding ->
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
                text = { Text(vm.cancellationDialogText) },
                confirmButton = { Button(onClick = vm::confirmCancellation) { Text("Cancel meeting") } },
                dismissButton = { TextButton(onClick = vm::dismissCancellation) { Text("Keep it") } }
            )
    }

    vm.communicationDraft?.let { draft ->
            AlertDialog(
                onDismissRequest = vm::dismissCommunication,
                title = {
                    Text(
                        when {
                            vm.choosingCommunicationTarget -> "Choose an app"
                            vm.editingCommunicationDraft -> "Edit draft"
                            else -> "Send this draft?"
                        }
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("To: ${vm.communicationRecipient.orEmpty()}")
                        if (vm.choosingCommunicationTarget) {
                            Text("Draft: $draft")
                            Text("The selected app opens with this text ready for you to review. JARVIS will not send it.")
                        } else if (vm.editingCommunicationDraft) {
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
                    if (vm.choosingCommunicationTarget) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = vm::handoffCommunicationToMessages) { Text("Messages") }
                            OutlinedButton(onClick = vm::handoffCommunicationToWhatsApp) { Text("WhatsApp") }
                        }
                    } else {
                        Button(onClick = vm::beginCommunicationHandoff) {
                            Text(if (vm.editingCommunicationDraft) "Send" else "Choose app")
                        }
                    }
                },
                dismissButton = {
                    if (vm.choosingCommunicationTarget || vm.editingCommunicationDraft) {
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

@Composable
private fun HomeScreen(vm: com.jarvis.ui.JarvisViewModel, modifier: Modifier) {
    var input by remember { mutableStateOf("") }
    var pendingCommunicationRequest by remember { mutableStateOf<String?>(null) }
    var pendingCalendarRequest by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val permissionLayer = remember(context) { PermissionLayer(context) }
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
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val pendingRequest = pendingCalendarRequest
        pendingCalendarRequest = null
        if (pendingRequest != null) vm.submit(pendingRequest)
    }
    val traces by vm.traces.collectAsState()
    val pulse = rememberInfiniteTransition(label = "jarvis-pulse").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "jarvis-pulse-alpha"
    )
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().padding(JarvisSpacing.Screen),
            verticalArrangement = Arrangement.spacedBy(JarvisSpacing.List)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("JARVIS", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineLarge)
                Text("●", color = accent.copy(alpha = pulse.value), style = MaterialTheme.typography.headlineSmall)
            }
            Text("LOCAL-FIRST OPERATING SYSTEM", color = accent, style = MaterialTheme.typography.labelMedium)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = JarvisColors.SurfaceContainer),
                border = BorderStroke(1.dp, JarvisColors.Border)
            ) { Column(Modifier.padding(JarvisSpacing.Section)) {
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
            } else if (
                MeetingRequestParser.isMeetingIntent(request) &&
                MeetingRequestParser.parse(request) != null &&
                !Regex("""\b(cancel|delete|remove)\b""", RegexOption.IGNORE_CASE).containsMatchIn(request) &&
                !permissionLayer.isGranted(Capability.CALENDAR)
            ) {
                pendingCalendarRequest = request
                calendarPermissionLauncher.launch(Capability.CALENDAR.permissions)
            } else {
                vm.submit(request)
            }
        }, Modifier.fillMaxWidth()) { Text("Run request") }
        OutlinedButton(onClick = vm::recheckTravel, Modifier.fillMaxWidth()) {
            Text("Re-check travel now")
        }
            Text("Decision Trace", color = accent, style = MaterialTheme.typography.titleMedium)
            val latestTrace = traces.firstOrNull()
            Text(
                text = latestTrace?.actionSummary ?: "No actions recorded yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun Long.asTraceTime(): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(this))

private fun Long.asPlanTimestamp(): String =
    SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault()).format(Date(this))

@Composable
private fun PlansScreen(vm: com.jarvis.ui.JarvisViewModel, modifier: Modifier) {
    val plans by vm.plans.collectAsState()
    val planItems by vm.planItems.collectAsState()
    val reminders by vm.reminders.collectAsState()
    val activePlans = plans.filter { it.status == "active" }
    val inactivePlans = plans.filter { it.status != "active" }
    LazyColumn(
        modifier.fillMaxSize().padding(JarvisSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.List)
    ) {
        item { Text("Plans", style = MaterialTheme.typography.headlineMedium) }
        if (plans.isEmpty()) item { Text("No plans yet. Ask JARVIS to create a meeting.") }
        if (activePlans.isNotEmpty()) item {
            Text("Active", color = JarvisColors.Primary, style = MaterialTheme.typography.titleLarge)
        }
        items(activePlans, key = { it.id }) { plan ->
            PlanCard(plan, planItems.filter { it.planId == plan.id }, reminders, vm)
        }
        if (inactivePlans.isNotEmpty()) item {
            Text("Completed / Cancelled", color = JarvisColors.TextSecondary, style = MaterialTheme.typography.titleLarge)
        }
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
    val cancelled = plan.status.equals("cancelled", ignoreCase = true)
    val active = plan.status.equals("active", ignoreCase = true)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (active) JarvisColors.SurfaceContainer else JarvisColors.Surface
        ),
        border = BorderStroke(1.dp, JarvisColors.Border)
    ) { Column(Modifier.padding(JarvisSpacing.Section), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.Compact)) {
        Text(plan.createdAt.asPlanTimestamp(), color = JarvisColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
        Text(
            plan.goalText,
            color = if (active) JarvisColors.TextPrimary else JarvisColors.TextSecondary,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "Status: ${plan.status}",
            color = when {
                active -> JarvisColors.Primary
                cancelled -> JarvisColors.Error
                else -> JarvisColors.TextSecondary
            },
            style = MaterialTheme.typography.labelLarge
        )
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
    LazyColumn(
        modifier.fillMaxSize().padding(JarvisSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.List)
    ) {
        item {
            Text("Decision History", style = MaterialTheme.typography.headlineMedium)
            Text("Why JARVIS took each action, newest first.")
        }
        item {
            Text("Risk level", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.Compact)
            ) {
                listOf("all", "low", "medium", "high").forEach { risk ->
                    OutlinedButton(onClick = { riskFilter = risk }) {
                        Text(if (risk == riskFilter) "selected: $risk" else risk)
                    }
                }
            }
        }
        item {
            Text("Plan", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.Compact)
            ) {
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = JarvisColors.SurfaceContainer),
                border = BorderStroke(1.dp, JarvisColors.Border)
            ) {
                Column(Modifier.padding(JarvisSpacing.Section), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.Compact)) {
                    Text(trace.actionSummary, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${trace.riskLevel} risk · ${trace.outcome} · ${trace.createdAt.asTraceTime()}",
                        color = JarvisColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Tool: ${trace.toolName}")
                    val planTitle = trace.planId?.let { id -> plans.firstOrNull { it.id == id }?.goalText }
                    Text(
                        "Plan: ${planTitle ?: if (trace.planId == null) "Background / unlinked" else "Plan unavailable"}",
                        color = JarvisColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun AutomationScreen(vm: com.jarvis.ui.JarvisViewModel, modifier: Modifier) {
    val rules by vm.automationRules.collectAsState()
    LazyColumn(
        modifier.fillMaxSize().padding(JarvisSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.List)
    ) {
        item { Text("Automations", style = MaterialTheme.typography.headlineMedium) }
        if (rules.isEmpty()) item { Text("No routine suggestions yet.") }
        items(rules, key = { it.id }) { rule ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = JarvisColors.SurfaceContainer),
                border = BorderStroke(1.dp, JarvisColors.Border)
            ) {
                Column(Modifier.padding(JarvisSpacing.Section), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.Compact)) {
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
    Column(
        modifier.fillMaxSize().padding(JarvisSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.List)
    ) {
        Text("Permission Center", style = MaterialTheme.typography.headlineMedium)
        Text("JARVIS only uses capabilities you explicitly grant.")
        Capability.entries.forEach { capability ->
            val granted = permissionLayer.isGranted(capability)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = JarvisColors.SurfaceContainer),
                border = BorderStroke(1.dp, JarvisColors.Border)
            ) { Row(Modifier.padding(JarvisSpacing.Section), horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.Section)) {
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
