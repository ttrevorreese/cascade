package com.cascade.alarm.engine.ring

import com.cascade.alarm.data.db.ActiveRingEntity
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.EventType
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.schedule.Reconciler.FireResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/**
 * The lifetime of one ringing alarm, independent of the Android Service that hosts it.
 *
 * Rules:
 * - Fail open: if the database cannot be read, ring with defaults. Silence is the worst outcome.
 * - Every exit path (dismiss, timeout, duplicate, nothing to resume) stops sound and releases the
 *   wake lock, even if writing to the database fails.
 * - A ring interrupted by power-off resumes on boot with a fresh timeout and a CHEAT_DETECTED event.
 */
class RingCoordinator(
    private val scope: CoroutineScope,
    private val store: RingStore,
    private val now: () -> Instant,
    private val sound: AlarmSoundPlayer,
    private val wakeLock: RingWakeLock,
    private val presenter: RingPresenter,
    private val state: RingState,
) {
    private val mutex = Mutex()
    private var timeoutJob: Job? = null

    suspend fun onFire(alarmId: String, scheduledFor: Instant) = mutex.withLock {
        wakeLock.acquire(RingDefaults.INITIAL_WAKE_LOCK)
        val result = try {
            store.recordFire(alarmId, scheduledFor)
        } catch (e: Exception) {
            EngineLog.e("recordFire failed; ringing with defaults alarm=$alarmId", e)
            FireResult.Ring(null)
        }
        val current = state.current.value
        if (result is FireResult.Duplicate) {
            EngineLog.i("duplicate fire ignored alarm=$alarmId scheduledFor=$scheduledFor")
            if (current == null) finishIdle()
            return@withLock
        }
        if (current != null && (current.alarmId != alarmId || current.scheduledFor != scheduledFor)) {
            bestEffort { store.log(current.alarmId, EventType.MISSED, "reason" to "superseded", "by" to alarmId) }
        }
        val ring = ActiveRingEntity(alarmId = alarmId, scheduledFor = scheduledFor, startedAt = now())
        bestEffort { store.setActiveRing(ring) }
        begin(ring, (result as FireResult.Ring).alarm)
    }

    /** Called after boot, on a sticky restart, or when the app opens while a ring is persisted. */
    suspend fun onResume(afterBoot: Boolean) = mutex.withLock {
        val persisted = try {
            store.activeRing()
        } catch (e: Exception) {
            EngineLog.e("cannot read active ring", e)
            null
        }
        val current = state.current.value
        if (persisted == null) {
            if (current == null) finishIdle()
            return@withLock
        }
        if (current != null && current.alarmId == persisted.alarmId && current.scheduledFor == persisted.scheduledFor) {
            return@withLock
        }
        wakeLock.acquire(RingDefaults.INITIAL_WAKE_LOCK)
        var ring = persisted
        if (afterBoot) {
            ring = persisted.copy(startedAt = now())
            bestEffort { store.setActiveRing(ring) }
            bestEffort { store.log(ring.alarmId, EventType.CHEAT_DETECTED, "reason" to "power_off") }
        }
        val alarm = try {
            store.alarm(ring.alarmId)
        } catch (e: Exception) {
            null
        }
        begin(ring, alarm)
    }

    suspend fun dismiss() = mutex.withLock {
        finish(EventType.DISMISSED, "reason" to "user")
    }

    private suspend fun begin(ring: ActiveRingEntity, alarm: AlarmEntity?) {
        val timeout = Duration.ofMinutes((alarm?.timeoutMinutes ?: RingDefaults.TIMEOUT_MINUTES).toLong())
        val deadline = ring.startedAt.plus(timeout)
        val remaining = Duration.between(now(), deadline)
        if (remaining <= Duration.ZERO) {
            EngineLog.i("ring already past its deadline alarm=${ring.alarmId}")
            finish(EventType.MISSED, "reason" to "timeout", ring = ring)
            return
        }
        wakeLock.acquire(remaining.plus(RingDefaults.WAKE_LOCK_MARGIN))
        val ringing = RingingAlarm(ring.alarmId, alarm?.label, ring.scheduledFor, deadline)
        state.set(ringing)
        presenter.show(ringing)
        sound.stop()
        sound.start(alarm?.volumeRampSeconds ?: RingDefaults.RAMP_SECONDS)
        EngineLog.i("ringing alarm=${ring.alarmId} deadline=$deadline")

        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(remaining.toMillis())
            mutex.withLock {
                if (state.current.value == ringing) finish(EventType.MISSED, "reason" to "timeout")
            }
        }
    }

    /** Stops everything. Sound and wake lock are released even if the database writes fail. */
    private suspend fun finish(
        type: EventType,
        vararg metadata: Pair<String, Any?>,
        ring: ActiveRingEntity? = null,
    ) {
        sound.stop()
        timeoutJob?.cancel()
        timeoutJob = null
        val alarmId = state.current.value?.alarmId
            ?: ring?.alarmId
            ?: try { store.activeRing()?.alarmId } catch (e: Exception) { null }
        state.set(null)
        try {
            bestEffort { store.clearActiveRing() }
            if (alarmId != null) bestEffort { store.log(alarmId, type, *metadata) }
        } finally {
            finishIdle()
        }
    }

    private fun finishIdle() {
        wakeLock.release()
        presenter.finish()
    }

    private inline fun bestEffort(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            EngineLog.e("ring bookkeeping failed", e)
        }
    }
}
