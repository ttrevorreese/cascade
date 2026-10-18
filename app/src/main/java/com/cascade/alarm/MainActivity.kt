package com.cascade.alarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cascade.alarm.data.db.ActiveRingDao
import com.cascade.alarm.engine.ring.AlarmRingService
import com.cascade.alarm.engine.ring.RingState
import com.cascade.alarm.engine.schedule.Reconciler
import com.cascade.alarm.ui.CascadeNavHost
import com.cascade.alarm.ui.theme.CascadeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var reconciler: Reconciler
    @Inject lateinit var activeRingDao: ActiveRingDao
    @Inject lateinit var ringState: RingState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) onAppOpened()
        setContent {
            CascadeTheme {
                CascadeNavHost()
            }
        }
    }

    private fun onAppOpened() = lifecycleScope.launch {
        // Re-arms everything, which also recovers from a force-stop (that clears AlarmManager).
        reconciler.reconcileAll("app_start")
        // SPEC §6.7: a ring that never completed resumes when the app is opened.
        if (activeRingDao.get() != null && ringState.current.value == null) {
            ContextCompat.startForegroundService(
                this@MainActivity,
                AlarmRingService.resumeIntent(this@MainActivity, afterBoot = false),
            )
        }
    }
}
