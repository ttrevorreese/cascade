package com.cascade.alarm.data

import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.engine.ring.RingDefaults
import java.time.DayOfWeek

/** M1 only: the single alarm the engine screen edits. M2 replaces this with user-created alarms. */
object HardcodedAlarm {
    const val ID = "m1-hardcoded"

    val DEFAULT = AlarmEntity(
        id = ID,
        hour = 7,
        minute = 0,
        daysOfWeek = DayOfWeek.entries.toSet(),
        label = "Engine test",
        isEnabled = false,
        volumeRampSeconds = RingDefaults.RAMP_SECONDS,
        timeoutMinutes = RingDefaults.TIMEOUT_MINUTES,
    )
}
