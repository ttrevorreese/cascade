package com.cascade.alarm.engine.ring

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.TimeSource
import dagger.hilt.android.AndroidEntryPoint
import com.cascade.alarm.engine.receivers.AlarmFireReceiver
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * Hosts a ringing alarm (SPEC §4.2). Uses the `systemExempted` foreground service type, which
 * Android 14+ grants to apps holding USE_EXACT_ALARM / SCHEDULE_EXACT_ALARM; `mediaPlayback`
 * is the fallback if the system refuses it. All decisions live in [RingCoordinator].
 */
@AndroidEntryPoint
class AlarmRingService : Service(), RingPresenter {
    @Inject lateinit var store: RingStore
    @Inject lateinit var ringState: RingState
    @Inject lateinit var time: TimeSource

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> EngineLog.e("uncaught error in ring service", e) },
    )
    private lateinit var notifications: RingNotifications
    private lateinit var wakeLock: AndroidRingWakeLock
    private lateinit var sound: ExoAlarmSoundPlayer
    private lateinit var coordinator: RingCoordinator

    /** Commands whose coroutine has not finished; the service stops only when this is zero. */
    private var commandsInFlight = 0

    override fun onCreate() {
        super.onCreate()
        notifications = RingNotifications(this)
        wakeLock = AndroidRingWakeLock(this)
        sound = ExoAlarmSoundPlayer(this, scope)
        coordinator = RingCoordinator(scope, store, time::now, sound, wakeLock, this, ringState)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent is a sticky restart after the process was killed mid-ring.
        val action = intent?.action ?: ACTION_RESUME
        EngineLog.i("service command $action")
        if (action != ACTION_DISMISS) {
            // Must happen synchronously: startForegroundService() gives us only a few seconds.
            promoteToForeground()
            wakeLock.acquire(RingDefaults.INITIAL_WAKE_LOCK)
        }
        WakeLocks.releaseHandoff()
        notifications.cancelFallback()

        commandsInFlight++
        scope.launch {
            try {
                when (action) {
                    ACTION_FIRE -> coordinator.onFire(
                        alarmId = intent?.getStringExtra(EXTRA_ALARM_ID) ?: AlarmFireReceiver.UNKNOWN_ALARM,
                        scheduledFor = Instant.ofEpochMilli(
                            intent?.getLongExtra(EXTRA_SCHEDULED_FOR, 0L)?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        ),
                    )
                    ACTION_RESUME -> coordinator.onResume(intent?.getBooleanExtra(EXTRA_AFTER_BOOT, false) ?: false)
                    ACTION_DISMISS -> coordinator.dismiss()
                }
            } finally {
                commandsInFlight--
                stopIfIdle()
            }
        }
        return START_STICKY
    }

    override fun show(ringing: RingingAlarm) {
        promoteToForeground()
        getSystemService(android.app.NotificationManager::class.java)
            .notify(RingNotifications.NOTIFICATION_ID, notifications.build(ringing))
    }

    override fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopIfIdle()
    }

    override fun onDestroy() {
        EngineLog.i("service destroyed")
        sound.stop()
        wakeLock.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopIfIdle() {
        if (commandsInFlight == 0 && ringState.current.value == null) stopSelf()
    }

    private fun promoteToForeground() {
        val notification = notifications.build(ringState.current.value)
        val id = RingNotifications.NOTIFICATION_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                ServiceCompat.startForeground(this, id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
                return
            } catch (e: Exception) {
                EngineLog.e("systemExempted refused; falling back to mediaPlayback", e)
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, id, notification, type)
    }

    companion object {
        const val ACTION_FIRE = "com.cascade.alarm.action.RING_FIRE"
        const val ACTION_RESUME = "com.cascade.alarm.action.RING_RESUME"
        const val ACTION_DISMISS = "com.cascade.alarm.action.RING_DISMISS"
        private const val EXTRA_ALARM_ID = "alarmId"
        private const val EXTRA_SCHEDULED_FOR = "scheduledFor"
        private const val EXTRA_AFTER_BOOT = "afterBoot"

        fun fireIntent(context: Context, alarmId: String, scheduledFor: Instant): Intent =
            Intent(context, AlarmRingService::class.java).setAction(ACTION_FIRE)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_SCHEDULED_FOR, scheduledFor.toEpochMilli())

        fun resumeIntent(context: Context, afterBoot: Boolean): Intent =
            Intent(context, AlarmRingService::class.java).setAction(ACTION_RESUME)
                .putExtra(EXTRA_AFTER_BOOT, afterBoot)

        fun dismissIntent(context: Context): Intent =
            Intent(context, AlarmRingService::class.java).setAction(ACTION_DISMISS)
    }
}
