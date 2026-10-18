package com.cascade.alarm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cascade.alarm.data.HardcodedAlarm
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
 * adb hooks for scripts/e2e-alarm.sh, e.g.
 *   adb shell am broadcast -a com.cascade.alarm.debug.RING_IN --ei seconds 45 -p com.cascade.alarm
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reconciler = EntryPointAccessors.fromApplication(context, EngineEntryPoint::class.java).reconciler()
        val action = intent.action?.substringAfterLast('.') ?: return
        EngineLog.i("debug command $action")
        if (action == "DISMISS") {
            context.startService(AlarmRingService.dismissIntent(context))
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reconciler.edit(HardcodedAlarm.ID, "debug_$action") { alarm ->
                    when (action) {
                        "RING_IN" -> alarm.copy(
                            oneTimeTriggerAt = Instant.now().plusSeconds(intent.getIntExtra("seconds", 60).toLong()),
                        )
                        "SET_TIMEOUT" -> alarm.copy(timeoutMinutes = intent.getIntExtra("minutes", 15))
                        "SET_DAILY" -> alarm.copy(
                            hour = intent.getIntExtra("hour", alarm.hour),
                            minute = intent.getIntExtra("minute", alarm.minute),
                            daysOfWeek = DayOfWeek.entries.toSet(),
                            isEnabled = true,
                        )
                        "DISABLE" -> alarm.copy(isEnabled = false, oneTimeTriggerAt = null)
                        else -> alarm
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
