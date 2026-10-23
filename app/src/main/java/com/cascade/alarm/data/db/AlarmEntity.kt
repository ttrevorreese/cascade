package com.cascade.alarm.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cascade.alarm.data.challenge.ChallengeConfig
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

/**
 * SPEC §5 `Alarm`; sound, challenge and snooze columns arrive in later milestones. The last four
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
    val volumeRampSeconds: Int,
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
)

val AlarmEntity.time: LocalTime get() = LocalTime.of(hour, minute)
