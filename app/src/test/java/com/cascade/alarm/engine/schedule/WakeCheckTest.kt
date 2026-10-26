package com.cascade.alarm.engine.schedule

import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant

class WakeCheckTest {
    private val six = Instant.parse("2026-10-19T10:00:00Z") // Monday 06:00 EDT
    private val group = AlarmGroupEntity("g", "Workday", "#000000", "default", true, 0, Instant.EPOCH, wakeCheckEnabled = true, wakeCheckDelayMinutes = 3)

    private fun at(minutes: Int) = six.plusSeconds(minutes * 60L)

    /** An alarm at 06:[position*10] that is still to ring this morning. */
    private fun due(id: String, position: Int) = AlarmEntity(
        id = id, groupId = "g", positionInGroup = position, hour = 6, minute = position * 10, daysOfWeek = emptySet(),
        label = null, isEnabled = true, volumeRampSeconds = 30, timeoutMinutes = 15, nextTriggerAt = at(position * 10),
    )

    /** The same alarm after it rang as a one-shot: off, nothing scheduled. */
    private fun rang(alarm: AlarmEntity) = alarm.copy(isEnabled = false, nextTriggerAt = null, lastFiredFor = at(alarm.positionInGroup * 10))

    private val dismissedAt = at(25)

    @Test
    fun `dismissing the last alarm schedules a check after the group's delay`() {
        val alarms = listOf(rang(due("a", 0)), rang(due("b", 1)), rang(due("c", 2)))
        assertEquals(dismissedAt.plusSeconds(180), WakeCheck.checkAt(alarms[2], group, alarms, at(20), dismissedAt))
    }

    @Test
    fun `dismissing an earlier alarm schedules nothing because more are coming`() {
        val alarms = listOf(rang(due("a", 0)), rang(due("b", 1)), due("c", 2))
        assertNull(WakeCheck.checkAt(alarms[1], group, alarms, at(10), at(12)))
    }

    @Test
    fun `switched-off alarms after it do not count`() {
        val alarms = listOf(rang(due("a", 0)), rang(due("b", 1)), due("c", 2).copy(isEnabled = false, nextTriggerAt = null))
        assertEquals(at(15).plusSeconds(180), WakeCheck.checkAt(alarms[1], group, alarms, at(10), at(15)))
    }

    @Test
    fun `a later alarm that repeats on other days is not due this morning and does not count`() {
        val weekendOnly = due("c", 2).copy(daysOfWeek = setOf(DayOfWeek.SATURDAY), nextTriggerAt = Instant.parse("2026-10-24T10:20:00Z"))
        val alarms = listOf(rang(due("a", 0)), rang(due("b", 1)), weekendOnly)
        assertEquals(at(15).plusSeconds(180), WakeCheck.checkAt(alarms[1], group, alarms, at(10), at(15)))
    }

    @Test
    fun `a one-shot last alarm that disabled itself on ringing still gets a check`() {
        val alarms = listOf(rang(due("a", 0)), rang(due("b", 1)))
        assertEquals(at(15).plusSeconds(180), WakeCheck.checkAt(alarms[1], group, alarms, at(10), at(15)))
    }

    @Test
    fun `no check when the group has it off or is disarmed`() {
        val alarms = listOf(rang(due("a", 0)), rang(due("b", 1)), rang(due("c", 2)))
        assertNull(WakeCheck.checkAt(alarms[2], group.copy(wakeCheckEnabled = false), alarms, at(20), dismissedAt))
        assertNull(WakeCheck.checkAt(alarms[2], group.copy(isArmed = false), alarms, at(20), dismissedAt))
    }

    @Test
    fun `delay is kept between one and five minutes`() {
        val alarms = listOf(rang(due("a", 0)), rang(due("b", 1)), rang(due("c", 2)))
        assertEquals(dismissedAt.plusSeconds(60), WakeCheck.checkAt(alarms[2], group.copy(wakeCheckDelayMinutes = 0), alarms, at(20), dismissedAt))
        assertEquals(dismissedAt.plusSeconds(300), WakeCheck.checkAt(alarms[2], group.copy(wakeCheckDelayMinutes = 9), alarms, at(20), dismissedAt))
    }
}
