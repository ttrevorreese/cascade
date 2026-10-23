package com.cascade.alarm.data

import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.data.db.time
import com.cascade.alarm.engine.ring.RingDefaults
import com.cascade.alarm.engine.ring.VolumeRamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

data class GroupSummary(
    val group: AlarmGroupEntity,
    val alarmCount: Int,
    val enabledCount: Int,
    /** Earliest scheduled ring, or null if the group is disarmed or nothing is scheduled. */
    val nextRing: Instant?,
    /** Times of the first and last alarm in sequence order, for the card's schedule line. */
    val firstTime: LocalTime? = null,
    val lastTime: LocalTime? = null,
    /** The first alarm's repeat days. */
    val days: Set<DayOfWeek> = emptySet(),
)

/** Pure rules behind the alarm and group CRUD screens. */
object AlarmRules {
    const val MIN_TIMEOUT_MINUTES = 1
    const val MAX_TIMEOUT_MINUTES = 60
    private const val SEQUENCE_STEP_MINUTES = 10L
    private const val MINUTES_PER_DAY = 24 * 60

    /**
     * A new alarm appended to a group. Groups are sequences, so it defaults to the next step:
     * ten minutes after the last alarm, with the same days and ring settings.
     */
    fun newAlarm(id: String, groupId: String, existing: List<AlarmEntity>): AlarmEntity {
        val last = existing.maxByOrNull { it.positionInGroup }
        val time = last?.time?.plusMinutes(SEQUENCE_STEP_MINUTES)
        return AlarmEntity(
            id = id,
            groupId = groupId,
            positionInGroup = (last?.positionInGroup ?: -1) + 1,
            hour = time?.hour ?: 7,
            minute = time?.minute ?: 0,
            daysOfWeek = last?.daysOfWeek ?: emptySet(),
            label = null,
            isEnabled = true,
            volumeRampSeconds = last?.volumeRampSeconds ?: RingDefaults.RAMP_SECONDS,
            timeoutMinutes = last?.timeoutMinutes ?: RingDefaults.TIMEOUT_MINUTES,
        )
    }

    /** Moves the item at [index] by [delta] places; out-of-range moves leave the list unchanged. */
    fun <T> move(list: List<T>, index: Int, delta: Int): List<T> {
        val target = index + delta
        if (index !in list.indices || target !in list.indices) return list
        return list.toMutableList().apply { add(target, removeAt(index)) }
    }

    /** Keeps a saved challenge within its limits; a barcode with nothing to match is no challenge. */
    fun normalizeChallenge(challenge: ChallengeConfig?): ChallengeConfig? = when (challenge) {
        null -> null
        is ChallengeConfig.Math -> challenge.copy(
            problemCount = challenge.problemCount.coerceIn(ChallengeConfig.MIN_PROBLEMS, ChallengeConfig.MAX_PROBLEMS),
        )
        is ChallengeConfig.TypePhrase -> challenge.copy(phrase = challenge.phrase.trim())
        is ChallengeConfig.BarcodeScan ->
            if (challenge.expectedPayload.isEmpty()) null else challenge.copy(label = challenge.label.trim())
        is ChallengeConfig.Steps -> challenge.copy(count = challenge.count.coerceIn(10, 200))
        is ChallengeConfig.Memory -> challenge.copy(sequenceLength = challenge.sequenceLength.coerceIn(3, 10))
        is ChallengeConfig.PhotoMatch -> challenge
    }

    /**
     * [alarm] moved by [minutes]. An alarm pushed across midnight takes its repeat days with it,
     * so "Mon 23:50" moved 20 minutes later rings at 00:10 on Tuesday, not a day early on Monday.
     */
    fun shifted(alarm: AlarmEntity, minutes: Long): AlarmEntity {
        val total = alarm.hour * 60L + alarm.minute + minutes
        val dayShift = Math.floorDiv(total, MINUTES_PER_DAY.toLong())
        val time = alarm.time.plusMinutes(minutes)
        return alarm.copy(
            hour = time.hour,
            minute = time.minute,
            daysOfWeek = alarm.daysOfWeek.mapTo(mutableSetOf()) { it.plus(dayShift) },
        )
    }

    /** Minutes from [from] to [to] the short way round the clock: 23:50 → 00:10 is +20, not −1420. */
    fun shiftMinutes(from: LocalTime, to: LocalTime): Long {
        val diff = Math.floorMod(to.toSecondOfDay() / 60 - from.toSecondOfDay() / 60, MINUTES_PER_DAY)
        return (if (diff > MINUTES_PER_DAY / 2) diff - MINUTES_PER_DAY else diff).toLong()
    }

    fun normalizeLabel(label: String?): String? = label?.trim()?.takeIf { it.isNotEmpty() }

    fun clampRamp(seconds: Int): Int = seconds.coerceIn(0, VolumeRamp.MAX_RAMP_SECONDS)

    fun clampTimeout(minutes: Int): Int = minutes.coerceIn(MIN_TIMEOUT_MINUTES, MAX_TIMEOUT_MINUTES)

    fun summarize(group: AlarmGroupEntity, alarms: List<AlarmEntity>): GroupSummary {
        val ordered = alarms.sortedBy { it.positionInGroup }
        return GroupSummary(
            group = group,
            alarmCount = alarms.size,
            enabledCount = alarms.count { it.isEnabled },
            nextRing = if (group.isArmed) alarms.mapNotNull { it.nextTriggerAt }.minOrNull() else null,
            firstTime = ordered.firstOrNull()?.time,
            lastTime = ordered.lastOrNull()?.time,
            days = ordered.firstOrNull()?.daysOfWeek.orEmpty(),
        )
    }
}

object DaysFormat {
    private val WEEKDAYS = DayOfWeek.entries.take(5).toSet()
    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    // Plain English for now; moves to string resources with the design pass.
    fun describe(days: Set<DayOfWeek>, locale: Locale): String = when (days) {
        emptySet<DayOfWeek>() -> "Once"
        DayOfWeek.entries.toSet() -> "Every day"
        WEEKDAYS -> "Weekdays"
        WEEKEND -> "Weekends"
        else -> days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
    }
}
