package com.jarvis.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/**
 * Requests that genuinely need the user on screen (a confirmation dialog, an editable draft, a list to pick from)
 * while JARVIS is in the background. The request itself is kept in the screen model; this notification opens JARVIS
 * exactly where it is waiting. Contents are generic so nothing personal appears on the lock screen.
 */
object ForegroundNeeded {
    private const val CHANNEL = "jarvis.continue"
    private const val NOTIFICATION_ID = 7004

    /** Posts "tap to continue" and returns the sentence to tell the user (also spoken). */
    fun post(context: Context, title: String, reason: String, extras: Intent.() -> Unit = {}): String {
        val appContext = context.applicationContext
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return "$reason Open JARVIS to continue."
        }
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Continue in JARVIS", NotificationManager.IMPORTANCE_HIGH))
        val intent = Intent().setClassName(appContext, "com.jarvis.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply(extras)
        val pending = PendingIntent.getActivity(appContext, NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(
            NOTIFICATION_ID,
            android.app.Notification.Builder(appContext, CHANNEL)
                .setSmallIcon(com.jarvis.R.drawable.ic_stat_jarvis)
                .setContentTitle(title)
                .setContentText("Tap to continue in JARVIS.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60_000)
                .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
                .build()
        )
        return "$reason I've put a notification up: tap it to continue."
    }

    fun clear(context: Context) {
        context.applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

/** JARVIS asked a question while it could not listen (not on screen and no wake-word service): tap to answer by voice. */
object FollowUpNotice {
    fun post(context: Context) {
        ForegroundNeeded.post(context, "JARVIS asked you a question", "") {
            putExtra(WakeWordService.EXTRA_START_LISTENING, true)
        }
    }
}
