package com.cascade.alarm.engine.schedule

import androidx.room.withTransaction
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.CascadeDatabase
import com.cascade.alarm.data.db.EventType
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.EventLog
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
) {
    private val mutex = Mutex()

    sealed interface FireResult {
        /**
         * Ring. [alarm] is null if it could not be loaded; ring with defaults anyway. [wakeCheck]
         * means this trigger is the alarm's wake-up check, not the alarm itself.
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
            if (alarm.lastFiredFor == scheduledFor) {
                result = FireResult.Duplicate
                return@withTransaction null
            }
            val wakeCheck = scheduledFor == alarm.wakeCheckAt
            result = FireResult.Ring(alarm, wakeCheck)
            events.record(alarmId, EventType.FIRED, "scheduledFor" to scheduledFor, "wakeCheck" to wakeCheck)
            val armed = db.alarmGroupDao().get(alarm.groupId)?.isArmed ?: true
            planAndPersist(alarm, ReconcilePlanner.afterFiring(alarm, scheduledFor), armed, "fired")
        }
        plan?.let(::apply)
        result
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

    /** Called when the user dismisses [alarmId]: schedules a wake-up check if it was the group's last. */
    suspend fun scheduleWakeCheck(alarmId: String) = mutate("wake_check") { db ->
        val alarm = db.alarmDao().get(alarmId) ?: return@mutate
        val group = db.alarmGroupDao().get(alarm.groupId) ?: return@mutate
        val at = WakeCheck.checkAt(alarm, group, db.alarmDao().inGroup(alarm.groupId), time.now()) ?: return@mutate
        db.alarmDao().upsert(alarm.copy(wakeCheckAt = at))
    }

    private suspend fun planAll(reason: String): List<Plan> {
        val armed = db.alarmGroupDao().all().associate { it.id to it.isArmed }
        return db.alarmDao().all().map { alarm ->
            planAndPersist(alarm, alarm, armed[alarm.groupId] ?: false, reason)
        }
    }

    private suspend fun planAndPersist(before: AlarmEntity, edited: AlarmEntity, groupArmed: Boolean, reason: String): Plan {
        val plan = ReconcilePlanner.plan(edited, groupArmed, time.now(), time.zone())
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
