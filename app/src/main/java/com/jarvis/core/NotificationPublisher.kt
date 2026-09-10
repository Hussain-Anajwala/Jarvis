package com.jarvis.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat

enum class NotificationTier(
    val channelId: String,
    val channelName: String,
    val importance: Int
) {
    SILENT("jarvis.silent", "Silent updates", NotificationManager.IMPORTANCE_LOW),
    NORMAL("jarvis.normal", "Normal updates", NotificationManager.IMPORTANCE_DEFAULT),
    PROMPT("jarvis.prompt", "Action prompts", NotificationManager.IMPORTANCE_HIGH),
    URGENT("jarvis.urgent", "Urgent alerts", NotificationManager.IMPORTANCE_MAX)
}

object NotificationPublisher {
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(NotificationTier.entries.map { tier ->
            NotificationChannel(tier.channelId, tier.channelName, tier.importance)
        })
    }

    fun post(context: Context, tier: NotificationTier, title: String, text: String) {
        createChannels(context)
        val notification = Notification.Builder(context, tier.channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(tier.ordinal + 1, notification)
    }
}
