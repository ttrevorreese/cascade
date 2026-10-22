package com.cascade.alarm.engine.schedule

import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import java.time.Duration
import java.time.Instant

/**
 * The wake-up check: a few minutes after the last alarm of a sequence is dismissed, a quiet
 * "are you awake?" prompt appears. Left unanswered, the last alarm rings again.
 */
object WakeCheck {
    const val MIN_DELAY_MINUTES = 1
    const val MAX_DELAY_MINUTES = 5

    /** Time allowed to answer the prompt before the alarm rings again. */
    val ANSWER_WINDOW: Duration = Duration.ofSeconds(60)

    /**
     * When to check after [alarm] was dismissed at [dismissedAt], or null for no check. Only the
     * group's last alarm is followed by one: an earlier alarm still has the rest of the sequence
     * behind it. Switched-off alarms later in the group do not count; [alarm] itself may be off
     * because a one-shot alarm disables itself when it rings.
     */
    fun checkAt(alarm: AlarmEntity, group: AlarmGroupEntity, groupAlarms: List<AlarmEntity>, dismissedAt: Instant): Instant? {
        if (!group.isArmed || !group.wakeCheckEnabled) return null
        if (groupAlarms.any { it.id != alarm.id && it.isEnabled && it.positionInGroup > alarm.positionInGroup }) return null
        return dismissedAt.plus(Duration.ofMinutes(clampDelay(group.wakeCheckDelayMinutes).toLong()))
    }

    fun clampDelay(minutes: Int): Int = minutes.coerceIn(MIN_DELAY_MINUTES, MAX_DELAY_MINUTES)
}
