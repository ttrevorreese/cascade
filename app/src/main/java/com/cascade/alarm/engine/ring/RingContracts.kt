package com.cascade.alarm.engine.ring

import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.SnoozeState
import com.cascade.alarm.data.db.ActiveRingEntity
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.data.sound.SoundConfig
import com.cascade.alarm.data.sound.SoundSettings
import com.cascade.alarm.data.sound.VibrationSetting
import com.cascade.alarm.data.db.EventType
import com.cascade.alarm.engine.schedule.Reconciler.FireResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class RingingAlarm(
    val alarmId: String,
    val label: String?,
    val scheduledFor: Instant,
    val deadline: Instant,
    /** A quiet "are you awake?" prompt; [deadline] is when the alarm rings again. */
    val isWakeCheck: Boolean = false,
    /** Where this alarm sits among the group's alarms that are on (0-based); drives the firing screen. */
    val position: Int = 0,
    val total: Int = 1,
    /** What dismissing takes; null = the plain Dismiss button. Never set on a wake-up check. */
    val challenge: ChallengeConfig? = null,
    /** Start of this ring; the give-up escape hatch counts from here (SPEC §6.7). */
    val startedAt: Instant = scheduledFor,
    /** The group's own theme as stored, read at fire time; the UI resolves it (DESIGN.md §11.1). */
    val groupThemeId: String? = null,
    /** Whether, and for how long, this ring may be snoozed. */
    val snooze: SnoozeState = SnoozeState.Off,
)

data class SequencePosition(val position: Int, val total: Int)

/** What is ringing right now, observed by [com.cascade.alarm.ui.ring.AlarmActivity]. */
@Singleton
class RingState @Inject constructor() {
    private val mutable = MutableStateFlow<RingingAlarm?>(null)
    val current: StateFlow<RingingAlarm?> = mutable.asStateFlow()

    internal fun set(value: RingingAlarm?) {
        mutable.value = value
    }
}

/** Persistence the ring lifecycle needs; backed by Room in production. */
interface RingStore {
    suspend fun recordFire(alarmId: String, scheduledFor: Instant): FireResult
    suspend fun alarm(alarmId: String): AlarmEntity?
    suspend fun activeRing(): ActiveRingEntity?
    suspend fun setActiveRing(ring: ActiveRingEntity)
    suspend fun clearActiveRing()
    suspend fun log(alarmId: String, type: EventType, vararg metadata: Pair<String, Any?>)

    /**
     * The user dismissed [alarmId]. If it was its cascade's last alarm, the sequence is over:
     * earlier alarms' pending snoozes are dropped and the wake-up check is scheduled.
     */
    suspend fun afterDismiss(alarmId: String)

    /** [alarm]'s place in the cascade of its ring at [ringAt] ([com.cascade.alarm.data.challenge.Escalation.cascade]). */
    suspend fun sequencePosition(alarm: AlarmEntity, ringAt: Instant): SequencePosition

    /** What dismissing [alarm]'s ring at [ringAt] takes, from its override or its group's ladder (SPEC §5); null = plain. */
    suspend fun challengeFor(alarm: AlarmEntity, ringAt: Instant): ChallengeConfig?

    /** [alarm]'s group's sound and ramp, read at fire time. */
    suspend fun soundFor(alarm: AlarmEntity): RingSound

    /** [alarm]'s group's own theme id, or null to follow the app theme. */
    suspend fun themeFor(alarm: AlarmEntity): String?

    /** Rings [alarmId] again at [at] and counts one snooze used. */
    suspend fun snooze(alarmId: String, at: Instant)

    /** The ring ended without a snooze: the next one starts with every snooze available. */
    suspend fun clearSnoozes(alarmId: String)
}

/** What a ring plays and how it gets louder: the group's settings ([AlarmGroupEntity]). */
data class RingSound(
    val sound: SoundConfig = SoundConfig.DEFAULT,
    val rampSeconds: Int = SoundSettings.DEFAULT_RAMP_SECONDS,
    val startPercent: Int = SoundSettings.DEFAULT_START_PERCENT,
    val vibration: VibrationSetting = VibrationSetting(),
) {
    companion object {
        fun of(group: AlarmGroupEntity) = RingSound(
            group.sound,
            group.rampSeconds,
            group.rampStartPercent,
            VibrationSetting.of(group.vibration, group.vibrationStrength, group.vibrationDelaySeconds),
        )
    }
}

interface AlarmSoundPlayer {
    /**
     * Starts the alarm sound. If [sound]'s choice cannot play, the player moves down the chain
     * (chosen → Classic → the phone's alarm sound → vibration) and reports each step
     * to [onFallback] with a short reason and what plays [next] ("classic", "system_sound" or
     * "vibration"), for the event log.
     */
    fun start(sound: RingSound, onFallback: (reason: String, next: String) -> Unit = { _, _ -> })

    /**
     * The wake-up check's prompt: the group's vibration, no alarm sound; or, for a group that does
     * not vibrate, its sound held at the starting level.
     */
    fun startWakeCheck(sound: RingSound)

    /** Quieter while the user works on a challenge; full volume again when [ducked] is false (SPEC §6.8). */
    fun setDucked(ducked: Boolean)
    fun stop()
}

/**
 * Reads an alarm's time and label aloud while it rings (owner request, 2026-10-23). Every failure
 * is quiet: the alarm rings exactly as it would without it.
 */
interface LabelSpeaker {
    /** Says the time [at] and [label] soon, then again every so often, until [stop]. */
    fun start(alarmId: String, label: String?, at: Instant)

    /** Quiet while a challenge is being solved; carries on when [paused] is false. */
    fun setPaused(paused: Boolean)
    fun stop()

    /** For alarms that do not read aloud, and for tests. */
    object None : LabelSpeaker {
        override fun start(alarmId: String, label: String?, at: Instant) = Unit
        override fun setPaused(paused: Boolean) = Unit
        override fun stop() = Unit
    }
}

object Ducking {
    /** Share of the ramped volume kept while a challenge is being solved. */
    const val GAIN = 0.3f
}

interface RingWakeLock {
    /** (Re)acquires with a hard timeout so the lock can never outlive a bug. */
    fun acquire(timeout: Duration)
    fun release()
}

interface RingPresenter {
    /** Shows the ringing notification with its full-screen intent. */
    fun show(ringing: RingingAlarm)

    /** Removes the notification and stops the foreground service. */
    fun finish()
}

object RingDefaults {
    const val TIMEOUT_MINUTES = 15

    /** Held from the moment a fire arrives until the alarm's real timeout is known. */
    val INITIAL_WAKE_LOCK: Duration = Duration.ofMinutes(TIMEOUT_MINUTES + 2L)

    /**
     * A ring cut short by power-off resumes on boot (anti-cheat, SPEC §6.7) only if it started
     * this recently; after longer the phone was simply off, and the ring is logged as missed.
     */
    val RESUME_AFTER_POWER_OFF: Duration = Duration.ofHours(3)

    /** Margin beyond the ring deadline before the wake lock self-releases. */
    val WAKE_LOCK_MARGIN: Duration = Duration.ofMinutes(1)
}
