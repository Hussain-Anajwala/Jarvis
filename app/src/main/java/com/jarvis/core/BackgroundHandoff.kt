package com.jarvis.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/**
 * Android does not let an app start another app's screen while none of its own screens is visible: the call is
 * silently ignored. A request that arrives hands-free (after "Hey JARVIS" while the phone is in a pocket or another
 * app is open) must therefore never say "opened Maps" when nothing opened. Instead the intent is parked in a
 * notification; the user's tap on it is a user-initiated launch that Android allows.
 */
object BackgroundHandoff {
    private const val CHANNEL = "jarvis.handoff"
    private const val NOTIFICATION_ID = 7003

    /** True when a JARVIS screen is visible, so Android will allow opening another app right now. */
    fun canOpenAppsNow(): Boolean = AppVisibility.visible

    /** Parks [intent] behind a notification and returns the honest sentence to tell the user. */
    fun offer(context: Context, intent: Intent, what: String): String {
        val appContext = context.applicationContext
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return "This needs $what to open, and Android only lets an app open another app while it is on screen. " +
                "Notifications are off, so open JARVIS and ask again."
        }
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Open an app for JARVIS", NotificationManager.IMPORTANCE_HIGH))
        val pending = PendingIntent.getActivity(
            appContext, NOTIFICATION_ID, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.notify(
            NOTIFICATION_ID,
            android.app.Notification.Builder(appContext, CHANNEL)
                .setSmallIcon(com.jarvis.R.drawable.ic_stat_jarvis)
                .setContentTitle("Tap to open $what")
                .setContentText("JARVIS has it ready; Android needs your tap to open another app.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setTimeoutAfter(5 * 60_000)
                .build()
        )
        return "This needs $what to open, and Android only lets an app open another app while you're using it. " +
            "I've put a notification up: tap it and $what opens straight away."
    }
}
