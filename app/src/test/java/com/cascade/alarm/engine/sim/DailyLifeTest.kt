package com.cascade.alarm.engine.sim

import com.cascade.alarm.data.SnoozeState
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.Difficulty
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/** Whole mornings on the real engine (EngineSim): what rings, when, and what it asks. */
@RunWith(RobolectricTestRunner::class)
class DailyLifeTest {
    private val newYork = ZoneId.of("America/New_York")

    /** Sunday 18 October 2026, 22:00: the evening before a Monday. */
    private val sundayEvening = LocalDateTime.of(2026, 10, 18, 22, 0)

    private fun sim(start: LocalDateTime = sundayEvening, zone: ZoneId = newYork, block: suspend EngineSim.() -> Unit) = runTest {
        val sim = EngineSim(this as TestScope, start, zone)
        try {
            sim.block()
        } finally {
            sim.close()
        }
    }

    private fun day(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m)

    private val weekdays = DayOfWeek.entries.take(5).toSet()
    private val ladder = listOf(
        ChallengeConfig.Math(Difficulty.EASY, 2),
        ChallengeConfig.TypePhrase("up"),
        ChallengeConfig.Math(Difficulty.HARD, 3),
        ChallengeConfig.Memory(5),
    )

    // --- One alarm ---

    @Test
    fun `a daily alarm rings at its minute every day for two weeks, and nothing else rings`() = sim {
        group("Daily", "07:00", days = DayOfWeek.entries.toSet())
        repeat(14) { i ->
            advanceTo(day(19, 7, 1).plusDays(i.toLong()))
            dismiss()
        }
        // 19 Oct to 1 Nov; on 2 Nov at 06:00 the fifteenth has not come yet. (1 Nov is the end of
        // daylight saving time in New York, so this also crosses a fall-back night.)
        advanceTo(LocalDateTime.of(2026, 11, 2, 6, 0))
        assertEquals(List(14) { "07:00 A" }, ringLog())
        rings.forEachIndexed { i, r -> assertEquals(day(19, 7, 0).plusDays(i.toLong()).atZone(newYork).toInstant(), r.at) }
    }

    @Test
    fun `weekday alarms skip the weekend`() = sim {
        group("Work", "06:30", days = weekdays)
        repeat(7) { i ->
            advanceTo(day(19 + i, 23, 0))
            if (ringing != null) dismiss()
        }
        // Rang Monday to Friday (19–23), not on Saturday 24 or Sunday 25.
        assertEquals(5, rings.size)
        assertEquals((19..23).map { day(it, 6, 30).atZone(zone).toInstant() }, rings.map { it.at })
    }

    @Test
    fun `a one-shot alarm rings once, switches itself off, and never rings again`() = sim {
        val (_, ids) = group("Once", "06:00")
        advanceTo(day(19, 6, 0))
        assertEquals("A", ringing?.label)
        dismiss()
        assertEquals(false, alarm(ids[0]).isEnabled)
        assertTrue(alarmManager.pending.isEmpty())
        advanceTo(day(26, 12, 0))
        assertEquals(1, rings.size)
    }

    @Test
    fun `an alarm a minute away rings in a minute`() = sim {
        group("Soon", "22:01")
        advanceTo(day(18, 22, 1))
        assertEquals(listOf("22:01 A"), ringLog())
    }

    @Test
    fun `an alarm left ringing stops after its timeout and is logged as missed`() = sim {
        group("Heavy sleeper", "06:00", timeoutMinutes = 10)
        advanceTo(day(19, 6, 9))
        assertTrue(ringing != null)
        advanceTo(day(19, 6, 10))
        assertNull(ringing)
        assertNull(process.sound.playing)
        assertEquals(false, process.wakeLock.held)
        assertEquals(listOf("A FIRED", "A MISSED timeout"), story())
    }

    // --- A cascade ---

    @Test
    fun `asleep through a one-shot cascade, each alarm takes over and climbs the ladder`() = sim {
        group("Morning", "06:00", "06:10", "06:20", "06:30", ladder = ladder)
        advanceTo(day(19, 7, 0))
        assertEquals(listOf("06:00 A", "06:10 B", "06:20 C", "06:30 D"), ringLog())
        assertEquals(ladder, rings.map { it.challenge })
        assertEquals(listOf(0, 1, 2, 3), rings.map { it.position })
        assertEquals(listOf(4, 4, 4, 4), rings.map { it.total })
        assertTrue(rings.dropLast(1).all { it.snooze is SnoozeState.Offer })
        assertEquals(SnoozeState.NotOnLast, rings.last().snooze)
        // The last ring times out at 06:45 and nothing else rings.
        assertNull(ringing)
        assertEquals(
            listOf(
                "A FIRED", "B FIRED", "A MISSED superseded", "C FIRED", "B MISSED superseded",
                "D FIRED", "C MISSED superseded", "D MISSED timeout",
            ),
            story(),
        )
    }

    @Test
    fun `a repeating cascade climbs the ladder every morning, not only the first`() = sim {
        group("Morning", "06:00", "06:10", "06:20", days = weekdays, ladder = ladder)
        for (d in 19..21) {
            advanceTo(day(d, 6, 0)); dismiss()
            advanceTo(day(d, 6, 10)); dismiss()
            advanceTo(day(d, 6, 20)); dismiss()
        }
        assertEquals(9, rings.size)
        rings.chunked(3).forEach { morning ->
            assertEquals(ladder.take(3), morning.map { it.challenge })
            assertEquals(listOf(0, 1, 2), morning.map { it.position })
            assertEquals(listOf(3, 3, 3), morning.map { it.total })
        }
    }

    @Test
    fun `a one-shot cascade set up again the next evening climbs the ladder again`() = sim {
        val (groupId, ids) = group("Morning", "06:00", "06:10", "06:20", ladder = ladder)
        advanceTo(day(19, 6, 0)); dismiss()
        advanceTo(day(19, 6, 10)); dismiss()
        advanceTo(day(19, 6, 20)); dismiss()
        // That evening the user switches the three back on for Tuesday.
        advanceTo(day(19, 21, 0))
        ids.forEach { repo.setAlarmEnabled(it, true) }
        advanceTo(day(20, 6, 0)); dismiss()
        advanceTo(day(20, 6, 10)); dismiss()
        advanceTo(day(20, 6, 20)); dismiss()
        assertEquals(ladder.take(3) + ladder.take(3), rings.map { it.challenge })
        assertTrue(groupId.isNotEmpty())
    }

    @Test
    fun `an alarm switched off is left out, the one before it becomes the last`() = sim {
        val (_, ids) = group("Morning", "06:00", "06:10", "06:20", days = weekdays, ladder = ladder, wakeCheckMinutes = 3)
        repo.setAlarmEnabled(ids[2], false)
        advanceTo(day(19, 6, 0)); dismiss()
        advanceTo(day(19, 6, 10))
        assertEquals(1, ringing?.position)
        assertEquals(2, ringing?.total)
        assertEquals(SnoozeState.NotOnLast, ringing?.snooze)
        dismiss()
        // B is last now, so its dismissal is followed by the wake-up check.
        advanceTo(day(19, 6, 13))
        assertEquals(true, ringing?.isWakeCheck)
    }

    @Test
    fun `an alarm that repeats on other days is not part of todays cascade`() = sim {
        val (_, ids) = group("Morning", "06:00", "06:10", "06:20", days = weekdays, ladder = ladder, wakeCheckMinutes = 3)
        // C only rings on Saturdays.
        repo.saveAlarm(alarm(ids[2]).copy(daysOfWeek = setOf(DayOfWeek.SATURDAY)))
        advanceTo(day(19, 6, 0)); dismiss()
        advanceTo(day(19, 6, 10))
        assertEquals("B", ringing?.label)
        assertEquals(2, ringing?.total)
        assertEquals(SnoozeState.NotOnLast, ringing?.snooze)
        assertEquals(ladder[1], ringing?.challenge)
        dismiss()
        advanceTo(day(19, 6, 13))
        assertEquals(true, ringing?.isWakeCheck)
    }

    @Test
    fun `awake for every alarm, each rings in turn and none is superseded`() = sim {
        group("Morning", "06:00", "06:10", "06:20", ladder = ladder)
        advanceTo(day(19, 6, 1)); dismiss()
        advanceTo(day(19, 6, 12)); dismiss()
        advanceTo(day(19, 6, 20)); dismiss()
        assertEquals(listOf("06:00 A", "06:10 B", "06:20 C"), ringLog())
        assertEquals(listOf("A FIRED", "A DISMISSED", "B FIRED", "B DISMISSED", "C FIRED", "C DISMISSED"), story())
    }

    @Test
    fun `a cascade across midnight keeps its order and its ladder`() = sim(start = LocalDateTime.of(2026, 10, 18, 20, 0)) {
        group("Night shift", "23:50", "00:00", "00:10", days = DayOfWeek.entries.toSet(), ladder = ladder)
        advanceTo(day(18, 23, 50)); dismiss()
        advanceTo(day(19, 0, 0)); dismiss()
        advanceTo(day(19, 0, 10)); dismiss()
        assertEquals(listOf("23:50 A", "00:00 B", "00:10 C"), ringLog())
        assertEquals(ladder.take(3), rings.map { it.challenge })
        assertEquals(SnoozeState.NotOnLast, rings.last().snooze)
    }

    @Test
    fun `more alarms than rungs, the last rung repeats`() = sim {
        group("Morning", "06:00", "06:05", "06:10", "06:15", "06:20", "06:25", ladder = ladder.take(2))
        advanceTo(day(19, 7, 0))
        assertEquals(listOf(ladder[0]) + List(5) { ladder[1] }, rings.map { it.challenge })
    }

    @Test
    fun `two alarms of a group at the same minute, the later in the sequence is the one that rings`() = sim {
        group("Morning", "06:00", "06:00", ladder = ladder)
        advanceTo(day(19, 6, 0))
        assertEquals("B", ringing?.label)
        assertEquals(ladder[1], ringing?.challenge)
        dismiss()
        advanceTo(day(19, 7, 0))
        assertNull(ringing)
    }

    @Test
    fun `an empty ladder means the plain Dismiss button, and an alarms own challenge still applies`() = sim {
        val (_, ids) = group("Morning", "06:00", "06:10")
        repo.saveAlarm(alarm(ids[1]).copy(challengeOverride = ChallengeConfig.Steps(20)))
        advanceTo(day(19, 6, 0))
        assertNull(ringing?.challenge)
        dismiss()
        advanceTo(day(19, 6, 10))
        assertEquals(ChallengeConfig.Steps(20), ringing?.challenge)
    }

    // --- Wake-up check ---

    @Test
    fun `wake-up check, answered, the morning is over`() = sim {
        group("Morning", "06:00", "06:10", wakeCheckMinutes = 3)
        advanceTo(day(19, 6, 0)); dismiss()
        advanceTo(day(19, 6, 11)); dismiss()
        advanceTo(day(19, 6, 14))
        val check = checkNotNull(ringing)
        assertTrue(check.isWakeCheck)
        assertEquals("B", check.label)
        assertNull(check.challenge)
        assertTrue(process.sound.wakeCheckPrompt)
        dismiss()
        advanceTo(day(19, 12, 0))
        assertEquals(listOf("06:00 A", "06:10 B", "06:14 check B"), ringLog())
        assertEquals("B WAKE_CHECK_PASSED user", story().last())
    }

    @Test
    fun `wake-up check, missed, the last alarm rings again with its challenge, and its dismissal checks again`() = sim {
        group("Morning", "06:00", "06:10", wakeCheckMinutes = 3, ladder = ladder)
        advanceTo(day(19, 6, 0)); dismiss()
        advanceTo(day(19, 6, 11)); dismiss()
        advanceTo(day(19, 6, 15)) // the check came at 06:14 and had a minute
        val again = checkNotNull(ringing)
        assertEquals("B", again.label)
        assertEquals(false, again.isWakeCheck)
        assertEquals(ladder[1], again.challenge)
        assertEquals(SnoozeState.NotOnLast, again.snooze)
        assertEquals(Duration.ofMinutes(15), Duration.between(again.startedAt, again.deadline))
        dismiss()
        advanceTo(day(19, 6, 18))
        assertEquals(true, ringing?.isWakeCheck)
        dismiss()
        advanceTo(day(19, 12, 0))
        assertEquals(listOf("06:00 A", "06:10 B", "06:14 check B", "06:15 B", "06:18 check B"), ringLog())
    }

    @Test
    fun `giving up on a challenge still counts as up, and the wake-up check follows`() = sim {
        group("Morning", "06:00", wakeCheckMinutes = 2, ladder = ladder)
        advanceTo(day(19, 6, 5))
        giveUp()
        advanceTo(day(19, 6, 7))
        assertEquals(true, ringing?.isWakeCheck)
        assertTrue("A GAVE_UP user" in story())
    }
}
