package com.cascade.alarm

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.db.ActiveRingDao
import com.cascade.alarm.data.db.AlarmDao
import com.cascade.alarm.data.db.AlarmGroupDao
import com.cascade.alarm.engine.ring.AlarmRingService
import com.cascade.alarm.engine.ring.RingState
import com.cascade.alarm.engine.schedule.Reconciler
import com.cascade.alarm.ui.CascadeNavHost
import com.cascade.alarm.ui.camera.PhotoStore
import com.cascade.alarm.ui.theme.CascadeTheme
import com.cascade.alarm.ui.theme.CascadeThemes
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var reconciler: Reconciler
    @Inject lateinit var activeRingDao: ActiveRingDao
    @Inject lateinit var alarmDao: AlarmDao
    @Inject lateinit var groupDao: AlarmGroupDao
    @Inject lateinit var ringState: RingState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bar icons follow the theme, not the system dark-mode setting (DESIGN.md §2.1).
        val bars = if (CascadeThemes.default.colors.isLight) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
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
        // Reference photos whose alarm was deleted, or that were taken and then not saved.
        // A photo can be an alarm's own challenge or a rung of a group's ladder; keep both.
        val challenges = alarmDao.all().mapNotNull { it.challengeOverride } + groupDao.all().flatMap { it.escalationPolicy.steps }
        val inUse = challenges.mapNotNull { (it as? ChallengeConfig.PhotoMatch)?.referencePhotoId }.toSet()
        withContext(Dispatchers.IO) { PhotoStore.deleteAllExcept(this@MainActivity, inUse) }
    }
}
