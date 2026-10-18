package com.cascade.alarm.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * SPEC §5 `AlarmGroup`, minus escalationPolicy (arrives with M4). Arming is a master switch that
 * never mutates its alarms' own isEnabled values.
 */
@Entity(tableName = "alarm_groups")
data class AlarmGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorHex: String,
    val iconKey: String,
    val isArmed: Boolean,
    val sortOrder: Int,
    val createdAt: Instant,
)
