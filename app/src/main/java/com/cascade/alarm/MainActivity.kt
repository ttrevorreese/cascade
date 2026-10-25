package com.cascade.alarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.db.ActiveRingDao
import com.cascade.alarm.data.db.AlarmDao
import com.cascade.alarm.data.db.AlarmGroupDao
import com.cascade.alarm.engine.ring.AlarmRingService
import com.cascade.alarm.engine.ring.RingState
import com.cascade.alarm.engine.schedule.Reconciler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cascade.alarm.ui.CascadeNavHost
import com.cascade.alarm.ui.components.AnimationChoice
import com.cascade.alarm.ui.components.LocalAnimationChoice
import com.cascade.alarm.ui.settings.UiPreferences
import com.cascade.alarm.ui.camera.PhotoStore
import com.cascade.alarm.ui.theme.CascadeTheme
import com.cascade.alarm.ui.theme.SystemBarsFor
import com.cascade.alarm.ui.theme.ThemeResolution
import com.cascade.alarm.data.billing.Entitlements
import com.cascade.alarm.data.voice.SpeechWarmer
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
    @Inject lateinit var uiPreferences: UiPreferences
    @Inject lateinit var entitlements: Entitlements
    @Inject lateinit var speechWarmer: SpeechWarmer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) onAppOpened()
        // Spoken labels are made while the phone is unlocked, for rings before the next unlock.
        speechWarmer.start()
        setContent {
            val themeId by uiPreferences.themeId.collectAsStateWithLifecycle()
            val animation by uiPreferences.animation.collectAsStateWithLifecycle()
            // Read so that a purchase or refund re-themes at once.
            val plusOwned by entitlements.plusOwned.collectAsStateWithLifecycle()
            val theme = ThemeResolution.app(themeId) { it.isFree || plusOwned }
            SystemBarsFor(theme.colors.isLight)
            CascadeTheme(theme) {
                // Background animations are part of Cascade Plus.
                CompositionLocalProvider(LocalAnimationChoice provides if (plusOwned) animation else AnimationChoice.None) {
                    CascadeNavHost()
                }
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
