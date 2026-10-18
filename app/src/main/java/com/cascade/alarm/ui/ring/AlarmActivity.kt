package com.cascade.alarm.ui.ring

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.cascade.alarm.data.db.ActiveRingDao
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.ring.AlarmRingService
import com.cascade.alarm.engine.ring.RingState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject

/**
 * Full-screen alarm over the lock screen. Deliberately does NOT request keyguard dismissal: on a
 * secure device that would put the PIN prompt on top of the alarm.
 */
@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {
    @Inject lateinit var ringState: RingState
    @Inject lateinit var activeRingDao: ActiveRingDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Back must not dismiss the alarm; only the Dismiss button (and, from M3, a challenge) does.
        onBackPressedDispatcher.addCallback(this) {}

        startFallbackRingIfNeeded(intent)
        lifecycleScope.launch { finishWhenNothingRings() }

        setContent {
            val ringing by ringState.current.collectAsStateWithLifecycle()
            AlarmScreen(ringing = ringing, onDismiss = ::dismiss)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startFallbackRingIfNeeded(intent)
    }

    private fun dismiss() {
        startService(AlarmRingService.dismissIntent(this))
    }

    private suspend fun finishWhenNothingRings() {
        // The full-screen intent can launch us before the service has started the ring, so give it
        // a moment before concluding that nothing is ringing.
        val appeared = withTimeoutOrNull(STARTUP_GRACE_MILLIS) { ringState.current.first { it != null } }
        if (appeared == null) {
            // Opened from a stale notification, or the process restarted: resume if a ring is persisted.
            if (activeRingDao.get() == null) {
                finish()
                return
            }
            ContextCompat.startForegroundService(this, AlarmRingService.resumeIntent(this, afterBoot = false))
            ringState.current.first { it != null }
        }
        ringState.current.first { it == null }
        finish()
    }

    /** The fire receiver could not start the service; we are in the foreground now, so we can. */
    private fun startFallbackRingIfNeeded(intent: Intent) {
        val alarmId = intent.getStringExtra(EXTRA_FALLBACK_ALARM_ID) ?: return
        val scheduledFor = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_FALLBACK_SCHEDULED_FOR, System.currentTimeMillis()))
        EngineLog.w("starting ring from fallback notification alarm=$alarmId")
        ContextCompat.startForegroundService(this, AlarmRingService.fireIntent(this, alarmId, scheduledFor))
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    companion object {
        private const val STARTUP_GRACE_MILLIS = 5_000L
        private const val EXTRA_FALLBACK_ALARM_ID = "fallbackAlarmId"
        private const val EXTRA_FALLBACK_SCHEDULED_FOR = "fallbackScheduledFor"

        fun fallbackIntent(context: Context, alarmId: String, scheduledFor: Instant): Intent =
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_FALLBACK_ALARM_ID, alarmId)
                .putExtra(EXTRA_FALLBACK_SCHEDULED_FOR, scheduledFor.toEpochMilli())
    }
}
