package com.cascade.alarm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.data.SystemTestAlarm
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.sound.SoundConfig
import com.cascade.alarm.data.sound.SoundSettings
import com.cascade.alarm.data.sound.Tones
import com.cascade.alarm.engine.EngineEntryPoint
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.ring.AlarmRingService
import com.cascade.alarm.ui.components.AnimationChoice
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
        // Look and feel, until M7's Appearance screen: --es theme <id>; --es animation theme|none|<key>.
        // --ez owned true|false: pretend Cascade Plus is (not) owned, until Play Billing can be tested.
        if (action == "SET_THEME" || action == "SET_ANIMATION" || action == "SET_PLUS") {
            val ui = EntryPointAccessors.fromApplication(context, DebugUiEntryPoint::class.java)
            intent.getStringExtra("theme")?.let(ui.uiPreferences()::setTheme)
            intent.getStringExtra("animation")?.let { ui.uiPreferences().setAnimation(AnimationChoice.fromKey(it)) }
            if (intent.hasExtra("owned")) ui.entitlements().setPlusOwned(intent.getBooleanExtra("owned", false))
            // --es group <id>|follow: the first listed group's own theme.
            intent.getStringExtra("group")?.let { theme ->
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val group = engine.alarmRepository().observeGroupSummaries().first().firstOrNull()?.group
                        group?.let { repository.setGroupTheme(it.id, theme.takeUnless { t -> t == "follow" }) }
                    } finally {
                        pending.finish()
                    }
                }
            }
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
                    // Sets the first listed group's sound (and the test alarm's): --es tone <key> | --es file <name>
                    // (a name that was never copied in, to exercise the fallback), --ei ramp, --ei start.
                    "SET_SOUND" -> {
                        val sound = intent.getStringExtra("file")?.let { SoundConfig.DeviceAudio(it, it) }
                            ?: SoundConfig.BuiltIn(intent.getStringExtra("tone") ?: Tones.DEFAULT.key)
                        val ramp = intent.getIntExtra("ramp", SoundSettings.DEFAULT_RAMP_SECONDS)
                        val start = intent.getIntExtra("start", SoundSettings.DEFAULT_START_PERCENT)
                        repository.editTestAlarm("debug_sound_rows") { it }
                        repository.setGroupSound(SystemTestAlarm.GROUP_ID, sound, ramp, start)
                        reconciler.mutate("debug_sound") { db ->
                            db.alarmGroupDao().all().firstOrNull { it.id != SystemTestAlarm.GROUP_ID }?.let {
                                db.alarmGroupDao().upsert(it.copy(sound = sound, rampSeconds = ramp, rampStartPercent = start))
                            }
                        }
                    }
                    // Sets the challenge of the alarm at --ei position: --es kind math|phrase|barcode|memory|steps|none,
                    // with --es text for the phrase or the barcode payload. Plain extras: JSON does not
                    // survive the device shell's quoting.
                    "SET_CHALLENGE" -> {
                        val position = intent.getIntExtra("position", 0)
                        val text = intent.getStringExtra("text").orEmpty()
                        val config = when (intent.getStringExtra("kind")) {
                            "math" -> ChallengeConfig.Math(problemCount = intent.getIntExtra("count", 3))
                            "phrase" -> ChallengeConfig.TypePhrase(text)
                            "barcode" -> ChallengeConfig.BarcodeScan(text.ifEmpty { "cascade:debug" }, "Bathroom mirror")
                            "memory" -> ChallengeConfig.Memory(intent.getIntExtra("count", 5))
                            "steps" -> ChallengeConfig.Steps(intent.getIntExtra("count", 20))
                            else -> null
                        }
                        reconciler.mutate("debug_challenge") { db ->
                            val group = db.alarmGroupDao().all().firstOrNull { it.id != SystemTestAlarm.GROUP_ID } ?: return@mutate
                            val alarm = db.alarmDao().inGroup(group.id).getOrNull(position) ?: return@mutate
                            db.alarmDao().upsert(alarm.copy(challengeOverride = config))
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
