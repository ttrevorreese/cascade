package com.cascade.alarm.engine.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.cascade.alarm.MainActivity
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.receivers.AlarmFireReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject

interface AlarmScheduler {
    /** Replaces any earlier schedule for [alarmId]. A past [at] fires immediately. */
    fun schedule(alarmId: String, at: Instant)
    fun cancel(alarmId: String)
}

class AndroidAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmManager: AlarmManager,
) : AlarmScheduler {

    override fun schedule(alarmId: String, at: Instant) {
        val operation = AlarmFireReceiver.pendingIntent(context, alarmId, at)
        val showIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            // Doze-exempt, never deferred, and shows the system alarm icon (SPEC §4.1).
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), showIntent), operation)
            EngineLog.i("setAlarmClock alarm=$alarmId at=$at")
        } catch (e: SecurityException) {
            // Only reachable on Android 12/12L if the user revoked "Alarms & reminders" (on 13+ we hold
            // USE_EXACT_ALARM, which cannot be revoked). An inexact alarm beats no alarm.
            EngineLog.e("exact alarm permission missing; falling back to inexact alarm=$alarmId at=$at", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
        }
    }

    override fun cancel(alarmId: String) {
        alarmManager.cancel(AlarmFireReceiver.pendingIntent(context, alarmId, Instant.EPOCH))
        EngineLog.i("cancel alarm=$alarmId")
    }
}
