package com.jarvis

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jarvis.core.Capability
import com.jarvis.core.CommunicationRequestParser
import com.jarvis.core.ElevenLabsTts
import com.jarvis.core.MeetingRequestParser
import com.jarvis.core.PermissionLayer
import com.jarvis.core.SpeechPhase
import com.jarvis.core.SpeechProvider
import com.jarvis.core.VoicePolicy
import com.jarvis.core.VoiceStart
import com.jarvis.ui.ChatMessage
import com.jarvis.ui.JarvisSpacing
import com.jarvis.ui.JarvisViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** Microphone glyph (Material "mic" path) — the core icon set does not include it. */
private val MicIcon: ImageVector = ImageVector.Builder("Mic", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 14f); curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f); verticalLineTo(5f)
        curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f); curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f); verticalLineTo(11f)
        curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f); close()
        moveTo(17.3f, 11f); curveTo(17.3f, 14f, 14.76f, 16.1f, 12f, 16.1f); curveTo(9.24f, 16.1f, 6.7f, 14f, 6.7f, 11f)
        horizontalLineTo(5f); curveTo(5f, 14.41f, 7.72f, 17.23f, 11f, 17.72f); verticalLineTo(21f); horizontalLineTo(13f)
        verticalLineTo(17.72f); curveTo(16.28f, 17.24f, 19f, 14.42f, 19f, 11f); horizontalLineTo(17.3f); close()
    }
}.build()

private enum class Tab(val label: String, val icon: ImageVector) {
    ASSISTANT("Assistant", Icons.Filled.Home),
    PLANS("Plans", Icons.Filled.DateRange),
    MEMORY("Memory", Icons.Filled.Star),
    ACTIVITY("Activity", Icons.AutoMirrored.Filled.List),
    SETTINGS("Settings", Icons.Filled.Settings)
}

/** Five destinations with icons; the old Home/History/Automations/Permissions screens are reorganised here. */
@Composable
internal fun JarvisShell(vm: JarvisViewModel) {
    var tab by remember { mutableStateOf(Tab.ASSISTANT) }
    androidx.compose.material3.Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label, maxLines = 1) }
                    )
                }
            }
        }
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.ASSISTANT -> AssistantScreen(vm, modifier)
            Tab.PLANS -> PlansScreen(vm, modifier)
            Tab.MEMORY -> MemoryScreen(vm, modifier)
            Tab.ACTIVITY -> DecisionHistoryScreen(vm, modifier)
            Tab.SETTINGS -> SettingsScreen(vm, modifier)
        }
    }
}

private enum class AssistantState(val label: String) { READY("Ready"), THINKING("Thinking…"), LISTENING("Listening…"), SPEAKING("Speaking"), OFFLINE("Offline fallback") }

@Composable
private fun StatusChip(state: AssistantState, wakeWordOn: Boolean) {
    val color = when (state) {
        AssistantState.READY -> MaterialTheme.colorScheme.onSurfaceVariant
        AssistantState.OFFLINE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = RoundedCornerShape(50),
            color = color.copy(alpha = 0.12f),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.size(6.dp))
                Text(state.label, color = color, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (wakeWordOn) {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(MicIcon, contentDescription = null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(4.dp))
                    Text("Hey JARVIS on", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onReplay: (() -> Unit)?) {
    val mine = message.fromUser
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp
            ),
            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp).semantics {
                contentDescription = (if (mine) "You said: " else "JARVIS replied: ") + message.text
            }
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    message.text,
                    color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.time)),
                        color = (if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (onReplay != null) {
                        TextButton(onClick = onReplay) { Text("Read aloud", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

/** Main conversation: transcript, live status, voice controls, and a composer at the bottom. */
@Composable
internal fun AssistantScreen(vm: JarvisViewModel, modifier: Modifier) {
    var input by remember { mutableStateOf("") }
    var pendingCommunicationRequest by remember { mutableStateOf<String?>(null) }
    var pendingCalendarRequest by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val permissionLayer = remember(context) { PermissionLayer(context) }
    val phonePermission = vm.phonePermissionRequest
    val phonePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        phonePermission?.let { vm.onPhonePermissionResult(it, granted) }
    }
    LaunchedEffect(phonePermission) { phonePermission?.let(phonePermissionLauncher::launch) }
    val contactsPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingCommunicationRequest
        pendingCommunicationRequest = null
        if (granted && pending != null) vm.submit(pending) else if (!granted) vm.reportContactsPermissionDenied()
    }
    val calendarPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val pending = pendingCalendarRequest
        pendingCalendarRequest = null
        if (pending != null) vm.submit(pending)
    }
    val voiceInput = remember(context) {
        com.jarvis.ui.VoiceInput(context, vm::onListeningChanged, vm::onVoiceTranscript, vm::onVoiceProblem)
    }
    DisposableEffect(voiceInput) { onDispose { voiceInput.stop() } }
    val speechStatus by vm.speechStatus.collectAsState()
    val wakeListening by vm.wakeWordListening.collectAsState()
    val pendingListen by vm.wakeWordPendingListen.collectAsState()

    // Not a LaunchedEffect keyed on a flag: resetting the flag would cancel the effect during its delay.
    val captureScope = rememberCoroutineScope()
    fun startCapture() {
        captureScope.launch {
            vm.beginVoiceCapture()
            kotlinx.coroutines.delay(400) // let the wake-word listener release the microphone first
            voiceInput.start()
        }
    }
    LaunchedEffect(pendingListen) {
        // "Hey JARVIS" was heard (or its notification tapped): capture one command with the normal voice flow.
        if (pendingListen && com.jarvis.core.WakeWordEvents.consumeListenRequest() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            voiceInput.isAvailable
        ) startCapture()
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when (val start = VoicePolicy.onPermissionResult(granted)) {
            VoiceStart.Listen -> startCapture()
            is VoiceStart.Unavailable -> vm.onVoiceProblem(start.message)
            VoiceStart.RequestPermission -> Unit
        }
    }

    fun send(text: String) {
        val request = text.trim()
        if (request.isEmpty()) return
        input = ""
        if (CommunicationRequestParser.isCommunicationIntent(request) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCommunicationRequest = request
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        } else if (MeetingRequestParser.isMeetingIntent(request) &&
            !Regex("""\b(cancel|delete|remove)\b""", RegexOption.IGNORE_CASE).containsMatchIn(request) &&
            !permissionLayer.isGranted(Capability.CALENDAR)
        ) {
            pendingCalendarRequest = request
            calendarPermissionLauncher.launch(Capability.CALENDAR.permissions)
        } else {
            vm.submit(request)
        }
    }

    val state = when {
        vm.listening -> AssistantState.LISTENING
        vm.thinking -> AssistantState.THINKING
        speechStatus.phase == SpeechPhase.PREPARING || speechStatus.phase == SpeechPhase.SPEAKING -> AssistantState.SPEAKING
        vm.engineLabel.startsWith("Offline fallback") -> AssistantState.OFFLINE
        else -> AssistantState.READY
    }
    val listState = rememberLazyListState()
    val conversation = vm.conversation
    LaunchedEffect(conversation.size, vm.thinking) {
        if (conversation.isNotEmpty()) listState.animateScrollToItem(conversation.size)
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Column(Modifier.padding(horizontal = JarvisSpacing.Screen, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("JARVIS", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                StatusChip(state, wakeListening)
            }
            Text("Engine: ${vm.engineLabel}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = JarvisSpacing.Screen, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (conversation.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("How can I help?", style = MaterialTheme.typography.headlineSmall)
                        Text(vm.reply, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Good morning", "What's the weather", "Any new messages?", "Set a timer for 5 minutes", "Play Kesariya by Arijit Singh")
                                .forEach { suggestion -> AssistChip(onClick = { send(suggestion) }, label = { Text(suggestion) }) }
                        }
                    }
                }
            }
            items(conversation, key = { it.id }) { message ->
                val replay = if (!message.fromUser && message.id == vm.lastAnswer?.id) ({ vm.replayLastAnswer() }) else null
                MessageBubble(message, replay)
            }
            if (vm.thinking) item { Text("JARVIS is thinking…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        }
        // Live voice banners: what the microphone and speaker are doing right now.
        if (vm.listening) {
            VoiceBanner("Listening… speak now", "Stop") { voiceInput.stop() }
        } else if (state == AssistantState.SPEAKING) {
            VoiceBanner("Speaking with ${speechStatus.engine.orEmpty()}", "Stop") { vm.stopSpeaking() }
        }
        speechStatus.detail?.let {
            Text(it, Modifier.padding(horizontal = JarvisSpacing.Screen), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask JARVIS…") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4
            )
            FilledIconButton(
                onClick = {
                    if (vm.listening) voiceInput.stop() else {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        when (val start = VoicePolicy.onMicTapped(granted, voiceInput.isAvailable, permanentlyDenied = false)) {
                            VoiceStart.Listen -> startCapture()
                            VoiceStart.RequestPermission -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            is VoiceStart.Unavailable -> vm.onVoiceProblem(start.message)
                        }
                    }
                },
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (vm.listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (vm.listening) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(if (vm.listening) Icons.Filled.Close else MicIcon, contentDescription = if (vm.listening) "Stop listening" else "Speak")
            }
            FilledIconButton(onClick = { send(input) }, enabled = input.isNotBlank(), modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun VoiceBanner(text: String, action: String, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = JarvisSpacing.Screen, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.onPrimaryContainer)
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(JarvisSpacing.Section), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A key field that never shows the stored value; it only says whether one is saved. */
@Composable
private fun SecretField(label: String, configured: Boolean, onSave: (String?) -> Unit) {
    var value by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label: ${if (configured) "saved on this phone" else "not set"}", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = value, onValueChange = { value = it.trim() }, modifier = Modifier.weight(1f),
                placeholder = { Text("Paste $label") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Button(onClick = { onSave(value); value = "" }, enabled = value.isNotBlank()) { Text("Save") }
        }
        if (configured) TextButton(onClick = { onSave(null) }) { Text("Remove $label") }
    }
}

@Composable
internal fun SettingsScreen(vm: JarvisViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val s = vm.speechSettings
    var voiceIdDraft by remember(s.voiceId) { mutableStateOf(s.voiceId) }
    val wakeListening by vm.wakeWordListening.collectAsState()
    var micBlocked by remember { mutableStateOf(false) }
    val wakeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        // After repeated denials Android no longer shows the dialog; say so instead of silently doing nothing.
        if (results[Manifest.permission.RECORD_AUDIO] == true) { micBlocked = false; vm.updateWakeWord(true) } else micBlocked = true
    }
    LazyColumn(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(JarvisSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.List)
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }
        item {
            SectionCard("Voice replies", "JARVIS reads each new answer once. Your own words, placeholders, and old messages are never read.") {
                SettingRow("Speak replies automatically", s.autoSpeak) { vm.updateSpeechSettings(s.copy(autoSpeak = it)) }
                Text("Voice", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(SpeechProvider.SYSTEM to "Phone voice", SpeechProvider.ELEVENLABS to "ElevenLabs").forEach { (provider, label) ->
                        val selected = s.provider == provider
                        if (selected) Button(onClick = {}) { Text(label) }
                        else OutlinedButton(onClick = { vm.updateSpeechSettings(s.copy(provider = provider)) }) { Text(label) }
                    }
                }
                if (s.provider == SpeechProvider.ELEVENLABS) {
                    Text(
                        "Answer text is sent to ElevenLabs to make audio. Answers built from your messages, email, or memories are always read with the phone voice instead. If ElevenLabs fails, the phone voice takes over.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SecretField("ElevenLabs API key", vm.elevenLabsConfigured, vm::setElevenLabsKey)
                    Text(
                        "Stored encrypted with Android Keystore. A key used by an app can still be extracted by someone who controls the phone — use a key you can revoke.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = voiceIdDraft, onValueChange = { voiceIdDraft = it.trim() },
                        label = { Text("Voice ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Default: George (${ElevenLabsTts.DEFAULT_VOICE_ID}), from ElevenLabs' quickstart") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.updateSpeechSettings(s.copy(voiceId = voiceIdDraft)) }, enabled = voiceIdDraft != s.voiceId) { Text("Use voice ID") }
                        TextButton(onClick = { voiceIdDraft = ElevenLabsTts.DEFAULT_VOICE_ID; vm.updateSpeechSettings(s.copy(voiceId = ElevenLabsTts.DEFAULT_VOICE_ID)) }) { Text("Reset") }
                    }
                }
                Text("Speed: %.1f×".format(s.rate), style = MaterialTheme.typography.labelLarge)
                Slider(value = s.rate, onValueChange = { vm.updateSpeechSettings(s.copy(rate = (it * 10).toInt() / 10f)) }, valueRange = 0.7f..1.2f, steps = 4)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::testVoice) { Text("Test voice") }
                    TextButton(onClick = vm::stopSpeaking) { Text("Stop speaking") }
                }
            }
        }
        item {
            SectionCard(
                "“Hey JARVIS” wake word",
                "Off by default. When on, the microphone listens for the phrase on this phone only (nothing is recorded or uploaded), a notification stays visible, and it pauses while JARVIS speaks or takes a command. Android needs JARVIS open to turn it on; after a restart, open JARVIS once."
            ) {
                SettingRow("Listen for “Hey JARVIS”", vm.wakeWordEnabled) { on ->
                    if (!on) vm.updateWakeWord(false)
                    else {
                        val needed = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) vm.updateWakeWord(true)
                        else wakeLauncher.launch(needed.toTypedArray())
                    }
                }
                if (micBlocked && !vm.wakeWordEnabled) {
                    Text("Microphone permission is off for JARVIS, so it can't listen. Allow it in Android settings → Permissions → Microphone.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text("Open app permissions") }
                }
                if (vm.wakeWordEnabled) Text(if (wakeListening) "Microphone is listening for the wake word." else "Paused (JARVIS is speaking, listening, or starting).",
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            SectionCard(
                "Travel and live traffic",
                "Without a traffic provider, travel time is road-network time from OSRM and is labelled “no traffic data”. Add a Google Routes API key (traffic-aware, billed by Google) or a Mapbox token (driving-traffic) to get live-traffic ETAs."
            ) {
                SecretField("Google Routes API key", vm.googleRoutesConfigured) { vm.setTrafficKey(com.jarvis.core.SecretStore.GOOGLE_ROUTES_API_KEY, it) }
                SecretField("Mapbox access token", vm.mapboxConfigured) { vm.setTrafficKey(com.jarvis.core.SecretStore.MAPBOX_ACCESS_TOKEN, it) }
                OutlinedButton(onClick = vm::recheckTravel) { Text("Re-check travel now") }
            }
        }
        item { BriefingCard(vm) }
        item { RoutinesSection(vm) }
        item { PermissionScreen(vm, Modifier) }
    }
}
