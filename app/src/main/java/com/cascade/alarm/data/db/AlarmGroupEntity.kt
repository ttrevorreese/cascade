package com.cascade.alarm.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cascade.alarm.data.challenge.EscalationPolicy
import java.time.Instant

/**
 * SPEC §5 `AlarmGroup`. Arming is a master switch that never mutates its alarms' own isEnabled
 * values.
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
    /** Changing the first alarm's time moves every other alarm in the group by the same amount. */
    @ColumnInfo(defaultValue = "1")
    val shiftWithFirst: Boolean = true,
    /** After the last alarm is dismissed, ask "are you awake?" and ring it again if unanswered. */
    @ColumnInfo(defaultValue = "0")
    val wakeCheckEnabled: Boolean = false,
    /** Minutes between dismissing the last alarm and the check (1–5). */
    @ColumnInfo(defaultValue = "3")
    val wakeCheckDelayMinutes: Int = 3,
    /** The challenge ladder by position (SPEC §5). Empty: no challenges unless an alarm sets one. */
    @ColumnInfo(defaultValue = "{}")
    val escalationPolicy: EscalationPolicy = EscalationPolicy(),
)
