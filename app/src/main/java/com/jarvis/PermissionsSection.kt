package com.jarvis

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jarvis.core.Capability
import com.jarvis.ui.JarvisSpacing

/** What a permission card can honestly say and do right now. */
enum class PermissionState { GRANTED, PARTIAL, CAN_REQUEST, BLOCKED, NOT_NEEDED }

object PermissionStates {
    /**
     * [asked]: JARVIS asked before. Android stops showing the prompt after repeated denials; then
     * shouldShowRequestPermissionRationale is false and only Settings can grant it.
     */
    fun of(capability: Capability, granted: (String) -> Boolean, rationale: (String) -> Boolean, asked: Boolean, sdk: Int): PermissionState {
        if (capability == Capability.NOTIFICATIONS && sdk < 33) return PermissionState.NOT_NEEDED
        val results = capability.permissions.map(granted)
        return when {
            results.all { it } -> PermissionState.GRANTED
            // Approximate location is enough for weather and travel; precise can be added later.
            capability == Capability.LOCATION && granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> PermissionState.PARTIAL
            asked && capability.permissions.none(rationale) -> PermissionState.BLOCKED
            else -> PermissionState.CAN_REQUEST
        }
    }
}

@Composable
internal fun PermissionCard(capability: Capability, resumeCount: Int) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val prefs = remember(context) { context.getSharedPreferences("permission_requests", Context.MODE_PRIVATE) }
    var refresh by remember { mutableStateOf(0) }
    val state = remember(resumeCount, refresh) {
        PermissionStates.of(
            capability,
            granted = { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED },
            rationale = { perm -> activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, perm) } == true },
            asked = prefs.getBoolean(capability.name, false),
            sdk = Build.VERSION.SDK_INT
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        prefs.edit().putBoolean(capability.name, true).apply()
        refresh++
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(JarvisSpacing.Section), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(capability.label, style = MaterialTheme.typography.titleMedium)
            Text(capability.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                when (state) {
                    PermissionState.GRANTED -> "Allowed"
                    PermissionState.PARTIAL -> "Allowed (approximate location)"
                    PermissionState.CAN_REQUEST -> "Not allowed"
                    PermissionState.BLOCKED -> "Not allowed. Android won't show the request again after it was denied; allow it in Android settings → Permissions."
                    PermissionState.NOT_NEEDED -> "No permission needed on this Android version."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state == PermissionState.BLOCKED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (state) {
                PermissionState.CAN_REQUEST -> Button(onClick = { launcher.launch(capability.permissions) }) { Text("Grant permission") }
                PermissionState.PARTIAL -> OutlinedButton(onClick = { launcher.launch(capability.permissions) }) { Text("Allow precise location") }
                PermissionState.BLOCKED -> Button(onClick = { openSettings(context, capability) }) { Text("Open settings") }
                PermissionState.GRANTED -> OutlinedButton(onClick = { openSettings(context, capability) }) { Text("Manage access") }
                PermissionState.NOT_NEEDED -> Unit
            }
        }
    }
}

/** Features that need no permission, said plainly instead of showing a button that cannot do anything. */
@Composable
internal fun NoPermissionNeededCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(JarvisSpacing.Section), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("No permission needed", style = MaterialTheme.typography.titleMedium)
            Text(
                "Alarms and timers are set through your Clock app; music, maps, and YouTube open in their own apps; the flashlight and volume work directly.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** Notifications have their own settings page; everything else is on the app's permission page. */
private fun openSettings(context: Context, capability: Capability) {
    val intent = if (capability == Capability.NOTIFICATIONS) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
