package com.cascade.alarm.engine.bedtime

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cascade.alarm.MainActivity
import com.cascade.alarm.R
import com.cascade.alarm.data.SystemTestAlarm
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.data.db.CascadeDatabase
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.TimeSource
import com.cascade.alarm.ui.RingsInText
import com.cascade.alarm.ui.TimeText
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Settings > Bedtime reminder (owner request, 2026-10-26). Off until the user turns it on. */
@Singleton
class BedtimePreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val mutableEnabled = MutableStateFlow(prefs.getBoolean(ENABLED, false))
    private val mutableSleep = MutableStateFlow(BedtimeRules.clampSleep(prefs.getInt(SLEEP_MINUTES, BedtimeRules.DEFAULT_SLEEP_MINUTES)))

    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    /** How much sleep to remind ahead of: the reminder comes this long before the first alarm. */
    val sleepMinutes: StateFlow<Int> = mutableSleep.asStateFlow()

    fun set(enabled: Boolean, sleepMinutes: Int) {
        val sleep = BedtimeRules.clampSleep(sleepMinutes)
        prefs.edit().putBoolean(ENABLED, enabled).putInt(SLEEP_MINUTES, sleep).apply()
        mutableEnabled.value = enabled
        mutableSleep.value = sleep
    }

    private companion object {
        const val NAME = "bedtime"
        const val ENABLED = "enabled"
        const val SLEEP_MINUTES = "sleep_minutes"
    }
}

object BedtimeRules {
    const val DEFAULT_SLEEP_MINUTES = 8 * 60
    val SLEEP_CHOICES = listOf(6 * 60, 7 * 60, 7 * 60 + 30, 8 * 60, 8 * 60 + 30, 9 * 60)

    /** A reminder that arrives this late, or this early, is stale: the schedule changed since. */
    val ON_TIME: Duration = Duration.ofMinutes(15)

    fun clampSleep(minutes: Int) = minutes.coerceIn(SLEEP_CHOICES.first(), SLEEP_CHOICES.last())

    fun sleepName(minutes: Int) = if (minutes % 60 == 0) "${minutes / 60} h" else "${minutes / 60}½ h"

    /**
     * The next regular ring of any group that is on: snoozes, test rings and wake-up checks do not
     * count, as there is no going to bed before those.
     */
    fun nextRing(groups: List<AlarmGroupEntity>, alarms: List<AlarmEntity>): Pair<AlarmGroupEntity, Instant>? {
        val armed = groups.filter { it.isArmed && it.id != SystemTestAlarm.GROUP_ID }.associateBy { it.id }
        return alarms.mapNotNull { alarm ->
            val group = armed[alarm.groupId] ?: return@mapNotNull null
            val at = alarm.nextTriggerAt ?: return@mapNotNull null
            if (!alarm.isEnabled || at == alarm.oneTimeTriggerAt || at == alarm.wakeCheckAt) null else group to at
        }.minByOrNull { it.second }
    }

    /** When to remind for a ring at [ring], or null when that is already past. */
    fun remindAt(ring: Instant, sleepMinutes: Int, now: Instant): Instant? =
        ring.minus(Duration.ofMinutes(sleepMinutes.toLong())).takeIf { it.isAfter(now) }
}

/** Keeps one inexact AlarmManager alarm for the next reminder, and posts it when due. */
@Singleton
class BedtimeReminder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: CascadeDatabase,
    private val prefs: BedtimePreferences,
    private val time: TimeSource,
) {
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)

    suspend fun update() {
        val at = if (prefs.enabled.value) next()?.let { (_, ring) -> BedtimeRules.remindAt(ring, prefs.sleepMinutes.value, time.now()) } else null
        if (at == null) {
            alarmManager.cancel(pendingIntent())
        } else {
            // Exact: an inexact alarm may come an hour late, which is no bedtime reminder at all.
            // USE_EXACT_ALARM is held for the alarms themselves; without it, a ten-minute window.
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pendingIntent())
            } catch (e: SecurityException) {
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), WINDOW_MILLIS, pendingIntent())
            }
            EngineLog.i("bedtime reminder at=$at")
        }
    }

    /** The reminder went off: post it if it still matches the schedule, then plan the next. */
    suspend fun onDue() {
        val next = if (prefs.enabled.value) next() else null
        val now = time.now()
        if (next != null) {
            val (group, ring) = next
            val due = ring.minus(Duration.ofMinutes(prefs.sleepMinutes.value.toLong()))
            if (Duration.between(due, now).abs() <= BedtimeRules.ON_TIME) post(group, ring, now)
        }
        update()
    }

    private suspend fun next() = BedtimeRules.nextRing(db.alarmGroupDao().all(), db.alarmDao().all())

    private fun post(group: AlarmGroupEntity, ring: Instant, now: Instant) {
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.bedtime_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val is24Hour = DateFormat.is24HourFormat(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = "${group.name} rings at ${TimeText.nextRing(ring, is24Hour, time.zone())}, ${RingsInText.until(Duration.between(now, ring)).removePrefix("in ")} from now."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(context.getString(R.string.bedtime_title))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            EngineLog.w("bedtime reminder not shown: ${e.message}")
        }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, BedtimeReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val CHANNEL_ID = "bedtime"
        const val NOTIFICATION_ID = 7301
        const val REQUEST_CODE = 7301
        const val WINDOW_MILLIS = 10 * 60 * 1000L
    }
}

class BedtimeReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun bedtimeReminder(): BedtimeReminder
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminder = EntryPointAccessors.fromApplication(context, Entry::class.java).bedtimeReminder()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reminder.onDue()
            } catch (e: Exception) {
                EngineLog.e("bedtime reminder failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
