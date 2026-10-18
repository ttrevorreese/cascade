package com.cascade.alarm.engine.ring

import com.cascade.alarm.data.db.ActiveRingEntity
import com.cascade.alarm.data.db.AlarmEntity
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
)

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
}

interface AlarmSoundPlayer {
    fun start(rampSeconds: Int)
    fun stop()
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
    const val RAMP_SECONDS = 30

    /** Held from the moment a fire arrives until the alarm's real timeout is known. */
    val INITIAL_WAKE_LOCK: Duration = Duration.ofMinutes(TIMEOUT_MINUTES + 2L)

    /** Margin beyond the ring deadline before the wake lock self-releases. */
    val WAKE_LOCK_MARGIN: Duration = Duration.ofMinutes(1)
}
