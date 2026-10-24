package com.cascade.alarm.data.stats

import com.cascade.alarm.data.SystemTestAlarm
import com.cascade.alarm.data.db.AlarmEventEntity
import com.cascade.alarm.data.db.EventType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the event log says about the last [days] days (SPEC §7.8). Everything is counted on the phone. */
data class Stats(
    val days: Int,
    val rang: Int,
    val dismissed: Int,
    val snoozed: Int,
    val gaveUp: Int,
    val missed: Int,
    /** Middle value of how long rings lasted before they were dismissed; null with none. */
    val medianToDismiss: Duration?,
    /** One entry per day, oldest first, including days with nothing. */
    val byDay: List<DayStats>,
    val challenges: List<ChallengeStats>,
    val wakeChecksPassed: Int,
    val wakeChecksFailed: Int,
    /** Times the alarm screen was left while ringing, or the phone powered off mid-ring. */
    val leftScreen: Int,
    val poweredOff: Int,
) {
    val isEmpty: Boolean get() = rang == 0 && wakeChecksPassed == 0 && wakeChecksFailed == 0
}

data class DayStats(val date: LocalDate, val rang: Int, val medianToDismiss: Duration?)

/** [kind] is the challenge's type name as logged ("Math", "PhotoMatch", …). */
data class ChallengeStats(val kind: String, val solved: Int, val gaveUp: Int, val misses: Int) {
    /** Wrong answers per finished challenge, solved or given up. */
    val missesPerTry: Double get() = if (solved + gaveUp == 0) 0.0 else misses.toDouble() / (solved + gaveUp)
}

object StatsRules {
    val PERIODS = listOf(7, 30)

    /** Rings longer than this were left alone, not slept through to a dismissal; they skew the median. */
    private val LONGEST_RING: Duration = Duration.ofHours(1)

    fun compute(events: List<AlarmEventEntity>, now: Instant, days: Int, zone: ZoneId = ZoneId.systemDefault()): Stats {
        val today = now.atZone(zone).toLocalDate()
        val firstDay = today.minusDays(days - 1L)
        val from = firstDay.atStartOfDay(zone).toInstant()
        // The Reliability Check's test alarm is not one of the user's mornings.
        val inWindow = events
            .filter { it.alarmId != SystemTestAlarm.ALARM_ID && !it.timestamp.isBefore(from) && !it.timestamp.isAfter(now) }
            .sortedBy { it.timestamp }

        val lastFired = mutableMapOf<String, Instant>()
        val toDismiss = mutableListOf<Pair<LocalDate, Duration>>()
        val challenges = mutableMapOf<String, ChallengeStats>()
        val ringDates = mutableListOf<LocalDate>()
        for (event in inWindow) {
            val meta = metadata(event)
            when (event.type) {
                EventType.FIRED -> if (meta["wakeCheck"] != "true") {
                    ringDates += event.timestamp.atZone(zone).toLocalDate()
                    lastFired[event.alarmId] = event.timestamp
                }
                EventType.DISMISSED, EventType.GAVE_UP -> {
                    lastFired.remove(event.alarmId)?.let { fired ->
                        val took = Duration.between(fired, event.timestamp)
                        if (!took.isNegative && took <= LONGEST_RING) toDismiss += event.timestamp.atZone(zone).toLocalDate() to took
                    }
                    meta["challenge"]?.let { kind ->
                        val misses = meta["misses"]?.toIntOrNull() ?: 0
                        val before = challenges[kind] ?: ChallengeStats(kind, 0, 0, 0)
                        challenges[kind] = if (event.type == EventType.GAVE_UP) {
                            before.copy(gaveUp = before.gaveUp + 1, misses = before.misses + misses)
                        } else {
                            before.copy(solved = before.solved + 1, misses = before.misses + misses)
                        }
                    }
                }
                EventType.SNOOZED, EventType.MISSED -> lastFired.remove(event.alarmId)
                else -> Unit
            }
        }

        val count = { type: EventType -> inWindow.count { it.type == type } }
        val cheats = inWindow.filter { it.type == EventType.CHEAT_DETECTED }.map { metadata(it)["reason"] }
        return Stats(
            days = days,
            rang = ringDates.size,
            dismissed = count(EventType.DISMISSED),
            snoozed = count(EventType.SNOOZED),
            gaveUp = count(EventType.GAVE_UP),
            missed = count(EventType.MISSED),
            medianToDismiss = median(toDismiss.map { it.second }),
            byDay = (0 until days).map { offset ->
                val date = firstDay.plusDays(offset.toLong())
                DayStats(date, ringDates.count { it == date }, median(toDismiss.filter { it.first == date }.map { it.second }))
            },
            challenges = challenges.values.sortedByDescending { it.solved + it.gaveUp },
            wakeChecksPassed = count(EventType.WAKE_CHECK_PASSED),
            wakeChecksFailed = count(EventType.WAKE_CHECK_FAILED),
            leftScreen = cheats.count { it == "left_screen" },
            poweredOff = cheats.count { it == "power_off" },
        )
    }

    fun median(values: List<Duration>): Duration? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else sorted[mid - 1].plus(sorted[mid]).dividedBy(2)
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** The event's metadata as strings; an empty map if it has none or cannot be read. */
    private fun metadata(event: AlarmEventEntity): Map<String, String> {
        val raw = event.metadata ?: return emptyMap()
        return try {
            (json.parseToJsonElement(raw) as? JsonObject)?.jsonObject
                ?.mapNotNull { (k, v) -> runCatching { v.jsonPrimitive.contentOrNull }.getOrNull()?.let { k to it } }
                ?.toMap()
                .orEmpty()
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
