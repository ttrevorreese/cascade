package com.cascade.alarm.data.stats

import com.cascade.alarm.data.SystemTestAlarm
import com.cascade.alarm.data.db.AlarmEventEntity
import com.cascade.alarm.data.db.EventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class StatsRulesTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-24T12:00:00Z")
    private val morning = Instant.parse("2026-10-24T06:00:00Z")

    private fun event(type: EventType, at: Instant, alarm: String = "a", meta: String? = null) =
        AlarmEventEntity(alarmId = alarm, type = type, timestamp = at, metadata = meta)

    private fun stats(vararg events: AlarmEventEntity, days: Int = 7) = StatsRules.compute(events.toList(), now, days, zone)

    @Test
    fun `nothing logged is an empty week with every day listed`() {
        val s = stats()
        assertTrue(s.isEmpty)
        assertEquals(7, s.byDay.size)
        assertEquals(LocalDate.of(2026, 10, 18), s.byDay.first().date)
        assertEquals(LocalDate.of(2026, 10, 24), s.byDay.last().date)
        assertNull(s.medianToDismiss)
    }

    @Test
    fun `time to dismiss runs from the latest ring of that alarm, so a snooze restarts it`() {
        val s = stats(
            event(EventType.FIRED, morning),
            event(EventType.SNOOZED, morning.plusSeconds(20)),
            event(EventType.FIRED, morning.plusSeconds(320)),
            event(EventType.DISMISSED, morning.plusSeconds(380)),
            event(EventType.FIRED, morning.plusSeconds(600), alarm = "b"),
            event(EventType.DISMISSED, morning.plusSeconds(700), alarm = "b"),
        )
        assertEquals(3, s.rang)
        assertEquals(2, s.dismissed)
        assertEquals(1, s.snoozed)
        // 60 s and 100 s: the middle of two is their average.
        assertEquals(Duration.ofSeconds(80), s.medianToDismiss)
        assertEquals(3, s.byDay.last().rang)
    }

    @Test
    fun `challenges count solves, give-ups and wrong answers by kind`() {
        val s = stats(
            event(EventType.FIRED, morning),
            event(EventType.DISMISSED, morning.plusSeconds(30), meta = """{"challenge":"Math","misses":"2"}"""),
            event(EventType.FIRED, morning.plusSeconds(600), alarm = "b"),
            event(EventType.GAVE_UP, morning.plusSeconds(800), alarm = "b", meta = """{"challenge":"Math","misses":"3"}"""),
            event(EventType.FIRED, morning.plusSeconds(900), alarm = "c"),
            event(EventType.DISMISSED, morning.plusSeconds(960), alarm = "c", meta = """{"challenge":"PhotoMatch","misses":"0"}"""),
        )
        val math = s.challenges.first()
        assertEquals(ChallengeStats("Math", solved = 1, gaveUp = 1, misses = 5), math)
        assertEquals(2.5, math.missesPerTry)
        assertEquals(1, s.gaveUp)
        assertEquals(listOf("Math", "PhotoMatch"), s.challenges.map { it.kind })
    }

    @Test
    fun `wake-up checks, leaving the screen and powering off are counted, the test alarm is not`() {
        val s = stats(
            event(EventType.FIRED, morning, meta = """{"wakeCheck":"true"}"""),
            event(EventType.WAKE_CHECK_PASSED, morning.plusSeconds(10)),
            event(EventType.WAKE_CHECK_FAILED, morning.plusSeconds(100)),
            event(EventType.CHEAT_DETECTED, morning, meta = """{"reason":"left_screen"}"""),
            event(EventType.CHEAT_DETECTED, morning, meta = """{"reason":"power_off"}"""),
            event(EventType.FIRED, morning, alarm = SystemTestAlarm.ALARM_ID),
            event(EventType.MISSED, morning.plusSeconds(1), meta = "not json"),
        )
        assertEquals(0, s.rang, "a wake-up check's prompt is not a ring")
        assertEquals(1, s.wakeChecksPassed)
        assertEquals(1, s.wakeChecksFailed)
        assertEquals(1, s.leftScreen)
        assertEquals(1, s.poweredOff)
        assertEquals(1, s.missed)
    }

    @Test
    fun `events before the period, and rings left for over an hour, are left out of the median`() {
        val s = stats(
            event(EventType.FIRED, morning.minus(Duration.ofDays(10))),
            event(EventType.DISMISSED, morning.minus(Duration.ofDays(10)).plusSeconds(5)),
            event(EventType.FIRED, morning),
            event(EventType.DISMISSED, morning.plus(Duration.ofHours(2))),
        )
        assertEquals(1, s.rang)
        assertNull(s.medianToDismiss)
    }
}
