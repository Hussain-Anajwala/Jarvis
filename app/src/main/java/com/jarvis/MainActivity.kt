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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
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
import com.jarvis.ui.JarvisSpacing
import com.jarvis.ui.JarvisTheme
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BackgroundEngine.schedule(this)
        // Re-arms the single daily briefing if the user enabled it (REPLACE keeps exactly one pending).
        com.jarvis.core.BriefingScheduler.reschedule(this)
        NotificationPublisher.createChannels(this)
        lifecycleScope.launch { HabitRoutineEngine.seedAndDetect(this@MainActivity) }
        handleListenIntent(intent)
        setContent { JarvisTheme { JarvisApp() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleListenIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        com.jarvis.core.AppVisibility.visible = true
        // A microphone foreground service may only be started while JARVIS is visible.
        if (com.jarvis.core.WakeWordSettings.enabled(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            com.jarvis.core.WakeWordService.start(this)
        }
    }

    override fun onStop() {
        com.jarvis.core.AppVisibility.visible = false
        super.onStop()
    }

    private fun handleListenIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(com.jarvis.core.WakeWordService.EXTRA_START_LISTENING, false) == true) {
            com.jarvis.core.WakeWordEvents.requestListen()
        }
    }
}

@Composable
private fun JarvisApp(vm: com.jarvis.ui.JarvisViewModel = viewModel()) {
    JarvisShell(vm)
    if (vm.confirmCancel) {
            AlertDialog(
                onDismissRequest = vm::dismissCancellation,
                title = { Text("Cancel meeting?") },
                text = { Text(vm.cancellationDialogText) },
                confirmButton = { Button(onClick = vm::confirmCancellation) { Text("Cancel meeting") } },
                dismissButton = { TextButton(onClick = vm::dismissCancellation) { Text("Keep it") } }
            )
    }

    val gmailConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.onGmailConsentResult(result.data) }
    LaunchedEffect(vm.gmailConsentIntent) {
        vm.gmailConsentIntent?.let { pending ->
            gmailConsentLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(pending.intentSender).build())
        }
    }

    vm.emailSummaryCount?.let { count ->
        AlertDialog(
            onDismissRequest = vm::dismissEmailSummary,
            title = { Text("Summarize $count email(s)?") },
            text = {
                Text(
                    "${vm.emailSummaryDisclosure()} Sender names, subjects, and snippets are sent; email addresses " +
                        "and full bodies are not. Nothing is sent unless you tap Summarize."
                )
            },
            confirmButton = { Button(onClick = vm::confirmEmailSummary) { Text("Summarize") } },
            dismissButton = { TextButton(onClick = vm::dismissEmailSummary) { Text("Cancel") } }
        )
    }

    vm.emailDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = vm::dismissEmailDraft,
            title = { Text("Email draft") },
            text = {
                Column(
                    Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("To: ${draft.recipientName} (${draft.address})")
                    Text(if (draft.source == "template") "Drafted from your words (no cloud model was used)." else "Drafted by ${draft.source}. Edit before sending.")
                    OutlinedTextField(value = draft.subject, onValueChange = vm::updateEmailSubject, label = { Text("Subject") })
                    OutlinedTextField(value = draft.body, onValueChange = vm::updateEmailBody, label = { Text("Body") }, minLines = 4)
                    Text("Your mail app opens with this draft. JARVIS does not send it; you tap Send there.")
                }
            },
            confirmButton = { Button(onClick = vm::handoffEmailDraft) { Text("Open mail app") } },
            dismissButton = { TextButton(onClick = vm::dismissEmailDraft) { Text("Discard") } }
        )
    }

    vm.summaryConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::dismissSummary,
            title = { Text("Summarize ${pending.count} message(s)?") },
            text = {
                Text(
                    "${pending.disclosure} Counts and lists never leave the phone; only this summary request does. " +
                        "Nothing is sent unless you tap Summarize."
                )
            },
            confirmButton = { Button(onClick = vm::confirmSummary) { Text("Summarize") } },
            dismissButton = { TextButton(onClick = vm::dismissSummary) { Text("Cancel") } }
        )
    }

    vm.callConfirmationContact?.let { contact ->
        AlertDialog(
            onDismissRequest = vm::dismissCallConfirmation,
            title = { Text("Call ${contact.displayName}?") },
            text = {
                Text("JARVIS resolved this contact locally. Tapping Call will place a phone call to ${contact.displayName}.")
            },
            confirmButton = {
                Button(onClick = vm::confirmCallContact) { Text("Call") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissCallConfirmation) { Text("Cancel") }
            }
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

private fun Long.asTraceTime(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(this))

private fun Long.asPlanTimestamp(): String =
    SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault()).format(Date(this))

@Composable
internal fun PlansScreen(vm: com.jarvis.ui.JarvisViewModel, modifier: Modifier) {
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
        if (plans.isNotEmpty()) item {
            Text("Active", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
            if (activePlans.isEmpty()) Text("Nothing active right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(activePlans, key = { it.id }) { plan ->
            PlanCard(plan, planItems.filter { it.planId == plan.id }, reminders, vm)
        }
        if (inactivePlans.isNotEmpty()) item {
            Text("Completed / Cancelled", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
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
            containerColor = if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) { Column(Modifier.padding(JarvisSpacing.Section), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.Compact)) {
        Text(plan.createdAt.asPlanTimestamp(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(
            plan.goalText,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "Status: ${plan.status.replaceFirstChar { it.uppercase() }}",
            color = when {
                active -> MaterialTheme.colorScheme.primary
                cancelled -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.labelLarge
        )
        if (items.isNotEmpty()) Text(
            if (items.size == 1) "1 linked item" else "${items.size} linked items",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        items.forEach { item ->
            val reminderStatus = reminders.firstOrNull { it.id == item.refId }?.status
            val label = item.itemType.replace('_', ' ').replaceFirstChar { it.uppercase() }
            val detail = reminderStatus?.takeIf { it != item.status }?.let { " (reminder $it)" } ?: ""
            Text("• $label: ${item.status}$detail")
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
internal fun DecisionHistoryScreen(vm: com.jarvis.ui.JarvisViewModel, modifier: Modifier) {
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
                    FilterChip(selected = risk == riskFilter, onClick = { riskFilter = risk }, label = { Text(risk.replaceFirstChar { it.uppercase() }) })
                }
            }
        }
        item {
            Text("Plan", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.Compact)
            ) {
                FilterChip(selected = planFilter == null, onClick = { planFilter = null }, label = { Text("All") })
                plans.forEach { plan ->
                    FilterChip(
                        selected = planFilter == plan.id, onClick = { planFilter = plan.id },
                        label = { Text(plan.goalText, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) }
                    )
                }
            }
        }
        if (filtered.isEmpty()) item { Text("No decision trace entries match these filters.") }
        items(filtered) { trace ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(JarvisSpacing.Section), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.Compact)) {
                    Text(trace.actionSummary, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${trace.modelName} · ${trace.riskLevel} risk · ${trace.outcome} · ${trace.createdAt.asTraceTime()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Tool: ${trace.toolName}")
                    val planTitle = trace.planId?.let { id -> plans.firstOrNull { it.id == id }?.goalText }
                    Text(
                        "Plan: ${planTitle ?: if (trace.planId == null) "Background / unlinked" else "Plan unavailable"}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
internal fun AutomationScreen(vm: com.jarvis.ui.JarvisViewModel, modifier: Modifier) {
    val rules by vm.automationRules.collectAsState()
    LazyColumn(
        modifier.fillMaxSize().padding(JarvisSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.List)
    ) {
        item { Text("Automations", style = MaterialTheme.typography.headlineMedium) }
        item { BriefingCard(vm) }
        if (rules.isEmpty()) item { Text("No routine suggestions yet.") }
        items(rules, key = { it.id }) { rule ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
internal fun PermissionScreen(vm: com.jarvis.ui.JarvisViewModel, modifier: Modifier, scrollable: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionLayer = remember { PermissionLayer(context) }
    var requested by remember { mutableStateOf<String?>(null) }
    var resumeCount by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        resumeCount++
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { requested = null }
    Column(
        (if (scrollable) modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(JarvisSpacing.Screen) else modifier),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.List)
    ) {
        Text("Connections and permissions", style = MaterialTheme.typography.titleLarge)
        Text("JARVIS only uses capabilities you explicitly grant.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Capability.entries.forEach { capability ->
            val granted = remember(resumeCount) { permissionLayer.isGranted(capability) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
        NotificationAccessCard(vm, resumeCount)
        GmailCard(vm)
    }
}

@Composable
internal fun GmailCard(vm: com.jarvis.ui.JarvisViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(JarvisSpacing.Section), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gmail (read-only)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Lets JARVIS count unread emails and list today's sender, subject, and snippet. Uses the gmail.readonly " +
                    "permission only: JARVIS cannot send, delete, or change email. Email bodies and tokens are not stored."
            )
            Text(if (vm.gmailConnected) "Connected (this session)" else "Not connected")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.gmailConnected) {
                    OutlinedButton(onClick = vm::disconnectGmail) { Text("Disconnect") }
                } else {
                    OutlinedButton(onClick = vm::connectGmail) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
internal fun NotificationAccessCard(vm: com.jarvis.ui.JarvisViewModel, resumeCount: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val enabled = remember(resumeCount) { com.jarvis.core.JarvisNotificationListener.isAccessEnabled(context) }
    val stored by vm.storedNotificationCount.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(JarvisSpacing.Section), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Notification access", style = MaterialTheme.typography.titleMedium)
            Text(
                "Lets JARVIS answer \"any new messages?\" from message notifications. Sender and a short snippet are kept " +
                    "on this phone for 48 hours. Only messages that arrive while access is on are seen. Text is sent to a " +
                    "cloud model only when you ask for a summary and confirm."
            )
            Text(if (enabled) "Granted" else "Not granted")
            Text("Stored message notifications: $stored")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text(if (enabled) "Review" else "Open settings") }
                OutlinedButton(onClick = { confirmClear = true }, enabled = stored > 0) { Text("Clear data") }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear stored notifications?") },
            text = { Text("This deletes all $stored message notifications JARVIS stored on this phone. It cannot be undone.") },
            confirmButton = { Button(onClick = { vm.clearNotificationData(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } }
        )
    }
}

@Composable
internal fun MemoryScreen(vm: com.jarvis.ui.JarvisViewModel, modifier: Modifier) {
    val memories by vm.memories.collectAsState()
    var newMemory by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<com.jarvis.data.Memory?>(null) }
    var editText by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier.fillMaxSize().padding(JarvisSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.List)
    ) {
        item {
            Text("Memory", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Stored only on this phone. When a request goes to a cloud model, up to 3 memories that share words " +
                    "with it are included and nothing else; each entry shows when it was last sent. Say \"remember that …\" " +
                    "or add one here."
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newMemory,
                    onValueChange = { newMemory = it.take(300) },
                    label = { Text("Add a memory") },
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { vm.addMemory(newMemory); newMemory = "" }, enabled = newMemory.isNotBlank()) { Text("Add") }
            }
        }
        if (memories.isEmpty()) item { Text("No memories yet.") }
        items(memories.size) { index ->
            val memory = memories[index]
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(JarvisSpacing.Section), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        memory.type.replaceFirstChar(Char::uppercase) + memory.memoryKey.takeIf(String::isNotBlank)?.let { " · ${it.replace('_', ' ')}" }.orEmpty(),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(memory.contentText, style = MaterialTheme.typography.titleMedium)
                    Text("Source: ${memory.sourceRef} · saved ${memory.updatedAt.coerceAtLeast(memory.createdAt).asPlanTimestamp()}")
                    Text(memory.lastUsedAt?.let { "Last sent with a request: ${it.asPlanTimestamp()}" } ?: "Never sent to a cloud model")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { editing = memory; editText = memory.contentText }) { Text("Edit") }
                        OutlinedButton(onClick = { vm.deleteMemory(memory.id) }) { Text("Delete") }
                    }
                }
            }
        }
        if (memories.isNotEmpty()) item {
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear all memories") }
        }
    }
    editing?.let { memory ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit memory") },
            text = { OutlinedTextField(value = editText, onValueChange = { editText = it.take(300) }) },
            confirmButton = { Button(onClick = { vm.editMemory(memory.id, editText); editing = null }, enabled = editText.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all memories?") },
            text = { Text("High-risk action: this permanently deletes all ${memories.size} memories JARVIS stored on this phone, including your favorite song, name, and email sign-off.") },
            confirmButton = { Button(onClick = { vm.clearMemories(); confirmClear = false }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } }
        )
    }
}

@Composable
internal fun BriefingCard(vm: com.jarvis.ui.JarvisViewModel) {
    val s = vm.briefingSettings
    val context = androidx.compose.ui.platform.LocalContext.current
    var notificationsOn by remember {
        mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsOn = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(JarvisSpacing.Section), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Daily briefing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = s.enabled, onCheckedChange = { enable ->
                    vm.updateBriefingSettings(s.copy(enabled = enable))
                    // The briefing arrives as a notification; ask for that permission when turning it on.
                    if (enable && !notificationsOn && android.os.Build.VERSION.SDK_INT >= 33) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                })
            }
            if (s.enabled && !notificationsOn) {
                Text("Notifications are off for JARVIS, so the scheduled briefing can't appear. Allow them in Permissions → Notifications.", color = MaterialTheme.colorScheme.error)
            }
            Text(
                "A notification once a day, assembled on this phone without any AI model. Off by default; " +
                    "each section below is your choice. Say \"good morning\" for one now."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Time: %02d:%02d".format(s.hour, s.minute))
                OutlinedButton(onClick = { vm.updateBriefingSettings(s.copy(hour = (s.hour + 23) % 24)) }) { Text("−1 h") }
                OutlinedButton(onClick = { vm.updateBriefingSettings(s.copy(hour = (s.hour + 1) % 24)) }) { Text("+1 h") }
                OutlinedButton(onClick = { vm.updateBriefingSettings(s.copy(minute = (s.minute + 15) % 60)) }) { Text("+15 m") }
            }
            listOf(
                Triple("Calendar", s.calendar) { v: Boolean -> s.copy(calendar = v) },
                Triple("Travel to next meeting", s.travel) { v: Boolean -> s.copy(travel = v) },
                Triple("Weather", s.weather) { v: Boolean -> s.copy(weather = v) },
                Triple("Open tasks", s.tasks) { v: Boolean -> s.copy(tasks = v) },
                Triple("Unread email count (Gmail)", s.unreadEmail) { v: Boolean -> s.copy(unreadEmail = v) },
                Triple("New message count (notifications)", s.newMessages) { v: Boolean -> s.copy(newMessages = v) }
            ).forEach { (label, checked, update) ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = { vm.updateBriefingSettings(update(it)) })
                    Text(label)
                }
            }
            OutlinedButton(onClick = vm::previewBriefing) { Text("Preview now") }
            vm.lastBriefing?.let { Text("Last: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun RoutinesSection(vm: com.jarvis.ui.JarvisViewModel) {
    val rules by vm.automationRules.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(JarvisSpacing.List)) {
        Text("Routines", style = MaterialTheme.typography.titleLarge)
        if (rules.isEmpty()) Text("No routine suggestions yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        rules.forEach { rule ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(JarvisSpacing.Section), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.Compact)) {
                    Text(rule.patternDescription, style = MaterialTheme.typography.titleMedium)
                    Text("Status: ${rule.status}", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
