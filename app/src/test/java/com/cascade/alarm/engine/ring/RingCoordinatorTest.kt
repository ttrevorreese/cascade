package com.cascade.alarm.engine.ring

import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.db.ActiveRingEntity
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.EventType
import com.cascade.alarm.engine.schedule.Reconciler.FireResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RingCoordinatorTest {
    private val epoch = Instant.parse("2026-10-19T11:00:00Z")
    private val scheduled = epoch

    private fun alarm(id: String = "a", timeout: Int = 15, ramp: Int = 30) = AlarmEntity(
        id = id, groupId = "g", positionInGroup = 0, hour = 7, minute = 0, daysOfWeek = emptySet(),
        label = "Wake", isEnabled = true,
        volumeRampSeconds = ramp, timeoutMinutes = timeout,
    )

    private class FakeStore : RingStore {
        val alarms = mutableMapOf<String, AlarmEntity>()
        var active: ActiveRingEntity? = null
        val events = mutableListOf<Pair<EventType, Map<String, Any?>>>()
        var fired = mutableSetOf<Pair<String, Instant>>()
        val deleted = mutableSetOf<String>()
        /** Triggers that are wake-up checks rather than the alarm itself. */
        val wakeChecks = mutableSetOf<Pair<String, Instant>>()
        val dismissed = mutableListOf<String>()
        var failRecordFire = false
        var failWrites = false
        var state: RingState? = null
        /** What observers could see at the moment the persisted ring was cleared. */
        var stateWhenCleared: RingingAlarm? = null

        override suspend fun recordFire(alarmId: String, scheduledFor: Instant): FireResult {
            if (failRecordFire) error("db unavailable")
            if (alarmId in deleted) return FireResult.Deleted
            if (!fired.add(alarmId to scheduledFor)) return FireResult.Duplicate
            return FireResult.Ring(alarms[alarmId], wakeCheck = (alarmId to scheduledFor) in wakeChecks)
        }
        override suspend fun alarm(alarmId: String) = alarms[alarmId]
        override suspend fun activeRing() = active
        override suspend fun setActiveRing(ring: ActiveRingEntity) {
            if (failWrites) error("db unavailable")
            active = ring
        }
        override suspend fun clearActiveRing() {
            if (failWrites) error("db unavailable")
            stateWhenCleared = state?.current?.value
            active = null
        }
        override suspend fun log(alarmId: String, type: EventType, vararg metadata: Pair<String, Any?>) {
            if (failWrites) error("db unavailable")
            events += type to metadata.toMap()
        }
        override suspend fun afterDismiss(alarmId: String) {
            if (failWrites) error("db unavailable")
            dismissed += alarmId
        }
        var sequence = SequencePosition(0, 1)
        var failSequence = false
        override suspend fun sequencePosition(alarm: AlarmEntity): SequencePosition {
            if (failSequence) error("db unavailable")
            return sequence
        }
        fun types() = events.map { it.first }
    }

    private class FakeSound : AlarmSoundPlayer {
        var playing = false
        var lastRamp: Int? = null
        var starts = 0
        var checking = false
        override fun start(rampSeconds: Int) { playing = true; lastRamp = rampSeconds; starts++ }
        var quieted = false
        override fun startWakeCheck() { checking = true }
        override fun setDucked(ducked: Boolean) { quieted = ducked }
        override fun stop() { playing = false; checking = false; quieted = false }
    }

    private class FakeWakeLock : RingWakeLock {
        var held = false
        var lastTimeout: Duration? = null
        override fun acquire(timeout: Duration) { held = true; lastTimeout = timeout }
        override fun release() { held = false }
    }

    private class FakePresenter : RingPresenter {
        var shown: RingingAlarm? = null
        var finished = 0
        override fun show(ringing: RingingAlarm) { shown = ringing }
        override fun finish() { shown = null; finished++ }
    }

    private inner class Harness(val scope: TestScope) {
        val store = FakeStore().apply { alarms["a"] = alarm() }
        val sound = FakeSound()
        val wakeLock = FakeWakeLock()
        val presenter = FakePresenter()
        val state = RingState().also { store.state = it }
        val coordinator = RingCoordinator(
            scope = scope.backgroundScope,
            store = store,
            now = { epoch.plusMillis(scope.testScheduler.currentTime) },
            sound = sound,
            wakeLock = wakeLock,
            presenter = presenter,
            state = state,
        )

        fun assertRinging() {
            assertTrue(sound.playing, "sound playing")
            assertTrue(wakeLock.held, "wake lock held")
            assertNotNull(presenter.shown, "notification shown")
            assertNotNull(state.current.value, "state published")
        }

        fun assertStopped() {
            assertFalse(sound.playing, "sound stopped")
            assertFalse(wakeLock.held, "wake lock released")
            assertNull(presenter.shown, "notification removed")
            assertNull(state.current.value, "state cleared")
        }
    }

    private fun harness(block: suspend Harness.() -> Unit) = runTest { Harness(this).block() }

    @Test
    fun `fire starts sound, holds the wake lock and persists the ring`() = harness {
        coordinator.onFire("a", scheduled)
        assertRinging()
        assertEquals(30, sound.lastRamp)
        assertEquals(ActiveRingEntity(alarmId = "a", scheduledFor = scheduled, startedAt = epoch), store.active)
        assertEquals(Duration.ofMinutes(16), wakeLock.lastTimeout) // 15 min timeout + 1 min margin
    }

    @Test
    fun `dismiss stops audio, releases the wake lock and clears the ring`() = harness {
        coordinator.onFire("a", scheduled)
        coordinator.dismiss()
        assertStopped()
        assertNull(store.active)
        assertEquals(EventType.DISMISSED, store.types().last())
        assertEquals(1, presenter.finished)
    }

    @Test
    fun `persisted ring is cleared before observers are told nothing is ringing`() = harness {
        // Otherwise a process death in between resurrects a dismissed alarm on next start.
        coordinator.onFire("a", scheduled)
        coordinator.dismiss()
        assertNotNull(store.stateWhenCleared)
        assertNull(state.current.value)
    }

    @Test
    fun `dismiss releases everything even when the database fails`() = harness {
        coordinator.onFire("a", scheduled)
        store.failWrites = true
        coordinator.dismiss()
        assertStopped()
    }

    @Test
    fun `rings until the timeout then stops and logs MISSED`() = harness {
        coordinator.onFire("a", scheduled)
        scope.advanceTimeBy(Duration.ofMinutes(15).toMillis() - 1)
        scope.runCurrent()
        assertRinging()
        scope.advanceTimeBy(2)
        scope.runCurrent()
        assertStopped()
        assertNull(store.active)
        assertEquals(EventType.MISSED, store.types().last())
        assertEquals("timeout", store.events.last().second["reason"])
    }

    @Test
    fun `per-alarm timeout and ramp are honoured`() = harness {
        store.alarms["a"] = alarm(timeout = 5, ramp = 90)
        coordinator.onFire("a", scheduled)
        assertEquals(90, sound.lastRamp)
        scope.advanceTimeBy(Duration.ofMinutes(5).toMillis() + 1)
        scope.runCurrent()
        assertStopped()
    }

    @Test
    fun `dismissing cancels the pending timeout`() = harness {
        coordinator.onFire("a", scheduled)
        coordinator.dismiss()
        val eventsAfterDismiss = store.events.size
        scope.advanceTimeBy(Duration.ofMinutes(30).toMillis())
        scope.runCurrent()
        assertEquals(eventsAfterDismiss, store.events.size)
    }

    @Test
    fun `duplicate fire while idle releases the wake lock without ringing`() = harness {
        store.fired += "a" to scheduled
        coordinator.onFire("a", scheduled)
        assertStopped()
        assertEquals(0, sound.starts)
        assertEquals(1, presenter.finished)
    }

    @Test
    fun `duplicate fire while ringing leaves the ring alone`() = harness {
        coordinator.onFire("a", scheduled)
        coordinator.onFire("a", scheduled)
        assertRinging()
        assertEquals(1, sound.starts)
    }

    @Test
    fun `database failure on fire still rings with defaults`() = harness {
        store.failRecordFire = true
        store.failWrites = true
        coordinator.onFire("a", scheduled)
        assertRinging()
        assertEquals(RingDefaults.RAMP_SECONDS, sound.lastRamp)
        scope.advanceTimeBy(Duration.ofMinutes(RingDefaults.TIMEOUT_MINUTES.toLong()).toMillis() + 1)
        scope.runCurrent()
        assertStopped() // the timeout still fires even though nothing could be logged
    }

    @Test
    fun `unknown alarm still rings with defaults`() = harness {
        coordinator.onFire("ghost", scheduled)
        assertRinging()
        assertEquals(RingDefaults.RAMP_SECONDS, sound.lastRamp)
    }

    @Test
    fun `fire for a deleted alarm is ignored`() = harness {
        store.deleted += "a"
        coordinator.onFire("a", scheduled)
        assertStopped()
        assertEquals(0, sound.starts)
    }

    @Test
    fun `a second alarm replaces the ringing one and the first is logged as superseded`() = harness {
        store.alarms["b"] = alarm(id = "b")
        coordinator.onFire("a", scheduled)
        scope.advanceTimeBy(60_000)
        coordinator.onFire("b", scheduled.plusSeconds(60))
        assertRinging()
        assertEquals("b", state.current.value?.alarmId)
        assertEquals("b", store.active?.alarmId)
        val superseded = store.events.single { it.first == EventType.MISSED }
        assertEquals("superseded", superseded.second["reason"])
    }

    @Test
    fun `power-off during a ring resumes it after boot with a fresh timeout and logs a cheat`() = harness {
        val ringStartedLongAgo = epoch.minus(Duration.ofHours(3))
        store.active = ActiveRingEntity(alarmId = "a", scheduledFor = scheduled, startedAt = ringStartedLongAgo)
        coordinator.onResume(afterBoot = true)
        assertRinging()
        assertEquals(EventType.CHEAT_DETECTED, store.types().single())
        assertEquals("power_off", store.events.single().second["reason"])
        assertEquals(epoch, store.active?.startedAt)
        scope.advanceTimeBy(Duration.ofMinutes(14).toMillis())
        scope.runCurrent()
        assertRinging()
    }

    @Test
    fun `resume after process death keeps the original deadline`() = harness {
        store.active = ActiveRingEntity(alarmId = "a", scheduledFor = scheduled, startedAt = epoch.minus(Duration.ofMinutes(10)))
        coordinator.onResume(afterBoot = false)
        assertRinging()
        assertEquals(Duration.ofMinutes(6), wakeLock.lastTimeout) // 5 min left + 1 min margin
        scope.advanceTimeBy(Duration.ofMinutes(5).toMillis() + 1)
        scope.runCurrent()
        assertStopped()
        assertFalse(store.types().contains(EventType.CHEAT_DETECTED))
    }

    @Test
    fun `resume after process death past the deadline times out without ringing`() = harness {
        store.active = ActiveRingEntity(alarmId = "a", scheduledFor = scheduled, startedAt = epoch.minus(Duration.ofMinutes(20)))
        coordinator.onResume(afterBoot = false)
        assertStopped()
        assertEquals(0, sound.starts)
        assertEquals(EventType.MISSED, store.types().single())
        assertNull(store.active)
    }

    @Test
    fun `resume with nothing active finishes idle`() = harness {
        coordinator.onResume(afterBoot = true)
        assertStopped()
        assertEquals(1, presenter.finished)
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun `resume while already ringing is a no-op`() = harness {
        coordinator.onFire("a", scheduled)
        coordinator.onResume(afterBoot = true) // e.g. BOOT_COMPLETED after LOCKED_BOOT_COMPLETED
        assertRinging()
        assertEquals(1, sound.starts)
        assertFalse(store.types().contains(EventType.CHEAT_DETECTED))
    }

    @Test
    fun `dismiss with no in-memory ring clears a stale persisted ring`() = harness {
        store.active = ActiveRingEntity(alarmId = "a", scheduledFor = scheduled, startedAt = epoch)
        coordinator.dismiss()
        assertNull(store.active)
        assertEquals(EventType.DISMISSED, store.types().single())
        assertStopped()
    }

    // --- Wake-up check ---

    private val checkAt = scheduled.plusSeconds(180)

    private suspend fun Harness.fireCheck() {
        store.wakeChecks += "a" to checkAt
        coordinator.onFire("a", checkAt)
    }

    @Test
    fun `dismissing an alarm lets the store decide on a wake-up check`() = harness {
        coordinator.onFire("a", scheduled)
        coordinator.dismiss()
        assertEquals(listOf("a"), store.dismissed)
    }

    @Test
    fun `an alarm that times out unanswered gets no wake-up check`() = harness {
        coordinator.onFire("a", scheduled)
        scope.advanceTimeBy(Duration.ofMinutes(16).toMillis())
        scope.runCurrent()
        assertTrue(store.dismissed.isEmpty())
    }

    @Test
    fun `a wake-up check prompts quietly for a minute`() = harness {
        fireCheck()
        val ringing = state.current.value!!
        assertTrue(ringing.isWakeCheck)
        assertEquals(epoch.plusSeconds(60), ringing.deadline)
        assertTrue(sound.checking)
        assertEquals(0, sound.starts) // no alarm sound
        assertTrue(wakeLock.held)
        assertEquals(true, store.active?.isWakeCheck)
    }

    @Test
    fun `answering the check ends it without scheduling another`() = harness {
        fireCheck()
        coordinator.dismiss()
        assertStopped()
        assertNull(store.active)
        assertEquals(EventType.WAKE_CHECK_PASSED, store.types().last())
        assertTrue(store.dismissed.isEmpty())
        scope.advanceTimeBy(Duration.ofMinutes(5).toMillis())
        scope.runCurrent()
        assertEquals(0, sound.starts)
    }

    @Test
    fun `an unanswered check rings the alarm again, and dismissing that checks again`() = harness {
        fireCheck()
        scope.advanceTimeBy(Duration.ofSeconds(60).toMillis() + 1)
        scope.runCurrent()
        assertRinging()
        assertEquals(1, sound.starts)
        assertFalse(state.current.value!!.isWakeCheck)
        assertEquals(false, store.active?.isWakeCheck)
        assertTrue(store.types().containsAll(listOf(EventType.WAKE_CHECK_FAILED, EventType.FIRED)))
        // The repeat ring has the alarm's full timeout.
        scope.advanceTimeBy(Duration.ofMinutes(14).toMillis())
        scope.runCurrent()
        assertRinging()
        coordinator.dismiss()
        assertEquals(listOf("a"), store.dismissed)
    }

    @Test
    fun `a check that comes due while an alarm is ringing is skipped`() = harness {
        store.alarms["b"] = alarm(id = "b")
        coordinator.onFire("b", scheduled)
        fireCheck()
        assertEquals("b", state.current.value?.alarmId)
        assertFalse(state.current.value!!.isWakeCheck)
        assertFalse(store.types().contains(EventType.MISSED))
    }

    @Test
    fun `power-off during a check rings the alarm after boot`() = harness {
        store.active = ActiveRingEntity(alarmId = "a", scheduledFor = checkAt, startedAt = epoch.minusSeconds(30), isWakeCheck = true)
        coordinator.onResume(afterBoot = true)
        assertRinging()
        assertEquals(1, sound.starts)
        assertTrue(store.types().containsAll(listOf(EventType.CHEAT_DETECTED, EventType.WAKE_CHECK_FAILED)))
    }

    @Test
    fun `a check resumed after its minute ran out rings the alarm`() = harness {
        store.active = ActiveRingEntity(alarmId = "a", scheduledFor = checkAt, startedAt = epoch.minusSeconds(90), isWakeCheck = true)
        coordinator.onResume(afterBoot = false)
        assertRinging()
        assertEquals(EventType.WAKE_CHECK_FAILED, store.types().first())
    }

    @Test
    fun `the ringing alarm carries its place in the sequence`() = harness {
        store.sequence = SequencePosition(2, 4)
        coordinator.onFire("a", scheduled)
        assertEquals(2, state.current.value?.position)
        assertEquals(4, state.current.value?.total)
    }

    @Test
    fun `failing to read the sequence still rings`() = harness {
        store.failSequence = true
        coordinator.onFire("a", scheduled)
        assertRinging()
        assertEquals(1, state.current.value?.total)
    }

    // --- Challenges ---

    @Test
    fun `the ringing alarm carries its challenge and start time`() = harness {
        store.alarms["a"] = alarm().copy(challengeOverride = ChallengeConfig.Math())
        coordinator.onFire("a", scheduled)
        assertEquals(ChallengeConfig.Math(), state.current.value?.challenge)
        assertEquals(epoch, state.current.value?.startedAt)
    }

    @Test
    fun `a wake-up check never has a challenge`() = harness {
        store.alarms["a"] = alarm().copy(challengeOverride = ChallengeConfig.Math())
        store.wakeChecks += "a" to scheduled
        coordinator.onFire("a", scheduled)
        assertNull(state.current.value?.challenge)
    }

    @Test
    fun `giving up ends the ring, is recorded, and the wake-up check still follows`() = harness {
        store.alarms["a"] = alarm().copy(challengeOverride = ChallengeConfig.Math())
        coordinator.onFire("a", scheduled)
        coordinator.dismiss(gaveUp = true)
        assertStopped()
        assertEquals(EventType.GAVE_UP, store.types().last())
        assertEquals(listOf("a"), store.dismissed)
    }

    @Test
    fun `leaving the alarm screen is recorded and the sound comes back up`() = harness {
        store.alarms["a"] = alarm().copy(challengeOverride = ChallengeConfig.Math())
        coordinator.onFire("a", scheduled)
        coordinator.duck(true)
        assertTrue(sound.quieted)
        coordinator.leftScreen()
        assertFalse(sound.quieted)
        assertRinging()
        assertEquals("left_screen", store.events.last { it.first == EventType.CHEAT_DETECTED }.second["reason"])
    }

    @Test
    fun `only an alarm with a challenge ducks, and leaving with nothing ringing logs nothing`() = harness {
        coordinator.onFire("a", scheduled)
        coordinator.duck(true)
        assertFalse(sound.quieted)
        coordinator.dismiss()
        val before = store.events.size
        coordinator.leftScreen()
        assertEquals(before, store.events.size)
    }
}
