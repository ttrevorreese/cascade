package com.cascade.alarm.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * The alarm that is ringing right now, persisted so a ring survives process death and power-off.
 * Single row: at most one alarm rings at a time.
 */
@Entity(tableName = "active_ring")
data class ActiveRingEntity(
    @PrimaryKey val slot: Int = 0,
    val alarmId: String,
    val scheduledFor: Instant,
    /** Start of the current timeout window; reset when a ring resumes after a reboot. */
    val startedAt: Instant,
    /** A wake-up check rather than the alarm itself; failing it rings the alarm again. */
    @ColumnInfo(defaultValue = "0")
    val isWakeCheck: Boolean = false,
)
