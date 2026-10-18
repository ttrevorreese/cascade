package com.cascade.alarm.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

/**
 * M1 subset of SPEC §5 `Alarm`; groups, sound, challenge and snooze columns arrive in later
 * milestones. The last three columns are engine bookkeeping, not user configuration.
 */
@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey val id: String,
    val hour: Int,
    val minute: Int,
    /** Empty = one-shot. */
    val daysOfWeek: Set<DayOfWeek>,
    val label: String?,
    val isEnabled: Boolean,
    val volumeRampSeconds: Int,
    val timeoutMinutes: Int,
    /** The instant currently handed to AlarmManager, or null if nothing is scheduled. */
    val nextTriggerAt: Instant? = null,
    /** Rings once at this instant ahead of the regular schedule (test alarm now, snooze later). */
    val oneTimeTriggerAt: Instant? = null,
    /** The scheduled instant of the most recent ring; makes a repeated fire for it a no-op. */
    val lastFiredFor: Instant? = null,
)

val AlarmEntity.time: LocalTime get() = LocalTime.of(hour, minute)
