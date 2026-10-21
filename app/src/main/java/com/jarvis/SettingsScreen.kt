package com.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jarvis.core.ApiCredentials
import com.jarvis.core.CapturePhase
import com.jarvis.core.ElevenLabsVoices
import com.jarvis.core.SpeechPhase
import com.jarvis.core.SpeechProvider
import com.jarvis.core.VoiceCheck
import com.jarvis.ui.JarvisSpacing
import com.jarvis.ui.JarvisViewModel
import com.jarvis.ui.ThemeMode
import com.jarvis.ui.ThemePreference

/** Every setting here changes real behaviour; nothing is a placeholder. */
@Composable
internal fun SettingsScreen(vm: JarvisViewModel, modifier: Modifier) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(JarvisSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.List)
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }
        item { AppearanceSection() }
        item { VoiceSection(vm) }
        item { WakeWordSection(vm) }
        item { PrivacySection(vm) }
        item { TimersSection() }
        item { TravelSection(vm) }
        item { BriefingCard(vm) }
        item { RoutinesSection(vm) }
        item { PermissionScreen(vm, Modifier) }
        item {
            SectionCard("Research dashboard", "Measured results of this project, how each was obtained, and CSV/JSON export.") {
                OutlinedButton(onClick = vm::openResearch) { Text("Open research dashboard") }
            }
        }
        item { AboutSection() }
    }
}

@Composable
private fun AppearanceSection() {
    val context = LocalContext.current
    val mode by ThemePreference.mode.collectAsState()
    SectionCard("Appearance", "Follow Android's light/dark setting, or choose one.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
            ThemeMode.entries.forEach { option ->
                FilterChip(
                    selected = mode == option,
                    onClick = { ThemePreference.set(context, option) },
                    label = { Text(option.label) }
                )
            }
        }
    }
}

@Composable
private fun VoiceSection(vm: JarvisViewModel) {
    val s = vm.speechSettings
    val status by vm.speechStatus.collectAsState()
    LaunchedEffect(vm.elevenLabsConfigured) { vm.checkVoices() }
    SectionCard("Voice replies", "JARVIS reads each new answer once. Your own words, placeholders, and old messages are never read.") {
        SettingRow("Speak replies automatically", s.autoSpeak) { vm.updateSpeechSettings(s.copy(autoSpeak = it)) }
        val hands = vm.handsFree
        SettingRow("Keep listening after answers", hands.enabled) { vm.updateHandsFree(hands.copy(enabled = it)) }
        Text(
            "After a spoken answer in a voice conversation, JARVIS listens once more while it is on screen. The panel shows " +
                "\"Still listening…\" with Stop; if you say nothing for ${hands.windowSeconds} s it stops. Needs microphone permission.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (hands.enabled) {
            var seconds by remember(hands.windowSeconds) { androidx.compose.runtime.mutableFloatStateOf(hands.windowSeconds.toFloat()) }
            Text("Listening window: ${seconds.toInt()} s", style = MaterialTheme.typography.labelMedium)
            androidx.compose.material3.Slider(
                value = seconds,
                onValueChange = { seconds = it },
                onValueChangeFinished = { vm.updateHandsFree(hands.copy(windowSeconds = seconds.toInt())) },
                valueRange = com.jarvis.core.HandsFree.MIN_SECONDS.toFloat()..com.jarvis.core.HandsFree.MAX_SECONDS.toFloat(),
                steps = com.jarvis.core.HandsFree.MAX_SECONDS - com.jarvis.core.HandsFree.MIN_SECONDS - 1
            )
        }
        Text("Voice", style = MaterialTheme.typography.labelLarge)
        Column(Modifier.selectableGroup()) {
            VoiceRow(
                name = "Phone voice",
                detail = "Android's built-in voice. Works offline and is always the fallback.",
                selected = s.provider == SpeechProvider.SYSTEM,
                enabled = true
            ) { vm.updateSpeechSettings(s.copy(provider = SpeechProvider.SYSTEM)) }
            ElevenLabsVoices.choices.forEach { voice ->
                val check = vm.voiceChecks[voice.id]
                val (detail, usable) = when {
                    !vm.elevenLabsConfigured -> "Needs an ElevenLabs key in this build (see below)." to false
                    check is VoiceCheck.Available -> (check.description.ifBlank { "Available on your ElevenLabs account" }) to true
                    check is VoiceCheck.Missing -> "Not on your ElevenLabs account." to false
                    check is VoiceCheck.Unknown -> "Couldn't confirm (${check.reason}); you can still try it." to true
                    else -> (if (vm.voiceCheckRunning) "Checking your account…" else "Not checked yet.") to true
                }
                VoiceRow(
                    name = voice.name,
                    detail = detail,
                    selected = s.provider == SpeechProvider.ELEVENLABS && s.voiceId == voice.id,
                    enabled = usable
                ) { vm.updateSpeechSettings(s.copy(provider = SpeechProvider.ELEVENLABS, voiceId = voice.id)) }
            }
        }
        Text(
            when (vm.elevenLabsSource) {
                ApiCredentials.Source.BUILD -> "ElevenLabs is set up in this build."
                ApiCredentials.Source.SAVED_ON_PHONE -> "ElevenLabs uses a key saved on this phone by an older version."
                ApiCredentials.Source.NONE ->
                    "ElevenLabs isn't set up in this build, so the phone voice is used. To enable it, add ELEVENLABS_API_KEY to local.properties and rebuild."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (vm.elevenLabsSource == ApiCredentials.Source.SAVED_ON_PHONE) {
            TextButton(onClick = vm::removeSavedElevenLabsKey) { Text("Forget the saved key") }
        }
        if (s.provider == SpeechProvider.ELEVENLABS) {
            SettingRow("Use this voice for message and email content too", s.cloudForPrivate) { vm.updateSpeechSettings(s.copy(cloudForPrivate = it)) }
            Text(
                "Only the text of an answer is sent to ElevenLabs to make audio. " +
                    (if (s.cloudForPrivate) "This includes answers that quote your messages or email."
                    else "Answers that quote your messages or email are read with the phone voice, so their text never goes to ElevenLabs.") +
                    " If ElevenLabs is unavailable, the whole answer is read with the phone voice and the reason is shown here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("Speed: %.1f×".format(s.rate), style = MaterialTheme.typography.labelLarge)
        Slider(value = s.rate, onValueChange = { vm.updateSpeechSettings(s.copy(rate = (it * 10).toInt() / 10f)) }, valueRange = 0.7f..1.2f, steps = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::testVoice) { Text("Test voice") }
            TextButton(onClick = vm::stopSpeaking) { Text("Stop speaking") }
        }
        val line = when (status.phase) {
            SpeechPhase.PREPARING, SpeechPhase.SPEAKING -> "Speaking with ${status.engine.orEmpty()}…"
            SpeechPhase.FAILED -> "Couldn't speak: ${status.detail ?: "unknown error"}"
            else -> status.detail
        }
        if (line != null) {
            Text(
                line,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.phase == SpeechPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VoiceRow(name: String, detail: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WakeWordSection(vm: JarvisViewModel) {
    val context = LocalContext.current
    val listening by vm.wakeWordListening.collectAsState()
    val running by vm.wakeServiceRunning.collectAsState()
    val capture by vm.wakeCapture.collectAsState()
    var micBlocked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        // After repeated denials Android no longer shows the dialog; say so instead of silently doing nothing.
        if (results[Manifest.permission.RECORD_AUDIO] == true) { micBlocked = false; vm.updateWakeWord(true) } else micBlocked = true
    }
    SectionCard(
        "“Hey JARVIS” wake word",
        "Off by default. When on, the microphone listens for the phrase on this phone only (nothing is recorded or uploaded) and a notification stays visible. After the phrase JARVIS beeps, listens for your request, runs it, and answers aloud."
    ) {
        SettingRow("Listen for “Hey JARVIS”", vm.wakeWordEnabled) { on ->
            if (!on) vm.updateWakeWord(false)
            else {
                val needed = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) vm.updateWakeWord(true)
                else launcher.launch(needed.toTypedArray())
            }
        }
        if (micBlocked && !vm.wakeWordEnabled) {
            Text(
                "Microphone permission is off for JARVIS, so it can't listen. Allow it in Android settings → Permissions → Microphone.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(onClick = { openAppSettings(context) }) { Text("Open app permissions") }
        }
        if (vm.wakeWordEnabled) {
            val (text, isError) = when {
                capture == CapturePhase.LISTENING -> "Listening for your request…" to false
                listening -> "Listening for “Hey JARVIS”." to false
                !running -> "Not running. Android only lets this start while JARVIS is open — open JARVIS and it will resume." to true
                else -> "Paused while JARVIS speaks or takes a request." to false
            }
            Text(
                text,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            "When JARVIS isn't on screen it can answer questions, but Android doesn't let it open other apps (maps, music, clock) from the background; it will show a notification to tap instead.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PrivacySection(vm: JarvisViewModel) {
    SectionCard(
        "Privacy",
        "Passwords, PINs, codes, card and ID numbers never leave your phone. Message and email text goes to a cloud " +
            "model only after you confirm a summary. Each decision is listed in Activity, without the protected value."
    ) {
        SettingRow("Use cloud AI (Groq, then Gemini)", vm.cloudAiAllowed, vm::updateCloudAiAllowed)
        if (!vm.cloudAiAllowed) {
            Text(
                "Off: every request is answered on this phone. Phone commands, reminders, and clock requests still work; " +
                    "open questions and email wording are simpler.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TimersSection() {
    val context = LocalContext.current
    val engine = remember { com.jarvis.core.JarvisTimerEngine(context) }
    var own by remember { mutableStateOf(engine.enabled) }
    SectionCard(
        "Timers",
        "When JARVIS runs a timer itself, \"cancel the timer\" really cancels it. Android doesn't let any app cancel a Clock-app timer."
    ) {
        SettingRow("JARVIS runs timers (instead of the Clock app)", own) { engine.enabled = it; own = it }
    }
}

@Composable
private fun TravelSection(vm: JarvisViewModel) {
    val status = when {
        vm.googleRoutesConfigured -> "Live traffic: on (Google Routes, traffic-aware)."
        vm.mapboxConfigured -> "Live traffic: on (Mapbox driving-traffic)."
        else -> "Live traffic: off. Travel times are road-network time from OSRM and are labelled “no traffic data”."
    }
    SectionCard(
        "Travel and live traffic",
        "Travel times use your phone's location. Only a provider that returns real traffic is ever labelled live traffic."
    ) {
        Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (!vm.googleRoutesConfigured && !vm.mapboxConfigured) {
            Text(
                "To turn it on, add GOOGLE_ROUTES_API_KEY (billed by Google) or MAPBOX_ACCESS_TOKEN to local.properties and rebuild.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = vm::recheckTravel) { Text("Re-check travel now") }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    SectionCard("About and privacy", "JARVIS ${BuildConfig.VERSION_NAME}, a personal assistant that runs on this phone.") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "Your plans, memories, and message records are stored on this phone.",
                "Each request sends the minimum context to a cloud model (Groq, then Google Gemini if needed). Message and email text is sent only when you confirm a summary.",
                "Gmail is read-only. JARVIS never sends email or messages by itself; you press send.",
                "“Hey JARVIS” audio is processed on the phone and never uploaded. Spoken requests go through Android's speech recognition.",
                "Location coordinates go to the weather and routing services only to answer travel and weather questions."
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
        OutlinedButton(onClick = { openAppSettings(context) }) { Text("Open Android app settings") }
    }
}

private fun openAppSettings(context: android.content.Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
