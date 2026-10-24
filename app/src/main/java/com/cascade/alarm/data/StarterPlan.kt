package com.cascade.alarm.data

import com.cascade.alarm.data.db.AlarmEntity
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * The first group, as onboarding sets it up: [count] alarms [stepMinutes] apart from [first]
 * (M7, 2026-10-24). Everything else comes from [NewAlarmDefaults], like any new alarm.
 */
data class StarterPlan(
    val name: String = "Weekday mornings",
    val first: LocalTime = LocalTime.of(6, 30),
    val count: Int = 3,
    val stepMinutes: Int = 10,
    val days: Set<DayOfWeek> = WEEKDAYS,
) {
    val times: List<LocalTime> get() = (0 until count).map { first.plusMinutes(it.toLong() * stepMinutes) }

    fun alarms(groupId: String, defaults: NewAlarmDefaults, newId: () -> String): List<AlarmEntity> =
        times.mapIndexed { i, time ->
            AlarmEntity(
                id = newId(),
                groupId = groupId,
                positionInGroup = i,
                hour = time.hour,
                minute = time.minute,
                daysOfWeek = days,
                label = null,
                isEnabled = true,
                timeoutMinutes = defaults.timeoutMinutes,
                snoozeEnabled = defaults.snoozeEnabled,
                snoozeMinutes = defaults.snoozeMinutes,
                snoozeLimit = defaults.snoozeLimit,
                readLabelAloud = defaults.readLabelAloud,
            )
        }

    companion object {
        val COUNTS = listOf(2, 3, 4, 5)
        val STEPS = listOf(5, 10, 15)
        val WEEKDAYS: Set<DayOfWeek> = DayOfWeek.entries.filter { it <= DayOfWeek.FRIDAY }.toSet()
        val EVERY_DAY: Set<DayOfWeek> = DayOfWeek.entries.toSet()
    }
}
