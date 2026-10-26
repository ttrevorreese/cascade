package com.cascade.alarm.engine.sim

import com.cascade.alarm.data.SnoozeState
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.Difficulty
import com.cascade.alarm.engine.ring.RingDefaults
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Everything that can go wrong around a morning, on the real engine (EngineSim): snoozes,
 * reboots, the process dying, the clock and time zone changing, edits while things are pending,
 * and alarms getting in each other's way.
 */
@RunWith(RobolectricTestRunner::class)
class TroubleTest {
    private val newYork = ZoneId.of("America/New_York")
    private val sundayEvening = LocalDateTime.of(2026, 10, 18, 22, 0)

    private fun sim(start: LocalDateTime = sundayEvening, zone: ZoneId = newYork, block: suspend EngineSim.() -> Unit) = runTest {
        val sim = EngineSim(this as TestScope, start, zone)
        try {
            sim.block()
        } finally {
            sim.close()
        }
    }

    private fun day(d: Int, h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 10, d, h, m, s)

    private val weekdays = DayOfWeek.entries.take(5).toSet()
    private val ladder = listOf(
        ChallengeConfig.Math(Difficulty.EASY, 2),
        ChallengeConfig.TypePhrase("up"),
        ChallengeConfig.Math(Difficulty.HARD, 3),
    )

    // --- Snooze ---

    @Test
    fun `snooze rings the same alarm again after its minutes, with the same challenge, until the snoozes run out`() = sim {
        group("Morning", "06:00", "07:00", days = weekdays, ladder = ladder, snoozeMinutes = 5, snoozeLimit = 2)
        advanceTo(day(19, 6, 0))
        assertEquals(SnoozeState.Offer(5, 2), ringing?.snooze)
        snooze()
        assertNull(ringing)
        advanceTo(day(19, 6, 5))
        assertEquals("A", ringing?.label)
        assertEquals(ladder[0], ringing?.challenge)
        assertEquals(0, ringing?.position)
        assertEquals(SnoozeState.Offer(5, 1), ringing?.snooze)
        snooze()
        advanceTo(day(19, 6, 10))
        assertEquals(SnoozeState.UsedUp, ringing?.snooze)
        dismiss()
        assertEquals(listOf("06:00 A", "06:05 A", "06:10 A"), ringLog())
        // Tomorrow starts with every snooze again.
        advanceTo(day(20, 6, 0))
        assertEquals(SnoozeState.Offer(5, 2), ringing?.snooze)
    }

    @Test
    fun `snoozes used are given back whatever ends the ring, even a takeover or the alarm being switched off`() = sim {
        val (_, ids) = group("Morning", "06:00", "06:20", days = weekdays, snoozeMinutes = 15, snoozeLimit = 2)
        advanceTo(day(19, 6, 0)); snooze()
        advanceTo(day(19, 6, 15)) // A again, one snooze left; then B takes over at 06:20
        assertEquals(SnoozeState.Offer(15, 1), ringing?.snooze)
        advanceTo(day(19, 6, 21)); dismiss()
        assertEquals("B", rings.last().label)
        advanceTo(day(20, 6, 0))
        assertEquals(SnoozeState.Offer(15, 2), ringing?.snooze)
        snooze()
        repo.setAlarmEnabled(ids[0], false)
        repo.setAlarmEnabled(ids[0], true)
        advanceTo(day(20, 6, 21)); dismiss()
        advanceTo(day(21, 6, 0))
        assertEquals(SnoozeState.Offer(15, 2), ringing?.snooze)
    }

    @Test
    fun `a one-shot alarm that switched itself off still comes back from its snooze`() = sim {
        val (_, ids) = group("Morning", "06:00", "07:00")
        advanceTo(day(19, 6, 0)); snooze()
        assertFalse(alarm(ids[0]).isEnabled)
        advanceTo(day(19, 6, 5))
        assertEquals("A", ringing?.label)
        dismiss()
        advanceTo(day(19, 6, 30))
        assertEquals(listOf("06:00 A", "06:05 A"), ringLog())
    }

    @Test
    fun `the last alarm never snoozes, and an alarm with snooze off offers none`() = sim {
        group("Morning", "06:00", "06:10", snooze = false)
        advanceTo(day(19, 6, 0))
        assertEquals(SnoozeState.Off, ringing?.snooze)
        snooze() // the button is not there; a stray command does nothing
        assertNotNull(ringing)
        dismiss()
        group("Other", "07:00", "07:10")
        advanceTo(day(19, 7, 10))
        assertEquals(SnoozeState.NotOnLast, ringing?.snooze)
    }

    @Test
    fun `a snooze that comes due while a later alarm rings is skipped, and the later one keeps ringing`() = sim {
        group("Morning", "06:00", "06:10", ladder = ladder, snoozeMinutes = 15)
        advanceTo(day(19, 6, 0)); snooze() // A again at 06:15
        advanceTo(day(19, 6, 16))
        assertEquals("B", ringing?.label)
        assertEquals(ladder[1], ringing?.challenge)
        assertEquals(listOf("06:00 A", "06:10 B"), ringLog())
        assertTrue("A MISSED skipped" in story())
    }

    @Test
    fun `a snooze still pending when a later alarm is dismissed is dropped`() = sim {
        group("Morning", "06:00", "06:10", "06:40", snoozeMinutes = 15)
        advanceTo(day(19, 6, 0)); snooze() // A again at 06:15
        advanceTo(day(19, 6, 11)); dismiss() // B, not the last
        advanceTo(day(19, 6, 39))
        assertNull(ringing)
        advanceTo(day(19, 6, 41))
        assertEquals(listOf("06:00 A", "06:10 B", "06:40 C"), ringLog())
    }

    @Test
    fun `turning the group off, the alarm off, or deleting it cancels a pending snooze`() = sim {
        val (g1, _) = group("One", "06:00", "08:00")
        val (_, ids2) = group("Two", "06:01", "08:00")
        val (_, ids3) = group("Three", "06:02", "08:00")
        advanceTo(day(19, 6, 0)); snooze()
        advanceTo(day(19, 6, 1)); snooze()
        advanceTo(day(19, 6, 2)); snooze()
        repo.setGroupArmed(g1, false)
        repo.setAlarmEnabled(ids2[0], false)
        repo.deleteAlarm(ids3[0])
        advanceTo(day(19, 7, 59))
        assertEquals(3, rings.size)
        assertNull(ringing)
    }

    // --- Power and process ---

    @Test
    fun `powered off mid-ring, it rings again on boot with a fresh timeout and the same challenge`() = sim {
        group("Morning", "06:00", "06:10", ladder = ladder)
        advanceTo(day(19, 6, 10, 30))
        reboot(offFor = Duration.ofMinutes(2))
        val again = checkNotNull(ringing)
        assertEquals("B", again.label)
        assertEquals(ladder[1], again.challenge)
        assertEquals(Duration.ofMinutes(15), Duration.between(again.startedAt, again.deadline))
        assertEquals(now(), again.startedAt)
        assertTrue("B CHEAT_DETECTED power_off" in story())
        dismiss()
        advanceTo(day(19, 12, 0))
        assertNull(ringing)
    }

    @Test
    fun `off for hours mid-ring, it does not ring when the phone comes back that evening`() = sim {
        group("Morning", "06:00", days = weekdays)
        advanceTo(day(19, 6, 1))
        reboot(offFor = Duration.ofHours(10))
        assertNull(ringing)
        assertTrue("A MISSED power_off" in story())
        advanceTo(day(20, 6, 0))
        assertEquals("A", ringing?.label)
    }

    @Test
    fun `off over the alarm and back within 15 minutes, it rings at once, later than that, it is logged as missed`() = sim {
        group("Morning", "06:00", days = weekdays)
        advanceTo(day(19, 5, 55))
        reboot(offFor = Duration.ofMinutes(14))
        assertEquals("A", ringing?.label)
        assertEquals(day(19, 6, 0).atZone(newYork).toInstant(), ringing?.scheduledFor)
        dismiss()
        advanceTo(day(20, 5, 55))
        reboot(offFor = Duration.ofMinutes(25))
        assertNull(ringing)
        assertTrue(story().count { it == "A MISSED not_fired" } == 1)
        advanceTo(day(21, 6, 0))
        assertEquals("A", ringing?.label)
    }

    @Test
    fun `back on in the middle of a cascade, the latest alarm that is due rings, not an earlier one`() = sim {
        group("Morning", "06:00", "06:10", "06:20", "06:40", ladder = ladder)
        advanceTo(day(19, 5, 50))
        reboot(offFor = Duration.ofMinutes(36)) // back at 06:26: A is too late, B and C are due
        assertEquals("C", ringing?.label)
        assertEquals(ladder[2], ringing?.challenge)
        assertEquals(2, ringing?.position)
        assertTrue("A MISSED not_fired" in story())
        dismiss()
        advanceTo(day(19, 6, 40))
        assertEquals("D", ringing?.label)
    }

    @Test
    fun `a reboot in the night keeps the morning, snoozes and wake-up checks included`() = sim {
        group("Morning", "06:00", "06:30", days = weekdays, wakeCheckMinutes = 3)
        advanceTo(day(19, 3, 0))
        reboot()
        advanceTo(day(19, 6, 0)); snooze()
        reboot()
        advanceTo(day(19, 6, 5))
        assertEquals("A", ringing?.label)
        dismiss()
        advanceTo(day(19, 6, 31)); dismiss()
        reboot()
        advanceTo(day(19, 6, 34))
        assertEquals(true, ringing?.isWakeCheck)
    }

    @Test
    fun `powered off during the wake-up check, the alarm rings after boot`() = sim {
        group("Morning", "06:00", wakeCheckMinutes = 3)
        advanceTo(day(19, 6, 1)); dismiss()
        advanceTo(day(19, 6, 4, 10))
        assertEquals(true, ringing?.isWakeCheck)
        reboot(offFor = Duration.ofMinutes(1))
        assertEquals(false, ringing?.isWakeCheck)
        assertEquals("A", ringing?.label)
    }

    @Test
    fun `the process dies mid-ring, the ring comes back with its original deadline`() = sim {
        group("Morning", "06:00", timeoutMinutes = 10)
        advanceTo(day(19, 6, 3))
        val before = checkNotNull(ringing)
        killProcess()
        val after = checkNotNull(ringing)
        assertEquals(before.deadline, after.deadline)
        advanceTo(day(19, 6, 10))
        assertNull(ringing)
        assertEquals("A MISSED timeout", story().last())
    }

    @Test
    fun `the process dies while idle, alarms still ring`() = sim {
        group("Morning", "06:00")
        advanceTo(day(19, 2, 0))
        killProcess()
        advanceTo(day(19, 6, 0))
        assertEquals("A", ringing?.label)
    }

    @Test
    fun `force stop drops the alarms, as Android does, opening the app puts them back`() = sim {
        group("Morning", "06:00", days = weekdays)
        advanceTo(day(19, 2, 0))
        forceStop()
        assertTrue(alarmManager.pending.isEmpty())
        openApp()
        advanceTo(day(19, 6, 0))
        assertEquals("A", ringing?.label)
    }

    // --- Clock and time zone ---

    @Test
    fun `spring forward, an alarm in the skipped hour rings when the hour ends, then normally`() = sim(start = LocalDateTime.of(2026, 3, 7, 22, 0)) {
        group("Night", "02:30", days = DayOfWeek.entries.toSet())
        advanceTo(LocalDateTime.of(2026, 3, 8, 3, 1)); dismiss()
        advanceTo(LocalDateTime.of(2026, 3, 9, 2, 31)); dismiss()
        assertEquals(listOf("03:00 A", "02:30 A"), ringLog())
    }

    @Test
    fun `fall back, an alarm in the repeated hour rings once`() = sim(start = LocalDateTime.of(2026, 10, 31, 22, 0)) {
        group("Night", "01:30", days = DayOfWeek.entries.toSet())
        advanceTo(LocalDateTime.of(2026, 11, 1, 1, 31)); dismiss()
        advanceBy(Duration.ofHours(2)) // through the second 01:30
        assertEquals(1, rings.size)
        advanceTo(LocalDateTime.of(2026, 11, 2, 1, 31))
        assertEquals(2, rings.size)
    }

    @Test
    fun `travel, after a time zone change the alarm rings at its time in the new zone`() = sim {
        group("Morning", "07:00", days = DayOfWeek.entries.toSet())
        setZone(ZoneId.of("Europe/London"))
        advanceTo(LocalDateTime.of(2026, 10, 19, 7, 1))
        assertEquals(listOf("07:00 A"), ringLog())
        assertEquals(ZoneId.of("Europe/London"), zone)
    }

    @Test
    fun `clock moved forward past an alarm by a few minutes rings it at once, by half an hour logs it missed`() = sim {
        group("Morning", "06:00", "06:40", days = weekdays)
        advanceTo(day(19, 5, 58))
        setClock(day(19, 6, 7))
        assertEquals("A", ringing?.label)
        dismiss()
        advanceTo(day(20, 5, 58))
        setClock(day(20, 6, 31))
        assertNull(ringing)
        assertTrue("A MISSED not_fired" in story())
        advanceTo(day(20, 6, 40))
        assertEquals("B", ringing?.label)
    }

    @Test
    fun `clock set back after an alarm rang, it does not ring twice, and tomorrow is still scheduled`() = sim {
        group("Morning", "07:00", days = weekdays)
        advanceTo(day(19, 7, 1)); dismiss()
        advanceTo(day(19, 7, 30))
        setClock(day(19, 6, 30))
        advanceTo(day(19, 7, 30))
        assertEquals(1, rings.size)
        advanceTo(day(20, 7, 1))
        assertEquals(2, rings.size)
    }

    // --- Edits while things are pending ---

    @Test
    fun `an alarm moved to later today after it rang rings again today`() = sim {
        val (_, ids) = group("Morning", "07:00", days = weekdays)
        advanceTo(day(19, 7, 1)); dismiss()
        repo.saveAlarm(alarm(ids[0]).copy(hour = 9))
        advanceTo(day(19, 9, 0))
        assertEquals(listOf("07:00 A", "09:00 A"), ringLog())
    }

    @Test
    fun `turning on a group or an alarm after its time today waits for tomorrow, without a late ring`() = sim {
        val (g, ids) = group("Morning", "06:00", days = weekdays)
        repo.setGroupArmed(g, false)
        advanceTo(day(19, 6, 5))
        repo.setGroupArmed(g, true)
        repo.setAlarmEnabled(ids[0], false)
        repo.setAlarmEnabled(ids[0], true)
        openApp()
        advanceTo(day(19, 23, 0))
        assertTrue(rings.isEmpty())
        advanceTo(day(20, 6, 0))
        assertEquals(1, rings.size)
    }

    @Test
    fun `moving the first alarm moves the rest, and they ring at the new times`() = sim {
        val (_, ids) = group("Morning", "06:00", "06:10", "06:20", days = weekdays)
        repo.saveAlarm(alarm(ids[0]).copy(hour = 6, minute = 30))
        advanceTo(day(19, 6, 30)); dismiss()
        advanceTo(day(19, 6, 40)); dismiss()
        advanceTo(day(19, 6, 50)); dismiss()
        assertEquals(listOf("06:30 A", "06:40 B", "06:50 C"), ringLog())
    }

    @Test
    fun `an alarm moved past another takes its place in the ladder`() = sim {
        val (_, ids) = group("Morning", "06:00", "06:10", "06:20", ladder = ladder, labels = listOf("A", "B", "C"))
        // Turn off "move the rest", then move A to 06:15: the order is now B, A, C.
        repo.setShiftWithFirst(db.alarmDao().get(ids[0])!!.groupId, false)
        repo.saveAlarm(alarm(ids[0]).copy(minute = 15))
        advanceTo(day(19, 6, 10)); dismiss()
        advanceTo(day(19, 6, 15)); dismiss()
        advanceTo(day(19, 6, 20)); dismiss()
        assertEquals(listOf("06:10 B", "06:15 A", "06:20 C"), ringLog())
        assertEquals(ladder, rings.map { it.challenge })
    }

    @Test
    fun `a ladder changed overnight applies in the morning`() = sim {
        val (g, _) = group("Morning", "06:00", ladder = ladder)
        repo.setEscalationPolicy(g, com.cascade.alarm.data.challenge.EscalationPolicy(listOf(ChallengeConfig.Steps(30))))
        advanceTo(day(19, 6, 0))
        assertEquals(ChallengeConfig.Steps(30), ringing?.challenge)
    }

    @Test
    fun `deleting the ringing alarm leaves the ring to be dismissed, and nothing rings after`() = sim {
        val (g, ids) = group("Morning", "06:00", "06:10", days = weekdays)
        advanceTo(day(19, 6, 0))
        repo.deleteAlarm(ids[0])
        assertNotNull(ringing)
        dismiss()
        repo.deleteGroup(g)
        advanceTo(day(21, 12, 0))
        assertEquals(1, rings.size)
        assertTrue(alarmManager.pending.isEmpty())
    }

    @Test
    fun `an alarm deleted after its trigger was handed over does not ring`() = sim {
        val (_, ids) = group("Morning", "06:00")
        val trigger = alarmManager.pending.getValue(ids[0])
        repo.deleteAlarm(ids[0])
        process.coordinator.onFire(ids[0], trigger) // a delivery already on its way
        assertNull(ringing)
    }

    // --- Alarms in each other's way ---

    @Test
    fun `two groups at the same minute, one ring to dismiss, the other logged`() = sim {
        group("One", "06:00", labels = listOf("X"))
        group("Two", "06:00", labels = listOf("Y"))
        advanceTo(day(19, 6, 0))
        assertNotNull(ringing)
        dismiss()
        advanceTo(day(19, 7, 0))
        assertNull(ringing)
        assertEquals(1, story().count { it.endsWith("MISSED superseded") })
    }

    @Test
    fun `another groups alarm takes over a ring, and a wake-up check due mid-ring is skipped`() = sim {
        group("Early", "06:00", wakeCheckMinutes = 2, labels = listOf("E"))
        group("Late", "06:03", labels = listOf("L"))
        advanceTo(day(19, 6, 1)); dismiss() // E's check due at 06:03
        advanceTo(day(19, 6, 3, 30))
        assertEquals("L", ringing?.label)
        assertFalse(ringing!!.isWakeCheck)
        dismiss()
        advanceTo(day(19, 7, 0))
        assertEquals(listOf("06:00 E", "06:03 L"), ringLog())
    }

    @Test
    fun `the same trigger delivered twice rings once`() = sim {
        val (_, ids) = group("Morning", "06:00", days = weekdays)
        val trigger = alarmManager.pending.getValue(ids[0])
        advanceTo(day(19, 6, 0))
        process.coordinator.onFire(ids[0], trigger)
        assertEquals(1, rings.size)
        dismiss()
        process.coordinator.onFire(ids[0], trigger)
        assertNull(ringing)
        advanceTo(day(20, 6, 0))
        assertEquals(2, rings.size)
    }

    @Test
    fun `the test alarm rings in seconds and leaves the groups alone`() = sim {
        group("Morning", "06:00", days = weekdays)
        repo.ringTestAlarmIn(10)
        advanceBy(Duration.ofSeconds(10))
        assertEquals("22:00:10", ringLog().single().substringBefore(" "))
        dismiss()
        advanceTo(day(19, 6, 0))
        assertEquals("A", ringing?.label)
    }

    @Test
    fun `the wake lock lasts as long as the longest ring`() = sim {
        group("Morning", "06:00", timeoutMinutes = 60)
        advanceTo(day(19, 6, 0))
        advanceTo(day(19, 6, 59))
        assertTrue(process.wakeLock.held)
        assertNotNull(ringing)
        advanceTo(day(19, 7, 0))
        assertNull(ringing)
        assertFalse(process.wakeLock.held)
        assertTrue(RingDefaults.INITIAL_WAKE_LOCK < Duration.ofMinutes(60))
    }
}
