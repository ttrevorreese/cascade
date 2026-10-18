package com.cascade.alarm.engine.ring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.cascade.alarm.R
import com.cascade.alarm.ui.ring.AlarmActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The ringing notification. Its full-screen intent is the only supported way to put
 * [AlarmActivity] on screen from the background, so without notification permission the alarm
 * still sounds but cannot take over the lock screen.
 */
class RingNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun build(ringing: RingingAlarm?): Notification {
        ensureChannel()
        val title = ringing?.label ?: context.getString(R.string.ring_title_default)
        val text = ringing?.let { formatTime(it.scheduledFor) }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true) // the service plays the sound itself on the alarm stream
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setFullScreenIntent(activityPendingIntent(null), true)
            .setContentIntent(activityPendingIntent(null))
            .addAction(0, context.getString(R.string.ring_action_dismiss), dismissPendingIntent())
            .build()
    }

    /** Used only if the foreground service could not be started from the fire receiver. */
    fun postFallback(alarmId: String, scheduledFor: Instant) {
        ensureChannel()
        val fallback = AlarmActivity.fallbackIntent(context, alarmId, scheduledFor)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(context.getString(R.string.ring_title_default))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(activityPendingIntent(fallback), true)
            .setContentIntent(activityPendingIntent(fallback))
            .setAutoCancel(true)
            .build()
        manager.notify(FALLBACK_NOTIFICATION_ID, notification)
    }

    fun cancelFallback() = manager.cancel(FALLBACK_NOTIFICATION_ID)

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.ring_channel_name), NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun activityPendingIntent(intent: Intent?): PendingIntent = PendingIntent.getActivity(
        context,
        if (intent == null) REQUEST_ACTIVITY else REQUEST_FALLBACK,
        intent ?: Intent(context, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun dismissPendingIntent(): PendingIntent = PendingIntent.getService(
        context, REQUEST_DISMISS, AlarmRingService.dismissIntent(context), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun formatTime(instant: Instant): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(instant.atZone(ZoneId.systemDefault()))

    companion object {
        const val CHANNEL_ID = "ringing_v1"
        const val NOTIFICATION_ID = 1
        private const val FALLBACK_NOTIFICATION_ID = 2
        private const val REQUEST_ACTIVITY = 10
        private const val REQUEST_FALLBACK = 11
        private const val REQUEST_DISMISS = 12
    }
}
