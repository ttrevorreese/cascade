package com.cascade.alarm.engine.ring

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
        var failRecordFire = false
        var failWrites = false
        var state: RingState? = null
        /** What observers could see at the moment the persisted ring was cleared. */
        var stateWhenCleared: RingingAlarm? = null

        override suspend fun recordFire(alarmId: String, scheduledFor: Instant): FireResult {
            if (failRecordFire) error("db unavailable")
            if (alarmId in deleted) return FireResult.Deleted
            if (!fired.add(alarmId to scheduledFor)) return FireResult.Duplicate
            return FireResult.Ring(alarms[alarmId])
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
        fun types() = events.map { it.first }
    }

    private class FakeSound : AlarmSoundPlayer {
        var playing = false
        var lastRamp: Int? = null
        var starts = 0
        override fun start(rampSeconds: Int) { playing = true; lastRamp = rampSeconds; starts++ }
        override fun stop() { playing = false }
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
}
