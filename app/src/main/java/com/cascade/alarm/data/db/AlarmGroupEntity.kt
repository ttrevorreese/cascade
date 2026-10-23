package com.cascade.alarm.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cascade.alarm.data.challenge.EscalationPolicy
import com.cascade.alarm.data.sound.SoundConfig
import com.cascade.alarm.data.sound.SoundSettings
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
    /** What every alarm in the group plays (decided 2026-10-23: one sound per group). */
    @ColumnInfo(defaultValue = SoundConfig.DEFAULT_JSON)
    val sound: SoundConfig = SoundConfig.DEFAULT,
    /** Seconds from [rampStartPercent] to full volume ([SoundSettings]: 30–60). */
    @ColumnInfo(defaultValue = "45") // SoundSettings.DEFAULT_RAMP_SECONDS
    val rampSeconds: Int = SoundSettings.DEFAULT_RAMP_SECONDS,
    /** Perceived loudness the ramp starts from, in percent of full. */
    @ColumnInfo(defaultValue = "15") // SoundSettings.DEFAULT_START_PERCENT
    val rampStartPercent: Int = SoundSettings.DEFAULT_START_PERCENT,
    /**
     * The group's own theme (DESIGN.md §11), or null to follow the app theme. Resolved at fire time
     * by [com.cascade.alarm.ui.theme.ThemeResolution]; an unknown or unowned id falls back silently.
     */
    val themeId: String? = null,
)
