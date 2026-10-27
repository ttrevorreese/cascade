package com.cascade.alarm.engine.schedule

import com.cascade.alarm.data.db.AlarmEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class GroupTimingTest {
    private val zone = ZoneId.of("America/New_York")
    private val everyDay = DayOfWeek.entries.toSet()

    private fun at(d: Int, h: Int, m: Int) = local(d, h, m).atZone(zone).toInstant()
    private fun local(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m)
    private fun alarm(id: String, h: Int, m: Int, days: Set<DayOfWeek> = everyDay, on: Boolean = true) = AlarmEntity(
        id = id, groupId = "g", positionInGroup = 0, hour = h, minute = m, daysOfWeek = days,
        label = null, isEnabled = on, timeoutMinutes = 15,
    )

    private val sundayNight = at(18, 22, 0)
    private val six = LocalTime.of(6, 0)

    @Test
    fun `the plain timing is exactly the regular next occurrence`() {
        assertEquals(NextOccurrence.after(six, everyDay, sundayNight, zone), GroupTiming.NONE.next(six, everyDay, sundayNight, zone))
    }

    @Test
    fun `a skip passes over occurrences before it and stops at the first after`() {
        val timing = GroupTiming(skipUntil = local(19, 6, 0).plusSeconds(1))
        assertEquals(at(20, 6, 0), timing.next(six, everyDay, sundayNight, zone))
    }

    @Test
    fun `a long pause does not run out of days to look at`() {
        val timing = GroupTiming(skipUntil = LocalDate.of(2027, 3, 1).atStartOfDay())
        assertEquals(LocalDateTime.of(2027, 3, 1, 6, 0).atZone(zone).toInstant(), timing.next(six, everyDay, sundayNight, zone))
    }

    @Test
    fun `a ring moved later is still due after its usual time has passed`() {
        val timing = GroupTiming(onceFrom = local(19, 6, 0), onceShiftMinutes = 90)
        assertEquals(at(19, 7, 30), timing.next(six, everyDay, at(19, 6, 30), zone))
        // Once it has rung, the next is the usual time again.
        assertEquals(at(20, 6, 0), timing.next(six, everyDay, at(19, 7, 30), zone))
    }

    @Test
    fun `a ring moved earlier does not ring again at its usual time`() {
        val timing = GroupTiming(onceFrom = local(19, 6, 0), onceShiftMinutes = -45)
        assertEquals(at(19, 5, 15), timing.next(six, everyDay, sundayNight, zone))
        assertEquals(at(20, 6, 0), timing.next(six, everyDay, at(19, 5, 15), zone))
    }

    @Test
    fun `skip next covers the morning's alarms that are on and nothing after`() {
        val alarms = listOf(alarm("a", 6, 0), alarm("b", 6, 10), alarm("c", 6, 20, on = false))
        val timing = GroupTiming.skipNext(alarms, GroupTiming.NONE, sundayNight, zone)!!
        assertEquals(local(19, 6, 10).plusSeconds(1), timing.skipUntil)
        assertEquals(at(20, 6, 0), timing.next(six, everyDay, sundayNight, zone))
    }

    @Test
    fun `skip next with nothing on has nothing to skip`() {
        assertNull(GroupTiming.skipNext(listOf(alarm("a", 6, 0, on = false)), GroupTiming.NONE, sundayNight, zone))
    }

    @Test
    fun `a pause starts at midnight of the chosen day and must be after today`() {
        assertEquals(local(21, 0, 0), GroupTiming.pauseUntil(LocalDate.of(2026, 10, 21), sundayNight, zone)!!.skipUntil)
        assertNull(GroupTiming.pauseUntil(LocalDate.of(2026, 10, 18), sundayNight, zone))
    }

    @Test
    fun `next time only moves from the morning's first alarm the short way round`() {
        val alarms = listOf(alarm("a", 6, 0), alarm("b", 6, 10))
        val moved = GroupTiming.moveNext(alarms, GroupTiming.NONE, LocalTime.of(7, 30), sundayNight, zone) as GroupTiming.MoveResult.Moved
        assertEquals(local(19, 6, 0), moved.timing.onceFrom)
        assertEquals(90, moved.timing.onceShiftMinutes)
        assertEquals(at(19, 7, 40), moved.timing.next(LocalTime.of(6, 10), everyDay, sundayNight, zone))
        // The short way from 06:00 to 23:30 is back 6.5 hours, to Sunday night, still ahead at 22:00.
        val back = GroupTiming.moveNext(alarms, GroupTiming.NONE, LocalTime.of(23, 30), sundayNight, zone) as GroupTiming.MoveResult.Moved
        assertEquals(-390, back.timing.onceShiftMinutes)
        // Back 8.5 hours would be 21:30 on Sunday, already gone.
        assertEquals(GroupTiming.MoveResult.InThePast, GroupTiming.moveNext(alarms, GroupTiming.NONE, LocalTime.of(21, 30), sundayNight, zone))
    }

    @Test
    fun `moving to the usual time just clears the move`() {
        val alarms = listOf(alarm("a", 6, 0))
        val moved = GroupTiming.moveNext(alarms, GroupTiming(onceFrom = local(19, 6, 0), onceShiftMinutes = 30), six, sundayNight, zone)
        assertEquals(GroupTiming.MoveResult.Moved(GroupTiming.NONE), moved)
    }

    @Test
    fun `cleared drops a finished skip and a finished move, and keeps what is still ahead`() {
        val timing = GroupTiming(skipUntil = local(19, 6, 1), onceFrom = local(19, 6, 0), onceShiftMinutes = 60)
        assertEquals(timing, timing.cleared(sundayNight, zone))
        assertNull(timing.cleared(at(19, 7, 0), zone).skipUntil)
        assertTrue(timing.cleared(at(19, 12, 0), zone).onceFrom != null)
        assertEquals(GroupTiming.NONE, timing.cleared(at(19, 19, 1), zone))
    }
}
