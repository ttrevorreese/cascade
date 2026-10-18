package com.cascade.alarm.engine.schedule

import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.engine.schedule.ReconcilePlanner.Action.Cancel
import com.cascade.alarm.engine.schedule.ReconcilePlanner.Action.Schedule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class ReconcilePlannerTest {
    private val zone = ZoneId.of("America/New_York")
    private val daily = DayOfWeek.entries.toSet()

    // 2026-10-18 is a Sunday; 07:00 EDT = 11:00Z.
    private val sevenAm = Instant.parse("2026-10-18T11:00:00Z")
    private val sevenAmTomorrow = Instant.parse("2026-10-19T11:00:00Z")

    private fun alarm(
        days: Set<DayOfWeek> = daily,
        enabled: Boolean = true,
        next: Instant? = null,
        oneTime: Instant? = null,
        lastFired: Instant? = null,
    ) = AlarmEntity(
        id = "a", hour = 7, minute = 0, daysOfWeek = days, label = null, isEnabled = enabled,
        volumeRampSeconds = 30, timeoutMinutes = 15,
        nextTriggerAt = next, oneTimeTriggerAt = oneTime, lastFiredFor = lastFired,
    )

    private fun plan(alarm: AlarmEntity, now: Instant, zone: ZoneId = this.zone) =
        ReconcilePlanner.plan(alarm, now, zone)

    @Test
    fun `disabled alarm is cancelled`() {
        val p = plan(alarm(enabled = false, next = sevenAm), sevenAm.minusSeconds(3600))
        assertEquals(Cancel, p.action)
        assertNull(p.alarm.nextTriggerAt)
        assertNull(p.missed)
    }

    @Test
    fun `disabled alarm still rings its one-time trigger`() {
        // A one-shot disables itself when it rings, but its snooze (or a test ring) must still fire.
        val soon = sevenAm.minusSeconds(600)
        val p = plan(alarm(days = emptySet(), enabled = false, oneTime = soon), sevenAm.minusSeconds(3600))
        assertEquals(Schedule(soon), p.action)
        assertEquals(soon, p.alarm.nextTriggerAt)
    }

    @Test
    fun `disabled alarm with an overdue one-time trigger catches up`() {
        val test = sevenAm.minusSeconds(600)
        val p = plan(alarm(enabled = false, next = test, oneTime = test), test.plusSeconds(300))
        assertEquals(Schedule(test), p.action)
    }

    @Test
    fun `newly enabled alarm is scheduled at its next occurrence`() {
        val p = plan(alarm(), sevenAm.minusSeconds(3600))
        assertEquals(Schedule(sevenAm), p.action)
        assertEquals(sevenAm, p.alarm.nextTriggerAt)
    }

    @Test
    fun `future trigger is recomputed so zone changes take effect`() {
        val chicago = ZoneId.of("America/Chicago") // 07:00 CDT = 12:00Z
        val p = plan(alarm(next = sevenAm), sevenAm.minusSeconds(3600), chicago)
        assertEquals(Schedule(Instant.parse("2026-10-18T12:00:00Z")), p.action)
    }

    @Test
    fun `trigger missed by under fifteen minutes rings immediately`() {
        val p = plan(alarm(next = sevenAm), sevenAm.plus(Duration.ofMinutes(10)))
        assertEquals(Schedule(sevenAm), p.action) // a past instant makes AlarmManager fire now
        assertEquals(sevenAm, p.alarm.nextTriggerAt)
        assertNull(p.missed)
    }

    @Test
    fun `trigger missed by exactly fifteen minutes still rings`() {
        val p = plan(alarm(next = sevenAm), sevenAm.plus(ReconcilePlanner.CATCH_UP_WINDOW))
        assertEquals(Schedule(sevenAm), p.action)
    }

    @Test
    fun `trigger missed by more than fifteen minutes is logged and the next one scheduled`() {
        val p = plan(alarm(next = sevenAm), sevenAm.plus(Duration.ofMinutes(16)))
        assertEquals(sevenAm, p.missed)
        assertEquals(Schedule(sevenAmTomorrow), p.action)
        assertEquals(sevenAmTomorrow, p.alarm.nextTriggerAt)
    }

    @Test
    fun `missed one-shot alarm is disabled`() {
        val p = plan(alarm(days = emptySet(), next = sevenAm), sevenAm.plus(Duration.ofHours(2)))
        assertEquals(sevenAm, p.missed)
        assertEquals(Cancel, p.action)
        assertFalse(p.alarm.isEnabled)
        assertNull(p.alarm.nextTriggerAt)
    }

    @Test
    fun `past trigger that already rang is not treated as missed`() {
        val p = plan(alarm(next = sevenAm, lastFired = sevenAm), sevenAm.plus(Duration.ofHours(1)))
        assertNull(p.missed)
        assertEquals(Schedule(sevenAmTomorrow), p.action)
    }

    @Test
    fun `one-time trigger earlier than the regular schedule wins`() {
        val soon = sevenAm.minusSeconds(600)
        val p = plan(alarm(oneTime = soon), sevenAm.minusSeconds(3600))
        assertEquals(Schedule(soon), p.action)
        assertEquals(soon, p.alarm.oneTimeTriggerAt)
    }

    @Test
    fun `one-time trigger later than the regular schedule waits its turn`() {
        val later = sevenAm.plusSeconds(600)
        val p = plan(alarm(oneTime = later), sevenAm.minusSeconds(3600))
        assertEquals(Schedule(sevenAm), p.action)
        assertEquals(later, p.alarm.oneTimeTriggerAt)
    }

    @Test
    fun `stale one-time trigger that was never scheduled is dropped`() {
        val p = plan(alarm(oneTime = sevenAm.minusSeconds(7200)), sevenAm.minusSeconds(3600))
        assertNull(p.alarm.oneTimeTriggerAt)
        assertEquals(Schedule(sevenAm), p.action)
    }

    @Test
    fun `missed one-time trigger is logged without disabling a one-shot alarm`() {
        val test = sevenAm.minus(Duration.ofHours(3))
        val p = plan(alarm(days = emptySet(), next = test, oneTime = test), sevenAm.minus(Duration.ofHours(1)))
        assertEquals(test, p.missed)
        assertTrue(p.alarm.isEnabled)
        assertNull(p.alarm.oneTimeTriggerAt)
        assertEquals(Schedule(sevenAm), p.action)
    }

    @Test
    fun `firing a repeating alarm records it and schedules the next day`() {
        val fired = ReconcilePlanner.afterFiring(alarm(next = sevenAm), sevenAm)
        assertEquals(sevenAm, fired.lastFiredFor)
        assertTrue(fired.isEnabled)
        val p = plan(fired, sevenAm.plusSeconds(1))
        assertNull(p.missed)
        assertEquals(Schedule(sevenAmTomorrow), p.action)
    }

    @Test
    fun `firing a one-shot alarm disables it`() {
        val fired = ReconcilePlanner.afterFiring(alarm(days = emptySet(), next = sevenAm), sevenAm)
        assertFalse(fired.isEnabled)
        assertEquals(Cancel, plan(fired, sevenAm.plusSeconds(1)).action)
    }

    @Test
    fun `firing the one-time trigger leaves the regular schedule armed`() {
        val test = sevenAm.minusSeconds(600)
        val fired = ReconcilePlanner.afterFiring(alarm(days = emptySet(), next = test, oneTime = test), test)
        assertTrue(fired.isEnabled)
        assertNull(fired.oneTimeTriggerAt)
        assertEquals(Schedule(sevenAm), plan(fired, test.plusSeconds(1)).action)
    }

    @Test
    fun `reconciling is idempotent`() {
        val now = sevenAm.plus(Duration.ofMinutes(30))
        val first = plan(alarm(next = sevenAm), now)
        val second = plan(first.alarm, now)
        assertEquals(first.action, second.action)
        assertEquals(first.alarm, second.alarm)
        assertNull(second.missed) // the miss is reported once, not on every pass
    }
}
