package com.cascade.alarm.engine.schedule

import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.time
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Pure scheduling decisions for one alarm. [Reconciler] runs this over every alarm from scratch on
 * each trigger (boot, time/zone change, edit, fire, app start), so it must be idempotent.
 */
object ReconcilePlanner {
    /** A trigger missed by at most this long (e.g. phone was off) rings as soon as we notice. */
    val CATCH_UP_WINDOW: Duration = Duration.ofMinutes(15)

    sealed interface Action {
        /** Hand [at] to AlarmManager. A past instant makes it fire immediately. */
        data class Schedule(val at: Instant) : Action
        data object Cancel : Action
    }

    /**
     * @param alarm the alarm with updated bookkeeping, to be persisted.
     * @param missed a trigger that should have rung but never did and is now too late to ring.
     */
    data class Plan(val alarm: AlarmEntity, val action: Action, val missed: Instant? = null)

    /**
     * The regular schedule rings only if [groupArmed] and [AlarmEntity.isEnabled] (SPEC §5).
     *
     * The one-time trigger rings regardless of [AlarmEntity.isEnabled]: a one-shot alarm disables
     * itself when it rings, yet its snooze must still fire. Disarming the group cancels everything
     * in it, one-time triggers included. A pending wake-up check works the same way.
     */
    fun plan(alarm: AlarmEntity, groupArmed: Boolean, now: Instant, zone: ZoneId): Plan {
        if (!groupArmed) {
            return Plan(alarm.copy(nextTriggerAt = null, oneTimeTriggerAt = null, wakeCheckAt = null), Action.Cancel)
        }
        var current = alarm
        var missed: Instant? = null
        val pending = alarm.nextTriggerAt
        if (pending != null && !pending.isAfter(now) && pending != alarm.lastFiredFor) {
            if (Duration.between(pending, now) <= CATCH_UP_WINDOW) {
                return Plan(alarm, Action.Schedule(pending))
            }
            missed = pending
            // Mark it handled so the next pass doesn't report it again.
            current = current.copy(lastFiredFor = pending)
            if (pending == alarm.oneTimeTriggerAt) {
                current = current.copy(oneTimeTriggerAt = null)
            } else if (pending == alarm.wakeCheckAt) {
                current = current.copy(wakeCheckAt = null)
            } else if (alarm.daysOfWeek.isEmpty()) {
                current = current.copy(isEnabled = false)
            }
        }

        val oneTime = current.oneTimeTriggerAt?.takeIf { it.isAfter(now) }
        val wakeCheck = current.wakeCheckAt?.takeIf { it.isAfter(now) }
        val regular = if (current.isEnabled) NextOccurrence.after(current.time, current.daysOfWeek, now, zone) else null
        val next = listOfNotNull(oneTime, wakeCheck, regular).minOrNull()
        val action = if (next != null) Action.Schedule(next) else Action.Cancel
        return Plan(current.copy(nextTriggerAt = next, oneTimeTriggerAt = oneTime, wakeCheckAt = wakeCheck), action, missed)
    }

    /** Bookkeeping when the trigger for [scheduledFor] rings. Follow with [plan] to schedule the next. */
    fun afterFiring(alarm: AlarmEntity, scheduledFor: Instant): AlarmEntity = when {
        scheduledFor == alarm.oneTimeTriggerAt -> alarm.copy(lastFiredFor = scheduledFor, oneTimeTriggerAt = null)
        scheduledFor == alarm.wakeCheckAt -> alarm.copy(lastFiredFor = scheduledFor, wakeCheckAt = null)
        alarm.daysOfWeek.isEmpty() -> alarm.copy(lastFiredFor = scheduledFor, isEnabled = false)
        else -> alarm.copy(lastFiredFor = scheduledFor)
    }
}
