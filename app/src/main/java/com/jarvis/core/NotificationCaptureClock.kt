package com.jarvis.core

import android.content.Context

/** Persists when message capture started, so nothing older than the grant (or the last Clear data) is stored. */
object NotificationCaptureClock {
    private const val PREFS = "notification_capture"
    private const val KEY_SINCE = "capture_since"

    fun since(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SINCE, Long.MAX_VALUE)

    /** Called when the listener connects; keeps an existing start time across app updates and reboots. */
    fun startIfUnset(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SINCE)) prefs.edit().putLong(KEY_SINCE, now).apply()
    }

    /** Clear data and revoked access restart the window. */
    fun restart(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_SINCE, now).apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SINCE).apply()
    }
}
