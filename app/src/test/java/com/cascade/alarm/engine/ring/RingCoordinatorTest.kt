package com.cascade.alarm.engine.ring

import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.Escalation
import com.cascade.alarm.data.challenge.EscalationPolicy
import com.cascade.alarm.data.db.ActiveRingEntity
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.EventType
import com.cascade.alarm.data.SnoozeState
import com.cascade.alarm.data.sound.SoundConfig
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

    private fun alarm(id: String = "a", timeout: Int = 15) = AlarmEntity(
        id = id, groupId = "g", positionInGroup = 0, hour = 7, minute = 0, daysOfWeek = emptySet(),
        label = "Wake", isEnabled = true, timeoutMinutes = timeout,
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
        var failChallenge = false
        /** The real resolution with an empty ladder: the alarm's own override, None meaning plain. */
        override suspend fun challengeFor(alarm: AlarmEntity): ChallengeConfig? {
            if (failChallenge) error("db unavailable")
            return Escalation.resolve(alarm, EscalationPolicy(), 0)
        }
        override suspend fun sequencePosition(alarm: AlarmEntity): SequencePosition {
            if (failSequence) error("db unavailable")
            return sequence
        }
        var sound = RingSound(SoundConfig.BuiltIn("klaxon"), rampSeconds = 60, startPercent = 30)
        var failSound = false
        val soundReads = mutableListOf<String>()
        override suspend fun soundFor(alarm: AlarmEntity): RingSound {
            soundReads += alarm.id
            if (failSound) error("db unavailable")
            return sound
        }
        val snoozed = mutableListOf<Pair<String, Instant>>()
        val snoozesCleared = mutableListOf<String>()
        var failSnooze = false
        override suspend fun snooze(alarmId: String, at: Instant) {
            if (failSnooze) error("db unavailable")
            snoozed += alarmId to at
        }
        override suspend fun clearSnoozes(alarmId: String) { snoozesCleared += alarmId }
        var theme: String? = "citrus"
        var failTheme = false
        override suspend fun themeFor(alarm: AlarmEntity): String? {
            if (failTheme) error("db unavailable")
            return theme
        }
        fun types() = events.map { it.first }
    }

    private class FakeSound : AlarmSoundPlayer {
        var playing = false
        var last: RingSound? = null
        var starts = 0
        var checking = false
        /** Reports a fallback as the real player would; set to simulate an unplayable sound. */
        var fallbackOnStart: Pair<String, String>? = null
        override fun start(sound: RingSound, onFallback: (reason: String, next: String) -> Unit) {
            playing = true; last = sound; starts++
            fallbackOnStart?.let { (reason, next) -> onFallback(reason, next) }
        }
        var quieted = false
        override fun startWakeCheck() { checking = true }
        override fun setDucked(ducked: Boolean) { quieted = ducked }
        override fun stop() { playing = false; checking = false; quieted = false }
    }

    private class FakeSpeaker : LabelSpeaker {
        val started = mutableListOf<Triple<String, String?, Instant>>()
        var speaking = false
        var held = false
        override fun start(alarmId: String, label: String?, at: Instant) { started += Triple(alarmId, label, at); speaking = true }
        override fun setPaused(paused: Boolean) { held = paused }
        override fun stop() { speaking = false; held = false }
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
        val speaker = FakeSpeaker()
        val state = RingState().also { store.state = it }
        val coordinator = RingCoordinator(
            scope = scope.backgroundScope,
            store = store,
            now = { epoch.plusMillis(scope.testScheduler.currentTime) },
            sound = sound,
            wakeLock = wakeLock,
            presenter = presenter,
            state = state,
            speaker = speaker,
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
        assertEquals(store.sound, sound.last)
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
    fun `the alarm's timeout and its group's sound are honoured`() = harness {
        store.alarms["a"] = alarm(timeout = 5)
        coordinator.onFire("a", scheduled)
        assertEquals(store.sound, sound.last)
        scope.advanceTimeBy(Duration.ofMinutes(5).toMillis() + 1)
        scope.runCurrent()
        assertStopped()
    }

    @Test
    fun `if the group's sound cannot be read the alarm still rings, with the defaults`() = harness {
        store.failSound = true
        coordinator.onFire("a", scheduled)
        assertRinging()
        assertEquals(RingSound(), sound.last)
    }

    @Test
    fun `a sound that cannot play is recorded with what plays instead`() = harness {
        sound.fallbackOnStart = "file_missing" to "classic"
        coordinator.onFire("a", scheduled)
        scope.runCurrent()
        assertRinging()
        val fallback = store.events.single { it.first == EventType.SOUND_FALLBACK }.second
        assertEquals(mapOf("scheduledFor" to scheduled, "reason" to "file_missing", "next" to "classic"), fallback)
    }

    @Test
    fun `the ring carries its group's theme, and rings without it when it cannot be read`() = harness {
        coordinator.onFire("a", scheduled)
        assertEquals("citrus", state.current.value?.groupThemeId)
        coordinator.dismiss()
        store.failTheme = true
        coordinator.onFire("a", scheduled.plusSeconds(60))
        assertRinging()
        assertNull(state.current.value?.groupThemeId)
    }

    @Test
    fun `an alarm before the last offers snooze, the last does not`() = harness {
        store.sequence = SequencePosition(0, 3)
        coordinator.onFire("a", scheduled)
        assertEquals(SnoozeState.Offer(5, 3), state.current.value?.snooze)
        coordinator.dismiss()
        store.sequence = SequencePosition(2, 3)
        coordinator.onFire("a", scheduled.plusSeconds(60))
        assertEquals(SnoozeState.NotOnLast, state.current.value?.snooze)
    }

    @Test
    fun `snoozing rings again in its minutes, ends this ring and keeps the count`() = harness {
        store.sequence = SequencePosition(0, 3)
        coordinator.onFire("a", scheduled)
        coordinator.snooze()
        assertStopped()
        assertEquals(listOf("a" to epoch.plus(Duration.ofMinutes(5))), store.snoozed)
        assertEquals(EventType.SNOOZED, store.types().last())
        assertTrue(store.snoozesCleared.isEmpty(), "a snooze keeps the count")
    }

    @Test
    fun `a snooze that cannot be saved keeps the alarm ringing`() = harness {
        store.sequence = SequencePosition(0, 3)
        store.failSnooze = true
        coordinator.onFire("a", scheduled)
        coordinator.snooze()
        assertRinging()
        assertFalse(EventType.SNOOZED in store.types())
    }

    @Test
    fun `snooze is ignored where it is not offered`() = harness {
        coordinator.onFire("a", scheduled) // alone in its group, so it is the last
        coordinator.snooze()
        assertRinging()
        assertTrue(store.snoozed.isEmpty())
    }

    @Test
    fun `dismissing resets the snooze count and records the challenge's misses`() = harness {
        store.alarms["a"] = alarm().copy(challengeOverride = ChallengeConfig.Math())
        coordinator.onFire("a", scheduled)
        coordinator.dismiss(misses = 2)
        assertEquals(listOf("a"), store.snoozesCleared)
        val dismissed = store.events.last { it.first == EventType.DISMISSED }.second
        assertEquals("Math", dismissed["challenge"])
        assertEquals(2, dismissed["misses"])
    }

    @Test
    fun `a wake-up check reads no sound and plays none`() = harness {
        store.wakeChecks += "a" to scheduled
        coordinator.onFire("a", scheduled)
        assertTrue(sound.checking)
        assertEquals(0, sound.starts)
        assertTrue(store.soundReads.isEmpty())
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
        assertEquals(RingSound(), sound.last)
        scope.advanceTimeBy(Duration.ofMinutes(RingDefaults.TIMEOUT_MINUTES.toLong()).toMillis() + 1)
        scope.runCurrent()
        assertStopped() // the timeout still fires even though nothing could be logged
    }

    @Test
    fun `unknown alarm still rings with defaults`() = harness {
        coordinator.onFire("ghost", scheduled)
        assertRinging()
        assertEquals(RingSound(), sound.last)
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

    @Test
    fun `if the ladder cannot be read the alarm's own challenge still applies`() = harness {
        store.alarms["a"] = alarm().copy(challengeOverride = ChallengeConfig.TypePhrase("up"))
        store.failChallenge = true
        coordinator.onFire("a", scheduled)
        assertRinging()
        assertEquals(ChallengeConfig.TypePhrase("up"), state.current.value?.challenge)
    }

    @Test
    fun `an alarm set to read aloud says its label from the moment it rings, and stops with it`() = harness {
        store.alarms["a"] = alarm().copy(readLabelAloud = true, label = "Take your pills")
        coordinator.onFire("a", scheduled)
        assertEquals(listOf(Triple("a", "Take your pills", epoch)), speaker.started)
        assertTrue(speaker.speaking)
        coordinator.dismiss()
        assertFalse(speaker.speaking)
    }

    @Test
    fun `alarms that do not read aloud, and wake-up checks, stay quiet`() = harness {
        coordinator.onFire("a", scheduled)
        assertTrue(speaker.started.isEmpty())
        coordinator.dismiss()
        store.alarms["a"] = alarm().copy(readLabelAloud = true)
        val checkAt = scheduled.plusSeconds(120)
        store.wakeChecks += "a" to checkAt
        coordinator.onFire("a", checkAt)
        assertTrue(state.current.value!!.isWakeCheck)
        assertTrue(speaker.started.isEmpty(), "a wake-up check only vibrates")
    }

    @Test
    fun `reading pauses while a challenge is solved and resumes on leaving the screen`() = harness {
        store.alarms["a"] = alarm().copy(readLabelAloud = true, challengeOverride = ChallengeConfig.Math())
        coordinator.onFire("a", scheduled)
        coordinator.duck(true)
        assertTrue(speaker.held)
        coordinator.leftScreen()
        assertFalse(speaker.held)
    }

    @Test
    fun `a snooze stops the reading too`() = harness {
        store.alarms["a"] = alarm().copy(readLabelAloud = true)
        store.sequence = SequencePosition(0, 3)
        coordinator.onFire("a", scheduled)
        coordinator.snooze()
        assertFalse(speaker.speaking)
    }
}
