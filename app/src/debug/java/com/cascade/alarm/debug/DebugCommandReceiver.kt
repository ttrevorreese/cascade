package com.cascade.alarm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cascade.alarm.data.SystemTestAlarm
import com.cascade.alarm.engine.EngineEntryPoint
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.ring.AlarmRingService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek

/**
 * adb hooks for scripts/e2e-alarm.sh, acting on the hidden system test alarm, e.g.
 *   adb shell am broadcast -a com.cascade.alarm.debug.RING_IN --ei seconds 45 -p com.cascade.alarm
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repository = EntryPointAccessors.fromApplication(context, EngineEntryPoint::class.java).alarmRepository()
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
                }
            } finally {
                pending.finish()
            }
        }
    }
}
