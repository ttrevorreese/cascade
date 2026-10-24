package com.cascade.alarm.data

import android.content.Context
import com.cascade.alarm.engine.ring.RingDefaults
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the first alarm of a group starts with (SPEC §7 Settings, "defaults"). Later alarms copy
 * the alarm before them instead, because a group is a sequence.
 */
data class NewAlarmDefaults(
    val timeoutMinutes: Int = RingDefaults.TIMEOUT_MINUTES,
    val snoozeEnabled: Boolean = true,
    val snoozeMinutes: Int = Snooze.DEFAULT_MINUTES,
    val snoozeLimit: Int = Snooze.DEFAULT_LIMIT,
    val readLabelAloud: Boolean = false,
) {
    companion object {
        val TIMEOUT_CHOICES = listOf(5, 10, 15, 20, 30)
    }
}

/** Only read when an alarm is created, never on the ring path, so credential storage is fine. */
@Singleton
class NewAlarmDefaultsPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val value: StateFlow<NewAlarmDefaults> = mutable.asStateFlow()

    fun set(defaults: NewAlarmDefaults) {
        prefs.edit()
            .putInt(TIMEOUT, defaults.timeoutMinutes)
            .putBoolean(SNOOZE, defaults.snoozeEnabled)
            .putInt(SNOOZE_MINUTES, defaults.snoozeMinutes)
            .putInt(SNOOZE_LIMIT, defaults.snoozeLimit)
            .putBoolean(READ_ALOUD, defaults.readLabelAloud)
            .apply()
        mutable.value = defaults
    }

    private fun read(): NewAlarmDefaults {
        val d = NewAlarmDefaults()
        return NewAlarmDefaults(
            timeoutMinutes = prefs.getInt(TIMEOUT, d.timeoutMinutes),
            snoozeEnabled = prefs.getBoolean(SNOOZE, d.snoozeEnabled),
            snoozeMinutes = prefs.getInt(SNOOZE_MINUTES, d.snoozeMinutes),
            snoozeLimit = prefs.getInt(SNOOZE_LIMIT, d.snoozeLimit),
            readLabelAloud = prefs.getBoolean(READ_ALOUD, d.readLabelAloud),
        )
    }

    private companion object {
        const val NAME = "new_alarm_defaults"
        const val TIMEOUT = "timeout_minutes"
        const val SNOOZE = "snooze"
        const val SNOOZE_MINUTES = "snooze_minutes"
        const val SNOOZE_LIMIT = "snooze_limit"
        const val READ_ALOUD = "read_aloud"
    }
}
