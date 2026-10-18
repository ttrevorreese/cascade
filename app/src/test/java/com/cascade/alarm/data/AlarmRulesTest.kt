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
import java.time.DayOfWeek.WEDNESDAY
import java.time.DayOfWeek
import java.time.Instant
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
    fun `disarmed group has no next ring`() {
        val s = AlarmRules.summarize(group.copy(isArmed = false), listOf(alarm("a", 0, next = Instant.EPOCH)))
        assertNull(s.nextRing)
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
