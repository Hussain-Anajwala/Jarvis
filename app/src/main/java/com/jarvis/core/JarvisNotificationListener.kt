package com.jarvis.core

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Captures message notifications only while the user has granted Notification access.
 * Stores a short snippet locally (see NotificationCapturePolicy); never sends anything off the device.
 */
class JarvisNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        if (!NotificationCapturePolicy.isMessageLike(
                packageName = sbn.packageName,
                ownPackage = packageName,
                category = notification.category,
                hasMessagingStyle = style != null,
                isOngoing = sbn.isOngoing,
                isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
            )
        ) return
        // A MessagingStyle with no messages (seen on-device) falls back to the title/text pair below.
        val messages = style?.messages?.takeIf { it.isNotEmpty() }?.map { message ->
            CapturedMessage(
                sender = message.person?.name?.toString() ?: style.conversationTitle?.toString().orEmpty(),
                text = message.text?.toString().orEmpty(),
                timestamp = message.timestamp.takeIf { it > 0 } ?: sbn.postTime
            )
        } ?: listOf(
            CapturedMessage(
                sender = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                timestamp = sbn.postTime
            )
        )
        val records = NotificationCapturePolicy.toRecords(
            sbn.key, sbn.packageName, appLabel(sbn.packageName), messages,
            postTime = sbn.postTime,
            captureSince = NotificationCaptureClock.since(applicationContext)
        )
        if (records.isEmpty()) return
        scope.launch {
            try {
                val dao = AppDatabase.create(applicationContext).notificationRecordDao()
                dao.insertAll(records)
                dao.deleteOlderThan(System.currentTimeMillis() - NotificationCapturePolicy.RETENTION_MS)
                dao.trimTo(NotificationCapturePolicy.MAX_RECORDS)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not store a message notification", error)
            }
        }
    }

    override fun onListenerConnected() {
        NotificationCaptureClock.startIfUnset(applicationContext)
    }

    override fun onListenerDisconnected() {
        // Revoked access: a later grant starts a fresh capture window.
        if (!isAccessEnabled(applicationContext)) NotificationCaptureClock.reset(applicationContext)
    }

    /** A notification the user dismissed or opened is no longer "new". */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        scope.launch {
            try {
                AppDatabase.create(applicationContext).notificationRecordDao().markReadByKey(sbn.key)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not update a removed notification", error)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun appLabel(packageName: String): String =
        NotificationCapturePolicy.knownLabel(packageName) ?: try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }

    companion object {
        private const val TAG = "JarvisNotifications"

        fun isAccessEnabled(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        fun component(context: Context) = ComponentName(context, JarvisNotificationListener::class.java)
    }
}
