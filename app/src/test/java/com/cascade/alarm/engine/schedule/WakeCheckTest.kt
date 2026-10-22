package com.cascade.alarm.engine.schedule

import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class WakeCheckTest {
    private val dismissedAt = Instant.parse("2026-10-19T10:30:00Z")
    private val group = AlarmGroupEntity("g", "Workday", "#000000", "default", true, 0, Instant.EPOCH, wakeCheckEnabled = true, wakeCheckDelayMinutes = 3)

    private fun alarm(id: String, position: Int, enabled: Boolean = true) = AlarmEntity(
        id = id, groupId = "g", positionInGroup = position, hour = 6, minute = position * 10, daysOfWeek = emptySet(),
        label = null, isEnabled = enabled, volumeRampSeconds = 30, timeoutMinutes = 15,
    )

    private val sequence = listOf(alarm("a", 0), alarm("b", 1), alarm("c", 2))

    @Test
    fun `dismissing the last alarm schedules a check after the group's delay`() {
        assertEquals(dismissedAt.plusSeconds(180), WakeCheck.checkAt(sequence[2], group, sequence, dismissedAt))
    }

    @Test
    fun `dismissing an earlier alarm schedules nothing because more are coming`() {
        assertNull(WakeCheck.checkAt(sequence[1], group, sequence, dismissedAt))
    }

    @Test
    fun `switched-off alarms after it do not count`() {
        val alarms = listOf(alarm("a", 0), alarm("b", 1), alarm("c", 2, enabled = false))
        assertEquals(dismissedAt.plusSeconds(180), WakeCheck.checkAt(alarms[1], group, alarms, dismissedAt))
    }

    @Test
    fun `a one-shot last alarm that disabled itself on ringing still gets a check`() {
        val alarms = listOf(alarm("a", 0), alarm("b", 1, enabled = false))
        assertEquals(dismissedAt.plusSeconds(180), WakeCheck.checkAt(alarms[1], group, alarms, dismissedAt))
    }

    @Test
    fun `no check when the group has it off or is disarmed`() {
        assertNull(WakeCheck.checkAt(sequence[2], group.copy(wakeCheckEnabled = false), sequence, dismissedAt))
        assertNull(WakeCheck.checkAt(sequence[2], group.copy(isArmed = false), sequence, dismissedAt))
    }

    @Test
    fun `delay is kept between one and five minutes`() {
        assertEquals(dismissedAt.plusSeconds(60), WakeCheck.checkAt(sequence[2], group.copy(wakeCheckDelayMinutes = 0), sequence, dismissedAt))
        assertEquals(dismissedAt.plusSeconds(300), WakeCheck.checkAt(sequence[2], group.copy(wakeCheckDelayMinutes = 9), sequence, dismissedAt))
    }
}
