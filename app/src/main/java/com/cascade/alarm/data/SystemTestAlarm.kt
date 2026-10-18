package com.cascade.alarm.data

import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupDefaults
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.engine.ring.RingDefaults
import java.time.Instant

/**
 * A hidden alarm used by "Ring in 1 minute" and scripts/e2e-alarm.sh to exercise the real ring
 * path (the seed of SPEC §4.3's Test Alarm). Its group is always armed and never listed; the
 * alarm's regular schedule is off, so it only ever rings via its one-time trigger.
 */
object SystemTestAlarm {
    const val GROUP_ID = "system-test-group"
    const val ALARM_ID = "system-test-alarm"

    fun group(now: Instant) = AlarmGroupEntity(
        id = GROUP_ID,
        name = "Test alarm",
        colorHex = AlarmGroupDefaults.COLOR_HEX,
        iconKey = AlarmGroupDefaults.ICON_KEY,
        isArmed = true,
        sortOrder = Int.MAX_VALUE,
        createdAt = now,
    )

    fun alarm() = AlarmEntity(
        id = ALARM_ID,
        groupId = GROUP_ID,
        positionInGroup = 0,
        hour = 7,
        minute = 0,
        daysOfWeek = emptySet(),
        label = "Test alarm",
        isEnabled = false,
        volumeRampSeconds = RingDefaults.RAMP_SECONDS,
        timeoutMinutes = RingDefaults.TIMEOUT_MINUTES,
    )
}
