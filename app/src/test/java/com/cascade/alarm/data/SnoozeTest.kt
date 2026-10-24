package com.cascade.alarm.data

import com.cascade.alarm.data.db.AlarmEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SnoozeTest {
    private fun alarm(enabled: Boolean = true, used: Int = 0) = AlarmEntity(
        id = "a", groupId = "g", positionInGroup = 0, hour = 6, minute = 0, daysOfWeek = emptySet(), label = null,
        isEnabled = true, timeoutMinutes = 15, snoozeEnabled = enabled, snoozeMinutes = 9, snoozeLimit = 3, snoozesUsed = used,
    )

    @Test
    fun `offers its minutes and what is left`() =
        assertEquals(SnoozeState.Offer(9, 2), Snooze.state(alarm(used = 1), isLast = false, isWakeCheck = false))

    @Test
    fun `the last alarm never snoozes`() =
        assertEquals(SnoozeState.NotOnLast, Snooze.state(alarm(), isLast = true, isWakeCheck = false))

    @Test
    fun `used up after the limit`() =
        assertEquals(SnoozeState.UsedUp, Snooze.state(alarm(used = 3), isLast = false, isWakeCheck = false))

    @Test
    fun `off when switched off, and never on a wake-up check`() {
        assertEquals(SnoozeState.Off, Snooze.state(alarm(enabled = false), isLast = true, isWakeCheck = false))
        assertEquals(SnoozeState.Off, Snooze.state(alarm(), isLast = false, isWakeCheck = true))
    }

    @Test
    fun `minutes and limit are kept to their ranges`() {
        assertEquals(1, Snooze.clampMinutes(0))
        assertEquals(30, Snooze.clampMinutes(90))
        assertEquals(5, Snooze.clampLimit(50))
        assertEquals(1, Snooze.clampLimit(0))
    }
}
