package com.cascade.alarm.data.reliability

import com.cascade.alarm.data.SystemTestAlarm
import com.cascade.alarm.data.db.AlarmEventEntity
import com.cascade.alarm.data.db.EventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ReliabilityRulesTest {
    private val now = Instant.parse("2026-10-22T20:00:00Z")
    private val boot = Instant.parse("2026-10-22T08:00:00Z")

    private fun event(
        id: String, type: EventType, at: Instant, scheduledFor: Instant?, reason: String? = null, alarm: String = "a",
        wakeCheck: Boolean = false, next: String? = null,
    ) =
        AlarmEventEntity(
            id = id, alarmId = alarm, type = type, timestamp = at,
            metadata = listOfNotNull(
                scheduledFor?.let { "\"scheduledFor\":\"$it\"" },
                reason?.let { "\"reason\":\"$it\"" },
                next?.let { "\"next\":\"$it\"" },
                if (type == EventType.FIRED) "\"wakeCheck\":\"$wakeCheck\"" else null,
            ).joinToString(",", "{", "}"),
        )

    @Test
    fun `an alarm that never rang while the phone was on blames Android`() {
        val missedAt = Instant.parse("2026-10-22T10:00:00Z")
        val incidents = ReliabilityRules.incidents(listOf(event("e1", EventType.MISSED, now, missedAt, "not_fired")), boot, now, emptySet())
        assertEquals(listOf(Incident.Missed("e1", "a", missedAt, IncidentCause.APP_STOPPED)), incidents)
    }

    @Test
    fun `an alarm due before the phone last started was missed because it was off`() {
        val missedAt = Instant.parse("2026-10-22T06:00:00Z")
        val incident = ReliabilityRules.incidents(listOf(event("e1", EventType.MISSED, boot, missedAt, "not_fired")), boot, now, emptySet()).single()
        assertEquals(IncidentCause.PHONE_OFF, (incident as Incident.Missed).cause)
    }

    @Test
    fun `rings that timed out or were replaced are not incidents, the alarm did ring`() {
        val events = listOf(
            event("t", EventType.MISSED, now, null, "timeout"),
            event("s", EventType.MISSED, now, null, "superseded"),
        )
        assertTrue(ReliabilityRules.incidents(events, boot, now, emptySet()).isEmpty())
    }

    @Test
    fun `rings more than two minutes late are reported, on-time ones and wake-up checks are not`() {
        val due = Instant.parse("2026-10-22T10:00:00Z")
        val events = listOf(
            event("late", EventType.FIRED, due.plus(Duration.ofMinutes(7)), due),
            event("ok", EventType.FIRED, due.plusSeconds(90), due),
            event("check", EventType.FIRED, due.plus(Duration.ofMinutes(9)), due, wakeCheck = true),
            event("rering", EventType.FIRED, due, null, "wake_check_failed"),
        )
        val late = ReliabilityRules.incidents(events, boot, now, emptySet()).single() as Incident.Late
        assertEquals("late", late.eventId)
        assertEquals(Duration.ofMinutes(7), late.lateBy)
    }

    @Test
    fun `a ring whose sound fell back is one incident showing what finally played`() {
        val due = Instant.parse("2026-10-22T10:00:00Z")
        val other = Instant.parse("2026-10-22T11:00:00Z")
        val events = listOf(
            event("f1", EventType.SOUND_FALLBACK, due, due, "could_not_play", next = "classic"),
            event("f2", EventType.SOUND_FALLBACK, due, due, "could_not_play", next = "system_sound"),
            event("g", EventType.SOUND_FALLBACK, other, other, "file_missing", next = "classic"),
        )
        assertEquals(
            listOf(
                Incident.SoundFailed("g", "a", other, "file_missing", "classic"),
                Incident.SoundFailed("f2", "a", due, "could_not_play", "system_sound"),
            ),
            ReliabilityRules.incidents(events, boot, now, emptySet()),
        )
    }

    @Test
    fun `acknowledged, old and test-alarm events are left out, newest first`() {
        val events = listOf(
            event("seen", EventType.MISSED, now, now.minusSeconds(7200), "not_fired"),
            event("old", EventType.MISSED, now.minus(Duration.ofDays(8)), now.minus(Duration.ofDays(8)), "not_fired"),
            event("test", EventType.MISSED, now, now.minusSeconds(600), "not_fired", alarm = SystemTestAlarm.ALARM_ID),
            event("a", EventType.MISSED, now, now.minusSeconds(5000), "not_fired"),
            event("b", EventType.MISSED, now, now.minusSeconds(1000), "not_fired"),
        )
        assertEquals(listOf("b", "a"), ReliabilityRules.incidents(events, boot, now, setOf("seen")).map { it.eventId })
    }

    @Test
    fun `the last test reports how late it rang, or that it never did`() {
        val due = Instant.parse("2026-10-22T19:00:00Z")
        val t = SystemTestAlarm.ALARM_ID
        assertNull(ReliabilityRules.lastTest(emptyList()))
        val rang = ReliabilityRules.lastTest(
            listOf(
                event("old", EventType.FIRED, due.minusSeconds(3600), due.minusSeconds(3610), alarm = t),
                event("new", EventType.FIRED, due.plusMillis(400), due, alarm = t),
            ),
        )
        assertEquals(TestResult.Rang(due, Duration.ofMillis(400)), rang)
        assertEquals(TestResult.Missed(due), ReliabilityRules.lastTest(listOf(event("m", EventType.MISSED, now, due, "not_fired", alarm = t))))
    }

    @Test
    fun `metadata is read from the event log's JSON`() {
        assertEquals("not_fired", ReliabilityRules.metadata("""{"scheduledFor":"x","reason":"not_fired"}""", "reason"))
        assertEquals("a \"quoted\" label", ReliabilityRules.metadata("""{"label":"a \"quoted\" label"}""", "label"))
        assertNull(ReliabilityRules.metadata(null, "reason"))
        assertNull(ReliabilityRules.metadata("{}", "reason"))
    }

    @Test
    fun `phone makers that stop apps get guidance, others do not`() {
        assertEquals("Samsung", OemGuidances.forDevice("samsung", "samsung")?.maker)
        assertEquals("Xiaomi", OemGuidances.forDevice("Xiaomi", "POCO")?.maker)
        assertEquals("Oppo, Realme and OnePlus", OemGuidances.forDevice("OnePlus", "OnePlus")?.maker)
        assertNull(OemGuidances.forDevice("Google", "google"))
        assertTrue(OemGuidances.forDevice("samsung", "samsung")!!.steps.any { "Never sleeping apps" in it })
    }
}
