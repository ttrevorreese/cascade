package com.cascade.alarm.data

import com.cascade.alarm.data.db.AlarmEntity

/** What the firing screen may offer for snoozing this ring (added in M7, 2026-10-23). */
sealed interface SnoozeState {
    /** Snoozing rings again in [minutes]; [left] is how many snoozes remain, this one included. */
    data class Offer(val minutes: Int, val left: Int) : SnoozeState

    /** The group's last alarm never snoozes: "Last one. No snooze." (DESIGN.md §7). */
    data object NotOnLast : SnoozeState

    /** Every snooze for this ring has been used. */
    data object UsedUp : SnoozeState

    /** Snooze is off for this alarm, or this ring cannot snooze (a wake-up check). */
    data object Off : SnoozeState
}

object Snooze {
    const val DEFAULT_MINUTES = 5
    const val DEFAULT_LIMIT = 3
    val MINUTE_CHOICES = listOf(3, 5, 9, 10, 15)
    val LIMIT_CHOICES = listOf(1, 2, 3, 5)
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 30

    /**
     * [isLast]: this alarm is the last that rings in its group, counting only alarms that are on,
     * the same rule as the sequence dots and escalation.
     */
    fun state(alarm: AlarmEntity, isLast: Boolean, isWakeCheck: Boolean): SnoozeState = when {
        isWakeCheck || !alarm.snoozeEnabled -> SnoozeState.Off
        isLast -> SnoozeState.NotOnLast
        alarm.snoozesUsed >= alarm.snoozeLimit -> SnoozeState.UsedUp
        else -> SnoozeState.Offer(alarm.snoozeMinutes, alarm.snoozeLimit - alarm.snoozesUsed)
    }

    fun clampMinutes(minutes: Int) = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    fun clampLimit(limit: Int) = limit.coerceIn(LIMIT_CHOICES.first(), LIMIT_CHOICES.last())
}
