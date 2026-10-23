package com.cascade.alarm.data.reliability

import com.cascade.alarm.data.SystemTestAlarm
import com.cascade.alarm.data.db.AlarmEventEntity
import com.cascade.alarm.data.db.EventType
import java.time.Duration
import java.time.Instant

/** Why an alarm did not ring on time, inferred from when the phone last started (SPEC §4.3). */
enum class IncidentCause {
    /** The alarm time was before the current boot: the phone was off or restarting. */
    PHONE_OFF,

    /** The phone was on, so Android (battery saving, a force stop) kept Cascade from ringing. */
    APP_STOPPED,
}

sealed interface Incident {
    /** The event it came from; acknowledging it hides the card for good. */
    val eventId: String
    val alarmId: String
    val scheduledFor: Instant
    val cause: IncidentCause

    data class Missed(
        override val eventId: String,
        override val alarmId: String,
        override val scheduledFor: Instant,
        override val cause: IncidentCause,
    ) : Incident

    data class Late(
        override val eventId: String,
        override val alarmId: String,
        override val scheduledFor: Instant,
        override val cause: IncidentCause,
        val lateBy: Duration,
    ) : Incident
}

sealed interface TestResult {
    val scheduledFor: Instant

    data class Rang(override val scheduledFor: Instant, val lateBy: Duration) : TestResult
    data class Missed(override val scheduledFor: Instant) : TestResult
}

/** The missed-alarm detector's decisions, separate from Android so they can be tested. */
object ReliabilityRules {
    /** A ring later than this is worth telling the user about. */
    val LATE_AFTER: Duration = Duration.ofMinutes(2)

    /** Older incidents are history, not news. */
    val WINDOW: Duration = Duration.ofDays(7)

    fun cause(scheduledFor: Instant, bootTime: Instant): IncidentCause =
        if (scheduledFor.isBefore(bootTime)) IncidentCause.PHONE_OFF else IncidentCause.APP_STOPPED

    /**
     * Alarms that never rang ([EventType.MISSED] with reason `not_fired`) and rings more than
     * [LATE_AFTER] late, newest first, minus the [acknowledged] ones and the hidden test alarm
     * (its result is shown separately). Timeouts and superseded rings are not incidents: the
     * alarm did ring.
     */
    fun incidents(events: List<AlarmEventEntity>, bootTime: Instant, now: Instant, acknowledged: Set<String>): List<Incident> =
        events.asSequence()
            .filter { it.id !in acknowledged && it.alarmId != SystemTestAlarm.ALARM_ID }
            .filter { Duration.between(it.timestamp, now) <= WINDOW }
            .mapNotNull { event ->
                val scheduledFor = metadata(event.metadata, "scheduledFor")?.let(::parseInstant) ?: return@mapNotNull null
                when (event.type) {
                    EventType.MISSED ->
                        if (metadata(event.metadata, "reason") == "not_fired") {
                            Incident.Missed(event.id, event.alarmId, scheduledFor, cause(scheduledFor, bootTime))
                        } else null
                    EventType.FIRED -> {
                        val lateBy = Duration.between(scheduledFor, event.timestamp)
                        // A wake-up check is a prompt, not an alarm; its timing is not news.
                        if (lateBy > LATE_AFTER && metadata(event.metadata, "wakeCheck") != "true") {
                            Incident.Late(event.id, event.alarmId, scheduledFor, cause(scheduledFor, bootTime), lateBy)
                        } else null
                    }
                    else -> null
                }
            }
            .sortedByDescending { it.scheduledFor }
            .toList()

    /** How the most recent test alarm went, or null if none has run (events of the test alarm, any order). */
    fun lastTest(testEvents: List<AlarmEventEntity>): TestResult? =
        testEvents.filter { it.alarmId == SystemTestAlarm.ALARM_ID }
            .sortedByDescending { it.timestamp }
            .firstNotNullOfOrNull { event ->
                val scheduledFor = metadata(event.metadata, "scheduledFor")?.let(::parseInstant) ?: return@firstNotNullOfOrNull null
                when (event.type) {
                    EventType.FIRED -> TestResult.Rang(scheduledFor, Duration.between(scheduledFor, event.timestamp).coerceAtLeast(Duration.ZERO))
                    EventType.MISSED -> if (metadata(event.metadata, "reason") == "not_fired") TestResult.Missed(scheduledFor) else null
                    else -> null
                }
            }

    /**
     * Reads one string value from the flat JSON the event log writes (`{"key":"value",...}`).
     * Deliberately not org.json, which is Android-only and absent from JVM tests.
     */
    fun metadata(json: String?, key: String): String? {
        json ?: return null
        val match = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return null
        return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
}
