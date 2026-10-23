package com.cascade.alarm.engine.ring

import com.cascade.alarm.data.db.ActiveRingEntity
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.EventType
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.schedule.Reconciler.FireResult
import com.cascade.alarm.engine.schedule.WakeCheck
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
 * - A wake-up check is a quiet prompt with a short deadline. Answering it ends the sequence;
 *   missing it (or powering off during it) rings the alarm again, whose dismissal checks again.
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
        if (result is FireResult.Duplicate || result is FireResult.Deleted) {
            EngineLog.i("fire ignored ($result) alarm=$alarmId scheduledFor=$scheduledFor")
            if (current == null) finishIdle()
            return@withLock
        }
        val ringResult = result as FireResult.Ring
        if (ringResult.wakeCheck && current != null && !current.isWakeCheck) {
            // Someone is up and handling an alarm; don't interrupt it with "are you awake?".
            EngineLog.i("wake check skipped while alarm=${current.alarmId} rings")
            return@withLock
        }
        if (current != null && (current.alarmId != alarmId || current.scheduledFor != scheduledFor)) {
            bestEffort { store.log(current.alarmId, EventType.MISSED, "reason" to "superseded", "by" to alarmId) }
        }
        val ring = ActiveRingEntity(alarmId = alarmId, scheduledFor = scheduledFor, startedAt = now(), isWakeCheck = ringResult.wakeCheck)
        bestEffort { store.setActiveRing(ring) }
        begin(ring, ringResult.alarm)
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
        if (afterBoot && ring.isWakeCheck) {
            failWakeCheck(ring.alarmId, alarm, "power_off")
            return@withLock
        }
        begin(ring, alarm)
    }

    /**
     * The user answered: dismisses an alarm (its challenge, if any, already done), or passes a
     * wake-up check. With [gaveUp] the alarm was ended through the challenge's escape hatch, which
     * is logged as GAVE_UP; either way the user is up, so the wake-up check still follows.
     */
    suspend fun dismiss(gaveUp: Boolean = false) = mutex.withLock {
        val active = state.current.value?.let { it.alarmId to it.isWakeCheck }
            ?: try { store.activeRing()?.let { it.alarmId to it.isWakeCheck } } catch (e: Exception) { null }
        if (active?.second == true) {
            finish(EventType.WAKE_CHECK_PASSED, "reason" to "user")
            return@withLock
        }
        if (gaveUp) finish(EventType.GAVE_UP, "reason" to "user") else finish(EventType.DISMISSED, "reason" to "user")
        if (active != null) bestEffort { store.afterDismiss(active.first) }
    }

    /** The alarm screen went to the background while ringing (SPEC §6.7). Sound returns to full. */
    suspend fun leftScreen() = mutex.withLock {
        val current = state.current.value ?: return@withLock
        sound.setDucked(false)
        bestEffort { store.log(current.alarmId, EventType.CHEAT_DETECTED, "reason" to "left_screen") }
    }

    /** Quieter while a challenge is being worked on (SPEC §6.8). */
    suspend fun duck(ducked: Boolean) = mutex.withLock {
        if (state.current.value?.challenge != null) sound.setDucked(ducked)
    }

    private suspend fun begin(ring: ActiveRingEntity, alarm: AlarmEntity?) {
        val timeout = if (ring.isWakeCheck) {
            WakeCheck.ANSWER_WINDOW
        } else {
            Duration.ofMinutes((alarm?.timeoutMinutes ?: RingDefaults.TIMEOUT_MINUTES).toLong())
        }
        val deadline = ring.startedAt.plus(timeout)
        val remaining = Duration.between(now(), deadline)
        if (remaining <= Duration.ZERO) {
            EngineLog.i("ring already past its deadline alarm=${ring.alarmId} wakeCheck=${ring.isWakeCheck}")
            if (ring.isWakeCheck) {
                failWakeCheck(ring.alarmId, alarm, "timeout")
            } else {
                finish(EventType.MISSED, "reason" to "timeout", ring = ring)
            }
            return
        }
        wakeLock.acquire(remaining.plus(RingDefaults.WAKE_LOCK_MARGIN))
        // Only shapes the screen; ringing must never wait on or fail because of it.
        val sequence = alarm?.let {
            try { store.sequencePosition(it) } catch (e: Exception) { null }
        } ?: SequencePosition(0, 1)
        val ringing = RingingAlarm(
            ring.alarmId, alarm?.label, ring.scheduledFor, deadline, ring.isWakeCheck,
            position = sequence.position, total = sequence.total,
            // Fail open: an alarm that could not be loaded rings with the plain Dismiss button.
            challenge = if (ring.isWakeCheck) null else alarm?.challengeOverride,
            startedAt = ring.startedAt,
        )
        state.set(ringing)
        presenter.show(ringing)
        sound.stop()
        if (ring.isWakeCheck) sound.startWakeCheck() else sound.start(alarm?.volumeRampSeconds ?: RingDefaults.RAMP_SECONDS)
        EngineLog.i("ringing alarm=${ring.alarmId} deadline=$deadline wakeCheck=${ring.isWakeCheck}")

        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(remaining.toMillis())
            mutex.withLock {
                if (state.current.value != ringing) return@withLock
                // This job is ending; keep begin() in failWakeCheck from cancelling it mid-way.
                timeoutJob = null
                if (ring.isWakeCheck) failWakeCheck(ring.alarmId, alarm, "timeout") else finish(EventType.MISSED, "reason" to "timeout")
            }
        }
    }

    /** A missed wake-up check rings [alarmId] again, as a new ring with the alarm's full timeout. */
    private suspend fun failWakeCheck(alarmId: String, alarm: AlarmEntity?, reason: String) {
        bestEffort { store.log(alarmId, EventType.WAKE_CHECK_FAILED, "reason" to reason) }
        val again = ActiveRingEntity(alarmId = alarmId, scheduledFor = now(), startedAt = now())
        bestEffort { store.setActiveRing(again) }
        bestEffort { store.log(alarmId, EventType.FIRED, "reason" to "wake_check_failed") }
        begin(again, alarm)
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
        try {
            // Clear the persisted ring before telling observers it's over, so "not ringing" in
            // memory always implies "not ringing" on disk.
            bestEffort { store.clearActiveRing() }
            if (alarmId != null) bestEffort { store.log(alarmId, type, *metadata) }
        } finally {
            state.set(null)
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
