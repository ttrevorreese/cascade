package com.cascade.alarm.engine.schedule

import androidx.room.withTransaction
import com.cascade.alarm.data.challenge.Escalation
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.CascadeDatabase
import com.cascade.alarm.data.db.EventType
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.EventLog
import com.cascade.alarm.engine.ScheduleObserver
import com.cascade.alarm.engine.TimeSource
import com.cascade.alarm.engine.receivers.AlarmFireReceiver
import com.cascade.alarm.engine.schedule.ReconcilePlanner.Action
import com.cascade.alarm.engine.schedule.ReconcilePlanner.Plan
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns every write to alarm and group state. Each entry point recomputes the whole schedule from
 * the database and re-issues every AlarmManager registration, so running it again is always safe
 * (SPEC §4.1). Edits go through [mutate] so they can never race a reconciliation.
 */
@Singleton
class Reconciler @Inject constructor(
    private val db: CascadeDatabase,
    private val scheduler: AlarmScheduler,
    private val time: TimeSource,
    private val events: EventLog,
    private val observer: ScheduleObserver = ScheduleObserver.None,
) {
    private val mutex = Mutex()

    sealed interface FireResult {
        /**
         * Ring. [alarm] is the row as saved after this fire (snoozes reset for a regular ring, a
         * one-shot switched off), or null if it could not be loaded; ring with defaults anyway.
         * [wakeCheck] means this trigger is the alarm's wake-up check, not the alarm itself.
         */
        data class Ring(val alarm: AlarmEntity?, val wakeCheck: Boolean = false) : FireResult
        data object Duplicate : FireResult
        /** The alarm was deleted after this trigger was handed to AlarmManager. */
        data object Deleted : FireResult
    }

    suspend fun reconcileAll(reason: String) = mutate(reason) {}

    /**
     * Runs [block] (database edits) and a full reconciliation in one transaction under the
     * scheduling lock, then updates AlarmManager, including cancelling alarms [block] deleted.
     */
    suspend fun <T> mutate(reason: String, block: suspend (CascadeDatabase) -> T): T = mutex.withLock {
        EngineLog.i("reconcile reason=$reason")
        val (result, before, plans) = db.withTransaction {
            val before = db.alarmDao().all().map { it.id }.toSet()
            val result = block(db)
            Triple(result, before, planAll(reason))
        }
        (before - plans.map { it.alarm.id }.toSet()).forEach(scheduler::cancel)
        plans.forEach(::apply)
        tellObserver()
        result
    }

    /** Records that the trigger for [scheduledFor] went off and schedules the following one. */
    suspend fun recordFire(alarmId: String, scheduledFor: Instant): FireResult = mutex.withLock {
        var result: FireResult = FireResult.Ring(null)
        val plan = db.withTransaction {
            val alarm = db.alarmDao().get(alarmId)
            if (alarm == null) {
                // A readable database without the row means the alarm was deleted. Only a fire
                // whose id was lost entirely still rings blind.
                if (alarmId != AlarmFireReceiver.UNKNOWN_ALARM) result = FireResult.Deleted
                return@withTransaction null
            }
            val group = db.alarmGroupDao().get(alarm.groupId)
            val armed = group?.isArmed ?: true
            val timing = group?.let(GroupTiming::of) ?: GroupTiming.NONE
            if (alarm.lastFiredFor == scheduledFor) {
                result = FireResult.Duplicate
                // AlarmManager has used up this alarm's registration (e.g. the clock was set back and
                // the trigger came round again), so schedule the next one or nothing rings tomorrow.
                return@withTransaction planAndPersist(alarm, alarm, armed, "duplicate", timing)
            }
            val wakeCheck = scheduledFor == alarm.wakeCheckAt
            events.record(alarmId, EventType.FIRED, "scheduledFor" to scheduledFor, "wakeCheck" to wakeCheck)
            planAndPersist(alarm, ReconcilePlanner.afterFiring(alarm, scheduledFor), armed, "fired", timing).also {
                result = FireResult.Ring(it.alarm, wakeCheck)
            }
        }
        plan?.let(::apply)
        tellObserver()
        result
    }

    private fun tellObserver() {
        try {
            observer.scheduleChanged()
        } catch (e: Exception) {
            EngineLog.e("schedule observer failed", e)
        }
    }

    /** The user snoozed [alarmId]: it rings again at [at], through the one-time trigger. */
    suspend fun snooze(alarmId: String, at: Instant) = mutate("snooze") { db ->
        val alarm = db.alarmDao().get(alarmId) ?: return@mutate
        db.alarmDao().upsert(alarm.copy(oneTimeTriggerAt = at, snoozesUsed = alarm.snoozesUsed + 1))
    }

    /** A ring ended other than by snoozing; its count starts again next time. */
    suspend fun clearSnoozes(alarmId: String) = mutate("snooze_reset") { db ->
        val alarm = db.alarmDao().get(alarmId) ?: return@mutate
        if (alarm.snoozesUsed != 0) db.alarmDao().upsert(alarm.copy(snoozesUsed = 0))
    }

    /**
     * Called when the user dismisses [alarmId]. Earlier alarms' pending snoozes are dropped: the
     * user is past them, and they would only ring an easier alarm again (one landing after the
     * hardest was solved would undo the ladder). If it was the last alarm of its cascade, the
     * wake-up check is scheduled.
     */
    suspend fun afterDismiss(alarmId: String) = mutate("dismissed") { db ->
        val alarm = db.alarmDao().get(alarmId) ?: return@mutate
        val group = db.alarmGroupDao().get(alarm.groupId) ?: return@mutate
        val groupAlarms = db.alarmDao().inGroup(alarm.groupId)
        val snoozed = groupAlarms.filter { it.id != alarm.id && it.oneTimeTriggerAt != null && it.positionInGroup < alarm.positionInGroup }
        if (snoozed.isNotEmpty()) {
            EngineLog.i("dismissed alarm=$alarmId; dropping earlier snoozes of ${snoozed.map { it.id }}")
            db.alarmDao().upsertAll(snoozed.map { it.copy(oneTimeTriggerAt = null) })
        }
        val ringAt = alarm.lastFiredFor ?: time.now()
        val at = WakeCheck.checkAt(alarm, group, groupAlarms, ringAt, time.now()) ?: return@mutate
        db.alarmDao().upsert(alarm.copy(wakeCheckAt = at))
    }

    private suspend fun planAll(reason: String): List<Plan> {
        val groups = db.alarmGroupDao().all().map { group ->
            // A skip or one-off move that is over is dropped, so it can never catch a later morning.
            val timing = GroupTiming.of(group)
            val cleared = timing.cleared(time.now(), time.zone())
            if (cleared == timing) {
                group
            } else {
                group.copy(skipUntil = cleared.skipUntil, onceFrom = cleared.onceFrom, onceShiftMinutes = cleared.onceShiftMinutes)
                    .also { db.alarmGroupDao().upsert(it) }
            }
        }.associateBy { it.id }
        return db.alarmDao().all().map { alarm ->
            val group = groups[alarm.groupId]
            planAndPersist(alarm, alarm, group?.isArmed ?: false, reason, group?.let(GroupTiming::of) ?: GroupTiming.NONE)
        }
    }

    private suspend fun planAndPersist(
        before: AlarmEntity,
        edited: AlarmEntity,
        groupArmed: Boolean,
        reason: String,
        timing: GroupTiming = GroupTiming.NONE,
    ): Plan {
        val plan = ReconcilePlanner.plan(edited, groupArmed, time.now(), time.zone(), timing)
        if (plan.alarm != before) db.alarmDao().upsert(plan.alarm)
        plan.missed?.let {
            events.record(before.id, EventType.MISSED, "scheduledFor" to it, "reason" to "not_fired")
        }
        val next = plan.alarm.nextTriggerAt
        if (next != null && next != before.nextTriggerAt) {
            events.record(before.id, EventType.SCHEDULED, "at" to next, "reason" to reason)
        }
        return plan
    }

    private fun apply(plan: Plan) = when (val action = plan.action) {
        is Action.Schedule -> scheduler.schedule(plan.alarm.id, action.at)
        Action.Cancel -> scheduler.cancel(plan.alarm.id)
    }
}
