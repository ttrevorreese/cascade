package com.cascade.alarm.engine.sim

import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.Difficulty
import com.cascade.alarm.engine.schedule.GroupTiming
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Skip next, pause until, next time only, and two groups ringing at once (2026-10-26), on the real
 * engine (EngineSim). Starts Sunday 18 October 2026, 22:00, New York.
 */
@RunWith(RobolectricTestRunner::class)
class ScheduleChangesTest {
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
    private val everyDay = DayOfWeek.entries.toSet()
    private val ladder = listOf(
        ChallengeConfig.Math(Difficulty.EASY, 2),
        ChallengeConfig.TypePhrase("up"),
        ChallengeConfig.Math(Difficulty.HARD, 3),
    )

    /** Dismisses every ring until [until], returning what rang. */
    private suspend fun EngineSim.morning(until: LocalDateTime): List<String> {
        val from = rings.size
        while (true) {
            val next = alarmManager.pending.values.minOrNull()
            if (next == null || next.isAfter(until.atZone(zone).toInstant())) break
            advanceTo(next)
            if (ringing != null) dismiss()
        }
        advanceTo(until)
        return ringLog(from)
    }

    // --- Skip next ---

    @Test
    fun `skip next skips the whole next morning and the one after rings as normal, ladder and all`() = sim {
        val (groupId, _) = group("Morning", "06:00", "06:10", "06:20", days = weekdays, ladder = ladder)
        assertTrue(repo.skipNext(groupId))
        assertEquals(emptyList<String>(), morning(day(19, 12, 0)))
        advanceTo(day(20, 6, 0))
        assertEquals("A", ringing?.label)
        assertEquals(ladder[0], ringing?.challenge)
        dismiss()
        advanceTo(day(20, 6, 20))
        assertEquals("C", ringing?.label)
        assertEquals(ladder[2], ringing?.challenge)
        assertEquals(2, ringing?.position)
        dismiss()
        assertEquals(listOf("06:00 A", "06:10 B", "06:20 C"), ringLog())
    }

    @Test
    fun `skip next twice skips two mornings, and a weekend in between does not count`() = sim(start = LocalDateTime.of(2026, 10, 22, 22, 0)) {
        val (groupId, _) = group("Work", "06:00", "06:15", days = weekdays)
        assertTrue(repo.skipNext(groupId)) // Friday 23
        assertTrue(repo.skipNext(groupId)) // Monday 26
        assertEquals(emptyList<String>(), morning(day(26, 23, 0)))
        assertEquals(listOf("06:00 A", "06:15 B"), morning(day(27, 7, 0)))
    }

    @Test
    fun `skip next in the middle of a morning skips only what is left of it`() = sim {
        val (groupId, _) = group("Morning", "06:00", "06:10", "06:20", days = weekdays)
        advanceTo(day(19, 6, 0)); dismiss()
        assertTrue(repo.skipNext(groupId))
        assertEquals(listOf("06:00 A"), ringLog())
        assertEquals(listOf("06:00 A", "06:10 B", "06:20 C"), morning(day(20, 7, 0)))
    }

    @Test
    fun `skip next leaves a pending snooze alone, and back to normal undoes the skip`() = sim {
        val (groupId, _) = group("Morning", "06:00", "06:30", days = weekdays)
        advanceTo(day(19, 6, 0)); snooze()
        repo.skipNext(groupId) // the rest of this morning: 06:30
        advanceTo(day(19, 6, 5))
        assertEquals("A", ringing?.label)
        dismiss()
        repo.resumeSchedule(groupId)
        advanceTo(day(19, 6, 30))
        assertEquals("B", ringing?.label)
    }

    @Test
    fun `a skipped one-shot alarm rings the day after instead`() = sim {
        val (groupId, _) = group("Once", "07:00")
        repo.skipNext(groupId)
        assertEquals(emptyList<String>(), morning(day(19, 12, 0)))
        assertEquals(listOf("07:00 A"), morning(day(20, 12, 0)))
        assertFalse(alarmByLabel("A").isEnabled)
        assertEquals(emptyList<String>(), morning(day(25, 12, 0)))
    }

    @Test
    fun `a skip survives a reboot and a time zone change`() = sim {
        val (groupId, _) = group("Morning", "06:00", "06:10", days = everyDay)
        repo.skipNext(groupId)
        reboot(Duration.ofMinutes(5))
        advanceTo(day(19, 3, 0))
        setZone(ZoneId.of("America/Chicago")) // an hour earlier: 02:00 local, still before 06:00
        assertEquals(emptyList<String>(), morning(day(19, 12, 0)))
        assertEquals(listOf("06:00 A", "06:10 B"), morning(day(20, 7, 0)))
    }

    // --- Pause until ---

    @Test
    fun `pause until a date rings nothing before it and everything from it`() = sim {
        val (groupId, _) = group("Morning", "06:00", "06:10", days = everyDay)
        assertTrue(repo.pauseUntil(groupId, LocalDate.of(2026, 10, 22)))
        assertEquals(emptyList<String>(), morning(day(21, 23, 59)))
        assertEquals(listOf("06:00 A", "06:10 B"), morning(day(22, 7, 0)))
        openApp()
        assertNull("the pause is cleared once over", db.alarmGroupDao().get(groupId)!!.skipUntil)
    }

    @Test
    fun `a pause cannot start today or in the past`() = sim {
        val (groupId, _) = group("Morning", "06:00", days = everyDay)
        assertFalse(repo.pauseUntil(groupId, LocalDate.of(2026, 10, 18)))
        assertFalse(repo.pauseUntil(groupId, LocalDate.of(2026, 10, 1)))
        assertEquals(listOf("06:00 A"), morning(day(19, 7, 0)))
    }

    @Test
    fun `turning the group off and on again keeps the pause`() = sim {
        val (groupId, _) = group("Morning", "06:00", days = everyDay)
        repo.pauseUntil(groupId, LocalDate.of(2026, 10, 21))
        repo.setGroupArmed(groupId, false)
        repo.setGroupArmed(groupId, true)
        assertEquals(emptyList<String>(), morning(day(20, 23, 0)))
        assertEquals(listOf("06:00 A"), morning(day(21, 7, 0)))
    }

    // --- Next time only ---

    @Test
    fun `next time only moves the whole next morning, keeps its ladder and wake-up check, then goes back`() = sim {
        val (groupId, _) = group("Morning", "06:00", "06:10", "06:20", days = weekdays, ladder = ladder, wakeCheckMinutes = 2)
        val result = repo.moveNextMorning(groupId, LocalTime.of(7, 30))
        assertTrue(result is GroupTiming.MoveResult.Moved)
        advanceTo(day(19, 6, 30))
        assertNull("nothing at the usual times", ringing)
        advanceTo(day(19, 7, 30))
        assertEquals("A", ringing?.label); assertEquals(ladder[0], ringing?.challenge); dismiss()
        advanceTo(day(19, 7, 40))
        assertEquals("B", ringing?.label); assertEquals(ladder[1], ringing?.challenge); dismiss()
        advanceTo(day(19, 7, 50))
        assertEquals("C", ringing?.label); assertEquals(ladder[2], ringing?.challenge)
        assertEquals(2, ringing?.position); assertEquals(3, ringing?.total)
        advanceTo(day(19, 7, 51)); dismiss()
        advanceTo(day(19, 7, 53))
        assertTrue("the wake-up check follows the last moved alarm", ringing?.isWakeCheck == true)
        dismiss()
        assertEquals(listOf("06:00 A", "06:10 B", "06:20 C", "06:22 check C"), morning(day(20, 7, 0)))
    }

    @Test
    fun `next time only can be earlier, and not into the past`() = sim {
        val (groupId, _) = group("Morning", "06:00", "06:10", days = weekdays)
        assertEquals(GroupTiming.MoveResult.InThePast, repo.moveNextMorning(groupId, LocalTime.of(21, 0)))
        assertTrue(repo.moveNextMorning(groupId, LocalTime.of(5, 15)) is GroupTiming.MoveResult.Moved)
        assertEquals(listOf("05:15 A", "05:25 B"), morning(day(19, 12, 0)))
        assertEquals(listOf("06:00 A", "06:10 B"), morning(day(20, 12, 0)))
    }

    @Test
    fun `next time only across midnight rings the night before`() = sim(start = LocalDateTime.of(2026, 10, 18, 20, 0)) {
        val (groupId, _) = group("Early", "00:30", "00:40", days = everyDay)
        assertTrue(repo.moveNextMorning(groupId, LocalTime.of(23, 50)) is GroupTiming.MoveResult.Moved)
        assertEquals(listOf("23:50 A", "00:00 B"), morning(day(19, 3, 0)))
        assertEquals(listOf("00:30 A", "00:40 B"), morning(day(20, 3, 0)))
    }

    @Test
    fun `a moved morning survives a reboot, a snooze and the process dying`() = sim {
        val (groupId, _) = group("Morning", "06:00", "06:30", days = weekdays, snoozeMinutes = 5)
        repo.moveNextMorning(groupId, LocalTime.of(8, 0))
        reboot(Duration.ofHours(2))
        advanceTo(day(19, 8, 0))
        assertEquals("A", ringing?.label)
        snooze()
        killProcess()
        advanceTo(day(19, 8, 5))
        assertEquals("A", ringing?.label)
        dismiss()
        advanceTo(day(19, 8, 30))
        assertEquals("B", ringing?.label)
        dismiss()
        assertEquals(listOf("08:00 A", "08:05 A", "08:30 B"), ringLog())
    }

    @Test
    fun `changing the group's times drops a one-off move`() = sim {
        val (groupId, _) = group("Morning", "06:00", "06:10", days = weekdays)
        repo.moveNextMorning(groupId, LocalTime.of(9, 0))
        repo.shiftGroup(groupId, 5)
        assertEquals(listOf("06:05 A", "06:15 B"), morning(day(19, 12, 0)))
    }

    @Test
    fun `skip next replaces a one-off move, and a move after a skip moves the morning after`() = sim {
        val (groupId, _) = group("Morning", "06:00", days = everyDay)
        repo.moveNextMorning(groupId, LocalTime.of(9, 0))
        repo.skipNext(groupId)
        assertEquals(emptyList<String>(), morning(day(19, 12, 0)))
        repo.moveNextMorning(groupId, LocalTime.of(7, 0))
        assertEquals(listOf("07:00 A"), morning(day(20, 12, 0)))
        assertEquals(listOf("06:00 A"), morning(day(21, 12, 0)))
    }

    // --- Two groups at once (2026-10-26: the one that asks more rings) ---

    @Test
    fun `two groups at the same minute ring the one with the harder challenge, whichever comes first`() {
        for (hardFirst in listOf(true, false)) sim {
            val names = if (hardFirst) listOf("Hard", "Easy") else listOf("Easy", "Hard")
            names.forEach { name ->
                if (name == "Hard") group("Hard", "06:00", labels = listOf("H"), ladder = listOf(ChallengeConfig.Math(Difficulty.HARD, 3)))
                else group("Easy", "06:00", labels = listOf("E"))
            }
            advanceTo(day(19, 6, 0))
            assertEquals("hard first: $hardFirst", "H", ringing?.label)
            dismiss()
            advanceTo(day(19, 7, 0))
            assertNull(ringing)
            assertTrue(story().any { it == "E MISSED covered" || it == "E MISSED superseded" })
        }
    }

    @Test
    fun `answering the ring that covered another group's last alarm still brings that group's wake-up check`() = sim {
        group("Easy", "06:00", labels = listOf("E"), wakeCheckMinutes = 2)
        group("Hard", "06:00", labels = listOf("H"), ladder = listOf(ChallengeConfig.Math(Difficulty.HARD, 3)))
        advanceTo(day(19, 6, 0))
        assertEquals("H", ringing?.label)
        advanceTo(day(19, 6, 1)); dismiss()
        advanceTo(day(19, 6, 3))
        assertTrue(ringing?.isWakeCheck == true)
        assertEquals("E", ringing?.label)
    }

    @Test
    fun `an easier alarm of another group arriving mid-ring is covered, a harder one takes over`() = sim {
        group("Mid", "06:00", labels = listOf("M"), ladder = listOf(ChallengeConfig.TypePhrase("up")))
        group("Easy", "06:02", labels = listOf("E"))
        group("Hard", "06:04", labels = listOf("H"), ladder = listOf(ChallengeConfig.Math(Difficulty.HARD, 3)), timeoutMinutes = 30)
        advanceTo(day(19, 6, 3))
        assertEquals("M", ringing?.label)
        advanceTo(day(19, 6, 4))
        assertEquals("H", ringing?.label)
        dismiss()
        assertEquals(listOf("06:00 M", "06:04 H"), ringLog())
    }

    @Test
    fun `with the same challenge, a group's last alarm is not covered by another's first`() = sim {
        group("Last", "06:00", labels = listOf("L"))
        group("Sequence", "06:00", "06:10", labels = listOf("S1", "S2"))
        advanceTo(day(19, 6, 0))
        assertEquals("L", ringing?.label)
        dismiss()
        advanceTo(day(19, 6, 10))
        assertEquals("S2", ringing?.label)
    }

    @Test
    fun `the test alarm never covers a real one`() = sim {
        group("Morning", "06:00", "06:10", labels = listOf("A", "B"))
        advanceTo(day(19, 5, 59, 50))
        repo.ringTestAlarmIn(5)
        advanceTo(day(19, 5, 59, 55))
        assertNotNull(ringing)
        advanceTo(day(19, 6, 0))
        assertEquals("A", ringing?.label)
    }
}
