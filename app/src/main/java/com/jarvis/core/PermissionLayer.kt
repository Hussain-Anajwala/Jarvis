package com.jarvis.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

enum class Capability(val label: String, val description: String, val permissions: Array<String>) {
    CALENDAR("Calendar", "Create and cancel calendar events", arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
    MICROPHONE("Microphone", "Use push-to-talk voice input", arrayOf(Manifest.permission.RECORD_AUDIO)),
    NOTIFICATIONS("Notifications", "Notify you about reminders", arrayOf(Manifest.permission.POST_NOTIFICATIONS))
}

class PermissionLayer(private val context: Context) {
    fun isGranted(capability: Capability): Boolean = capability.permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
