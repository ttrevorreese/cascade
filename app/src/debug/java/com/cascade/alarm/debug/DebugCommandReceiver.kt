package com.cascade.alarm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.data.SystemTestAlarm
import com.cascade.alarm.engine.EngineEntryPoint
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.ring.AlarmRingService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant

/**
 * adb hooks for scripts/e2e-alarm.sh, acting on the hidden system test alarm, e.g.
 *   adb shell am broadcast -a com.cascade.alarm.debug.RING_IN --ei seconds 45 -p com.cascade.alarm
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = EntryPointAccessors.fromApplication(context, EngineEntryPoint::class.java)
        val repository = engine.alarmRepository()
        val reconciler = engine.reconciler()
        val action = intent.action?.substringAfterLast('.') ?: return
        EngineLog.i("debug command $action")
        if (action == "DISMISS") {
            context.startService(AlarmRingService.dismissIntent(context))
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    "RING_IN" -> repository.ringTestAlarmIn(intent.getIntExtra("seconds", 60).toLong())
                    "SET_TIMEOUT" -> repository.editTestAlarm("debug_timeout") {
                        it.copy(timeoutMinutes = intent.getIntExtra("minutes", 15))
                    }
                    "SET_DAILY" -> repository.editTestAlarm("debug_daily") {
                        it.copy(
                            hour = intent.getIntExtra("hour", it.hour),
                            minute = intent.getIntExtra("minute", it.minute),
                            daysOfWeek = DayOfWeek.entries.toSet(),
                            isEnabled = true,
                        )
                    }
                    "DISABLE" -> repository.editTestAlarm("debug_disable") {
                        it.copy(isEnabled = false, oneTimeTriggerAt = null)
                    }
                    "SET_WAKE_CHECK" -> {
                        repository.editTestAlarm("debug_wake_check_rows") { it }
                        repository.setWakeCheck(
                            SystemTestAlarm.GROUP_ID,
                            enabled = intent.getBooleanExtra("enabled", true),
                            delayMinutes = intent.getIntExtra("minutes", 1),
                        )
                    }
                    // Screenshot fixtures: an armed four-step weekday group and a disarmed one.
                    "SEED_DEMO" -> {
                        seedGroup(repository, "Workday Wake", armed = true, listOf(6 to 0, 6 to 10, 6 to 20, 6 to 35), weekdays = true)
                        seedGroup(repository, "Gym Day", armed = false, listOf(5 to 30, 5 to 45), weekdays = false)
                    }
                    // Rings the alarm at --ei position of the first listed group in --ei seconds.
                    "RING_POSITION" -> {
                        val position = intent.getIntExtra("position", 0)
                        val at = Instant.now().plusSeconds(intent.getIntExtra("seconds", 10).toLong())
                        reconciler.mutate("debug_ring_position") { db ->
                            val group = db.alarmGroupDao().all().firstOrNull { it.id != SystemTestAlarm.GROUP_ID } ?: return@mutate
                            val alarm = db.alarmDao().inGroup(group.id).getOrNull(position) ?: return@mutate
                            db.alarmDao().upsert(alarm.copy(oneTimeTriggerAt = at))
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun seedGroup(repository: AlarmRepository, name: String, armed: Boolean, times: List<Pair<Int, Int>>, weekdays: Boolean) {
        val id = repository.createGroup(name)
        times.forEach { (hour, minute) ->
            val draft = repository.newAlarmDraft(id)
            repository.saveAlarm(
                draft.copy(hour = hour, minute = minute, daysOfWeek = if (weekdays) DayOfWeek.entries.take(5).toSet() else setOf(DayOfWeek.SATURDAY)),
            )
        }
        if (!armed) repository.setGroupArmed(id, false)
    }
}
