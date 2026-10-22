package com.cascade.alarm.data

import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.Locale

class AlarmRulesTest {
    private val weekdays = DayOfWeek.entries.take(5).toSet()

    private fun alarm(id: String, position: Int, hour: Int = 6, minute: Int = 0, enabled: Boolean = true, next: Instant? = null) =
        AlarmEntity(
            id = id, groupId = "g", positionInGroup = position, hour = hour, minute = minute,
            daysOfWeek = weekdays, label = null, isEnabled = enabled, volumeRampSeconds = 45, timeoutMinutes = 10,
            nextTriggerAt = next,
        )

    private val group = AlarmGroupEntity("g", "Workday", "#000000", "default", true, 0, Instant.EPOCH)

    @Test
    fun `first alarm in a group defaults to 07 00 once`() {
        val a = AlarmRules.newAlarm("new", "g", emptyList())
        assertEquals(7 to 0, a.hour to a.minute)
        assertEquals(emptySet<DayOfWeek>(), a.daysOfWeek)
        assertEquals(0, a.positionInGroup)
        assertEquals(true, a.isEnabled)
    }

    @Test
    fun `next alarm follows the last one in the sequence by ten minutes`() {
        val existing = listOf(alarm("a", 0, 6, 0), alarm("b", 1, 6, 20))
        val a = AlarmRules.newAlarm("new", "g", existing)
        assertEquals(6 to 30, a.hour to a.minute)
        assertEquals(weekdays, a.daysOfWeek)
        assertEquals(2, a.positionInGroup)
        assertEquals(45, a.volumeRampSeconds)
        assertEquals(10, a.timeoutMinutes)
    }

    @Test
    fun `next alarm wraps past midnight`() {
        val a = AlarmRules.newAlarm("new", "g", listOf(alarm("a", 0, 23, 55)))
        assertEquals(0 to 5, a.hour to a.minute)
    }

    @Test
    fun `moving renumbers positions contiguously`() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "c", "b", "d"), AlarmRules.move(list, 2, -1))
        assertEquals(listOf("b", "a", "c", "d"), AlarmRules.move(list, 0, 1))
    }

    @Test
    fun `moving past either end is a no-op`() {
        val list = listOf("a", "b")
        assertEquals(list, AlarmRules.move(list, 0, -1))
        assertEquals(list, AlarmRules.move(list, 1, 1))
    }

    @Test
    fun `labels are trimmed and blank means none`() {
        assertEquals("Get up", AlarmRules.normalizeLabel("  Get up "))
        assertNull(AlarmRules.normalizeLabel("   "))
        assertNull(AlarmRules.normalizeLabel(null))
    }

    @Test
    fun `ramp and timeout are clamped to the spec ranges`() {
        assertEquals(120, AlarmRules.clampRamp(500))
        assertEquals(0, AlarmRules.clampRamp(-3))
        assertEquals(1, AlarmRules.clampTimeout(0))
        assertEquals(60, AlarmRules.clampTimeout(999))
    }

    @Test
    fun `summary reports the earliest next ring of an armed group`() {
        val early = Instant.parse("2026-10-19T10:00:00Z")
        val alarms = listOf(
            alarm("a", 0, next = early.plusSeconds(600)),
            alarm("b", 1, next = early),
            alarm("c", 2, enabled = false),
        )
        val s = AlarmRules.summarize(group, alarms)
        assertEquals(early, s.nextRing)
        assertEquals(3, s.alarmCount)
        assertEquals(2, s.enabledCount)
    }

    @Test
    fun `summary spans the sequence from first to last alarm`() {
        val s = AlarmRules.summarize(group, listOf(alarm("b", 1, 6, 35), alarm("a", 0, 6, 0)))
        assertEquals(LocalTime.of(6, 0), s.firstTime)
        assertEquals(LocalTime.of(6, 35), s.lastTime)
        assertEquals(weekdays, s.days)
    }

    @Test
    fun `disarmed group has no next ring`() {
        val s = AlarmRules.summarize(group.copy(isArmed = false), listOf(alarm("a", 0, next = Instant.EPOCH)))
        assertNull(s.nextRing)
    }

    @Test
    fun `shifting moves the time and keeps the days`() {
        val a = AlarmRules.shifted(alarm("a", 0, 6, 50), 15)
        assertEquals(7 to 5, a.hour to a.minute)
        assertEquals(weekdays, a.daysOfWeek)
        val b = AlarmRules.shifted(alarm("b", 0, 6, 5), -10)
        assertEquals(5 to 55, b.hour to b.minute)
    }

    @Test
    fun `shifting past midnight moves the days with it`() {
        val late = alarm("a", 0, 23, 50).copy(daysOfWeek = setOf(MONDAY, FRIDAY, SUNDAY))
        val later = AlarmRules.shifted(late, 20)
        assertEquals(0 to 10, later.hour to later.minute)
        assertEquals(setOf(TUESDAY, SATURDAY, MONDAY), later.daysOfWeek)

        val early = alarm("b", 0, 0, 5).copy(daysOfWeek = setOf(TUESDAY, THURSDAY))
        val earlier = AlarmRules.shifted(early, -10)
        assertEquals(23 to 55, earlier.hour to earlier.minute)
        assertEquals(setOf(MONDAY, WEDNESDAY), earlier.daysOfWeek)
    }

    @Test
    fun `one-shot alarms stay one-shot when shifted past midnight`() {
        val once = alarm("a", 0, 23, 55).copy(daysOfWeek = emptySet())
        assertEquals(emptySet<DayOfWeek>(), AlarmRules.shifted(once, 10).daysOfWeek)
    }

    @Test
    fun `shift between two times takes the short way round midnight`() {
        assertEquals(15L, AlarmRules.shiftMinutes(LocalTime.of(6, 0), LocalTime.of(6, 15)))
        assertEquals(-30L, AlarmRules.shiftMinutes(LocalTime.of(6, 0), LocalTime.of(5, 30)))
        assertEquals(20L, AlarmRules.shiftMinutes(LocalTime.of(23, 50), LocalTime.of(0, 10)))
        assertEquals(-20L, AlarmRules.shiftMinutes(LocalTime.of(0, 10), LocalTime.of(23, 50)))
        assertEquals(0L, AlarmRules.shiftMinutes(LocalTime.of(6, 0), LocalTime.of(6, 0)))
    }

    @Test
    fun `days are described the way people say them`() {
        val us = Locale.US
        assertEquals("Once", DaysFormat.describe(emptySet(), us))
        assertEquals("Every day", DaysFormat.describe(DayOfWeek.entries.toSet(), us))
        assertEquals("Weekdays", DaysFormat.describe(weekdays, us))
        assertEquals("Weekends", DaysFormat.describe(setOf(SATURDAY, SUNDAY), us))
        assertEquals("Mon, Wed, Fri", DaysFormat.describe(setOf(FRIDAY, MONDAY, WEDNESDAY), us))
    }
}
