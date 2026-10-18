package com.cascade.alarm.engine.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Computes when an alarm should next ring. Alarms are wall-clock times in the device's *current*
 * zone (like the stock Clock app), so the result must be recomputed whenever the zone changes.
 *
 * DST policy:
 * - A time inside a spring-forward gap rings at the instant the gap ends (02:30 → 03:00).
 * - A time inside a fall-back overlap rings once, at its first (earlier) occurrence.
 * - If a gap swallows the rest of the calendar day (e.g. Samoa skipping 2011-12-30), that day is
 *   skipped rather than ringing at midnight of the following day.
 */
object NextOccurrence {

    /**
     * @param days the weekdays the alarm repeats on; empty means one-shot (next matching time).
     * @return the first instant strictly after [after] at which the alarm should ring.
     */
    fun after(time: LocalTime, days: Set<DayOfWeek>, after: Instant, zone: ZoneId): Instant {
        val startDate = after.atZone(zone).toLocalDate()
        // Today plus the following 7 days covers every weekday at least once after "today".
        // One extra day absorbs a calendar day removed by a zone change.
        for (offset in 0L..8L) {
            val date = startDate.plusDays(offset)
            if (days.isNotEmpty() && date.dayOfWeek !in days) continue
            val candidate = resolve(date, time, zone) ?: continue
            if (candidate.isAfter(after)) return candidate
        }
        error("No occurrence of $time on $days within 9 days after $after in $zone")
    }

    /** Maps a local date/time to an instant under the DST policy above; null if the day is skipped. */
    private fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId): Instant? {
        val local = LocalDateTime.of(date, time)
        val rules = zone.rules
        val offsets = rules.getValidOffsets(local)
        return when (offsets.size) {
            // Normal time, or an overlap: the first offset in the list is the earlier instant.
            1, 2 -> local.toInstant(offsets[0])
            else -> {
                val gap = rules.getTransition(local)
                if (gap.dateTimeAfter.toLocalDate() == date) gap.instant else null
            }
        }
    }
}
