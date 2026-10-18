package com.cascade.alarm.data

import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.data.db.time
import com.cascade.alarm.engine.ring.RingDefaults
import com.cascade.alarm.engine.ring.VolumeRamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale

data class GroupSummary(
    val group: AlarmGroupEntity,
    val alarmCount: Int,
    val enabledCount: Int,
    /** Earliest scheduled ring, or null if the group is disarmed or nothing is scheduled. */
    val nextRing: Instant?,
)

/** Pure rules behind the alarm and group CRUD screens. */
object AlarmRules {
    const val MIN_TIMEOUT_MINUTES = 1
    const val MAX_TIMEOUT_MINUTES = 60
    private const val SEQUENCE_STEP_MINUTES = 10L

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

    fun normalizeLabel(label: String?): String? = label?.trim()?.takeIf { it.isNotEmpty() }

    fun clampRamp(seconds: Int): Int = seconds.coerceIn(0, VolumeRamp.MAX_RAMP_SECONDS)

    fun clampTimeout(minutes: Int): Int = minutes.coerceIn(MIN_TIMEOUT_MINUTES, MAX_TIMEOUT_MINUTES)

    fun summarize(group: AlarmGroupEntity, alarms: List<AlarmEntity>): GroupSummary = GroupSummary(
        group = group,
        alarmCount = alarms.size,
        enabledCount = alarms.count { it.isEnabled },
        nextRing = if (group.isArmed) alarms.mapNotNull { it.nextTriggerAt }.minOrNull() else null,
    )
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
