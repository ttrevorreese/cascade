package com.cascade.alarm.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cascade.alarm.data.Snooze
import com.cascade.alarm.data.challenge.ChallengeConfig
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

/**
 * SPEC §5 `Alarm`; sound and ramp are the group's ([AlarmGroupEntity.sound]). The last four
 * columns are engine bookkeeping, not user configuration.
 */
@Entity(
    tableName = "alarms",
    foreignKeys = [
        ForeignKey(
            entity = AlarmGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupId")],
)
data class AlarmEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    /** 0-based order within the group; drives escalation from M4. */
    val positionInGroup: Int,
    val hour: Int,
    val minute: Int,
    /** Empty = one-shot. */
    val daysOfWeek: Set<DayOfWeek>,
    val label: String?,
    /** The alarm's own switch. It rings only if this AND its group's isArmed are true. */
    val isEnabled: Boolean,
    /**
     * Unused since schema v7: the ramp belongs to the group ([AlarmGroupEntity.rampSeconds]).
     * Kept because SQLite cannot drop a column before API 34; migration 6→7 read it once.
     */
    val volumeRampSeconds: Int = 30,
    val timeoutMinutes: Int,
    /** What dismissing it takes; null = just tap Dismiss. From M4 it overrides the group's policy. */
    val challengeOverride: ChallengeConfig? = null,
    /** The instant currently handed to AlarmManager, or null if nothing is scheduled. */
    val nextTriggerAt: Instant? = null,
    /** Rings once at this instant ahead of the regular schedule (test alarm now, snooze later). */
    val oneTimeTriggerAt: Instant? = null,
    /** The scheduled instant of the most recent ring; makes a repeated fire for it a no-op. */
    val lastFiredFor: Instant? = null,
    /** A pending wake-up check after this alarm was dismissed as the group's last. */
    val wakeCheckAt: Instant? = null,
    /** Whether this alarm offers snooze; never on the group's last alarm ([com.cascade.alarm.data.Snooze]). */
    @ColumnInfo(defaultValue = "1")
    val snoozeEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "5") // Snooze.DEFAULT_MINUTES
    val snoozeMinutes: Int = Snooze.DEFAULT_MINUTES,
    /** How many times one ring may be snoozed. */
    @ColumnInfo(defaultValue = "3") // Snooze.DEFAULT_LIMIT
    val snoozeLimit: Int = Snooze.DEFAULT_LIMIT,
    /** Snoozes used by the ring in progress; engine bookkeeping, back to 0 when the ring ends. */
    @ColumnInfo(defaultValue = "0")
    val snoozesUsed: Int = 0,
    /** Say the time and [label] aloud while it rings (owner request, 2026-10-23); the voice is app-wide. */
    @ColumnInfo(defaultValue = "0")
    val readLabelAloud: Boolean = false,
)

val AlarmEntity.time: LocalTime get() = LocalTime.of(hour, minute)
