package com.cascade.alarm.engine.receivers

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.cascade.alarm.engine.EngineEntryPoint
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.ring.AlarmRingService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Rebuilds the whole schedule whenever something outside the app may have invalidated it, and
 * resumes a ring that was interrupted by power-off. Direct Boot aware: LOCKED_BOOT_COMPLETED
 * arrives before the user unlocks, which is when an overnight reboot most needs us.
 */
class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return
        val isBoot = action == Intent.ACTION_LOCKED_BOOT_COMPLETED || action == Intent.ACTION_BOOT_COMPLETED
        val entryPoint = EntryPointAccessors.fromApplication(context, EngineEntryPoint::class.java)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                entryPoint.reconciler().reconcileAll(reason = action.substringAfterLast('.'))
                if (entryPoint.activeRingDao().get() != null) {
                    EngineLog.i("active ring found after $action; resuming")
                    ContextCompat.startForegroundService(context, AlarmRingService.resumeIntent(context, afterBoot = isBoot))
                }
            } catch (e: Exception) {
                EngineLog.e("reconcile after $action failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        // Action strings are compile-time constants; older releases simply never send the newer ones.
        @SuppressLint("InlinedApi")
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
