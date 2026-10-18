package com.cascade.alarm.engine.receivers

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.ring.AlarmRingService
import com.cascade.alarm.engine.ring.RingNotifications
import com.cascade.alarm.engine.ring.WakeLocks
import java.time.Instant

/**
 * Target of every AlarmManager alarm. Does nothing but hand off to the foreground service: the
 * receiver's execution budget is short and the process may be cold-starting (SPEC §4.2).
 */
class AlarmFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: UNKNOWN_ALARM
        val scheduledFor = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_SCHEDULED_FOR, System.currentTimeMillis()))
        EngineLog.i("fire received alarm=$alarmId scheduledFor=$scheduledFor")

        WakeLocks.acquireHandoff(context)
        try {
            // Allowed from the background: exact alarms are an exemption to FGS start restrictions.
            ContextCompat.startForegroundService(context, AlarmRingService.fireIntent(context, alarmId, scheduledFor))
        } catch (e: Exception) {
            // Should not happen, but if the service cannot start, a full-screen notification can still
            // bring up AlarmActivity, which then starts the service from the foreground.
            EngineLog.e("could not start ring service; posting fallback notification", e)
            RingNotifications(context).postFallback(alarmId, scheduledFor)
            WakeLocks.releaseHandoff()
        }
    }

    companion object {
        const val ACTION_FIRE = "com.cascade.alarm.action.FIRE"
        const val EXTRA_ALARM_ID = "alarmId"
        const val EXTRA_SCHEDULED_FOR = "scheduledFor"
        const val UNKNOWN_ALARM = "unknown"

        /** One PendingIntent per alarm (identified by its data URI); extras are refreshed on update. */
        fun pendingIntent(context: Context, alarmId: String, scheduledFor: Instant): PendingIntent {
            val intent = Intent(context, AlarmFireReceiver::class.java)
                .setAction(ACTION_FIRE)
                .setData(Uri.parse("cascade://alarm/${Uri.encode(alarmId)}"))
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_SCHEDULED_FOR, scheduledFor.toEpochMilli())
            return PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
