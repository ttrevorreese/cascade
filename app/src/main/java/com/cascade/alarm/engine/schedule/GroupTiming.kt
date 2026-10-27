package com.cascade.alarm.engine.schedule

import com.cascade.alarm.data.AlarmRules
import com.cascade.alarm.data.challenge.Escalation
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.data.db.time
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A group's changes to its regular schedule (owner request, 2026-10-26):
 * - [skipUntil]: regular rings before it do not happen ("Skip next", "Pause until a date").
 * - [onceFrom] and [onceShiftMinutes]: the regular rings of one morning, the [Escalation.CASCADE_WINDOW]
 *   from [onceFrom], ring that many minutes later or earlier ("Next time only"). Then back to normal.
 *
 * Both are wall-clock times, like the alarms: after a flight to another time zone the skipped
 * morning is still the one skipped, not whatever falls before the same instant.
 *
 * Snoozes, test rings and wake-up checks are not regular rings and are never moved or skipped.
 * The plain timing gives exactly [NextOccurrence.after].
 */
data class GroupTiming(
    val skipUntil: LocalDateTime? = null,
    val onceFrom: LocalDateTime? = null,
    val onceShiftMinutes: Int = 0,
) {
    private val hasOnce get() = onceFrom != null && onceShiftMinutes != 0
    val isPlain get() = skipUntil == null && !hasOnce

    /** Whether the regular ring at [occurrence] is moved by [onceShiftMinutes]. */
    fun isMoved(occurrence: Instant, zone: ZoneId): Boolean {
        val from = onceFrom ?: return false
        val local = occurrence.atZone(zone).toLocalDateTime()
        return hasOnce && !local.isBefore(from) && !local.isAfter(from.plus(Escalation.CASCADE_WINDOW))
    }

    fun skips(occurrence: Instant, zone: ZoneId): Boolean =
        skipUntil?.let { occurrence.atZone(zone).toLocalDateTime().isBefore(it) } ?: false

    /** When the regular ring at [occurrence] actually rings. */
    fun effective(occurrence: Instant, zone: ZoneId): Instant =
        if (isMoved(occurrence, zone)) occurrence.plus(Duration.ofMinutes(onceShiftMinutes.toLong())) else occurrence

    /** The first regular ring strictly after [after], skips and the one-off move applied. */
    fun next(time: LocalTime, days: Set<DayOfWeek>, after: Instant, zone: ZoneId): Instant =
        nextWithOccurrence(time, days, after, zone).second

    /** [next], with the regular occurrence it comes from (they differ for a moved ring). */
    fun nextWithOccurrence(time: LocalTime, days: Set<DayOfWeek>, after: Instant, zone: ZoneId): Pair<Instant, Instant> {
        if (isPlain) return NextOccurrence.after(time, days, after, zone).let { it to it }
        // A ring moved later can come from an occurrence before [after]; skipped ones never ring.
        var from = after.minus(LOOK_BACK)
        val skipEnd = skipUntil?.atZone(zone)?.toInstant()
        skipEnd?.minusNanos(1)?.let { if (it.isAfter(from)) from = it }
        repeat(MAX_STEPS) {
            val occurrence = NextOccurrence.after(time, days, from, zone)
            val at = effective(occurrence, zone)
            if (!skips(occurrence, zone) && at.isAfter(after)) return occurrence to at
            from = occurrence
        }
        return NextOccurrence.after(time, days, maxOf(after, skipEnd ?: after), zone).let { it to it }
    }

    /** Drops what is over by [now], so a finished skip or move cannot catch a later morning. */
    fun cleared(now: Instant, zone: ZoneId): GroupTiming {
        val local = now.atZone(zone).toLocalDateTime()
        val skip = skipUntil?.takeIf { it.isAfter(local) }
        val onceOver = onceFrom?.plus(Escalation.CASCADE_WINDOW)?.plusMinutes(Math.abs(onceShiftMinutes).toLong())
        val keepOnce = hasOnce && onceOver != null && onceOver.isAfter(local)
        return GroupTiming(skip, if (keepOnce) onceFrom else null, if (keepOnce) onceShiftMinutes else 0)
    }

    /** One regular ring of the group's next morning. */
    data class Ring(val alarm: AlarmEntity, val occurrence: Instant, val at: Instant)

    sealed interface MoveResult {
        data class Moved(val timing: GroupTiming) : MoveResult
        /** The new first time would already be past. */
        data object InThePast : MoveResult
        data object NothingDue : MoveResult
    }

    companion object {
        val NONE = GroupTiming()
        private val LOOK_BACK: Duration = Duration.ofHours(13)
        private const val MAX_STEPS = 24

        fun of(group: AlarmGroupEntity) = GroupTiming(group.skipUntil, group.onceFrom, group.onceShiftMinutes)

        /**
         * The group's next morning: each alarm that is on at its next regular ring, keeping those
         * within the cascade window of the first, in ring order.
         */
        fun nextCascade(alarms: List<AlarmEntity>, timing: GroupTiming, now: Instant, zone: ZoneId): List<Ring> {
            val rings = alarms.filter { it.isEnabled }.map { alarm ->
                val (occurrence, at) = timing.nextWithOccurrence(alarm.time, alarm.daysOfWeek, now, zone)
                Ring(alarm, occurrence, at)
            }
            val first = rings.minOfOrNull { it.at } ?: return emptyList()
            return rings.filter { !it.at.isAfter(first.plus(Escalation.CASCADE_WINDOW)) }.sortedBy { it.at }
        }

        /** Skips the next morning (on top of any pause); null when nothing is due. Drops a one-off move. */
        fun skipNext(alarms: List<AlarmEntity>, timing: GroupTiming, now: Instant, zone: ZoneId): GroupTiming? {
            val plain = timing.copy(onceFrom = null, onceShiftMinutes = 0)
            val last = nextCascade(alarms, plain, now, zone).lastOrNull() ?: return null
            return plain.copy(skipUntil = last.occurrence.atZone(zone).toLocalDateTime().plusSeconds(1))
        }

        /** Nothing regular rings before [date] starts; null unless [date] is after today. */
        fun pauseUntil(date: LocalDate, now: Instant, zone: ZoneId): GroupTiming? {
            if (!date.isAfter(now.atZone(zone).toLocalDate())) return null
            return GroupTiming(skipUntil = date.atStartOfDay())
        }

        /**
         * The next morning starts at [firstTime] instead, the rest moving with it; the morning after
         * is back to normal. The move is the short way round the clock (at most 12 hours).
         */
        fun moveNext(alarms: List<AlarmEntity>, timing: GroupTiming, firstTime: LocalTime, now: Instant, zone: ZoneId): MoveResult {
            val plain = timing.copy(onceFrom = null, onceShiftMinutes = 0)
            val first = nextCascade(alarms, plain, now, zone).firstOrNull() ?: return MoveResult.NothingDue
            val local = first.occurrence.atZone(zone).toLocalDateTime()
            val minutes = AlarmRules.shiftMinutes(LocalTime.of(local.hour, local.minute), firstTime)
            if (minutes == 0L) return MoveResult.Moved(plain)
            if (!first.occurrence.plus(Duration.ofMinutes(minutes)).isAfter(now)) return MoveResult.InThePast
            return MoveResult.Moved(plain.copy(onceFrom = local, onceShiftMinutes = minutes.toInt()))
        }
    }
}
