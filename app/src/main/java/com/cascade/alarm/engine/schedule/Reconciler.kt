package com.cascade.alarm.engine.schedule

import androidx.room.withTransaction
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.CascadeDatabase
import com.cascade.alarm.data.db.EventType
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.EventLog
import com.cascade.alarm.engine.TimeSource
import com.cascade.alarm.engine.schedule.ReconcilePlanner.Action
import com.cascade.alarm.engine.schedule.ReconcilePlanner.Plan
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns every write to alarm scheduling state. Each entry point recomputes from the database and
 * re-issues every AlarmManager registration, so running it again is always safe (SPEC §4.1).
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
        /** Ring. [alarm] is null if it could not be loaded; ring with defaults anyway. */
        data class Ring(val alarm: AlarmEntity?) : FireResult
        data object Duplicate : FireResult
    }

    suspend fun reconcileAll(reason: String) = mutex.withLock {
        EngineLog.i("reconcile reason=$reason")
        val plans = db.withTransaction {
            db.alarmDao().all().map { alarm -> planAndPersist(alarm, alarm, reason) }
        }
        plans.forEach(::apply)
    }

    /** Applies a user/debug edit to one alarm and reschedules it. */
    suspend fun edit(alarmId: String, reason: String, transform: (AlarmEntity) -> AlarmEntity) = mutex.withLock {
        val plan = db.withTransaction {
            val before = db.alarmDao().get(alarmId) ?: return@withTransaction null
            planAndPersist(before, transform(before), reason)
        }
        plan?.let(::apply)
    }

    /** Records that the trigger for [scheduledFor] went off and schedules the following one. */
    suspend fun recordFire(alarmId: String, scheduledFor: java.time.Instant): FireResult = mutex.withLock {
        var result: FireResult = FireResult.Ring(null)
        val plan = db.withTransaction {
            val alarm = db.alarmDao().get(alarmId) ?: return@withTransaction null
            if (alarm.lastFiredFor == scheduledFor) {
                result = FireResult.Duplicate
                return@withTransaction null
            }
            result = FireResult.Ring(alarm)
            events.record(alarmId, EventType.FIRED, "scheduledFor" to scheduledFor)
            planAndPersist(alarm, ReconcilePlanner.afterFiring(alarm, scheduledFor), "fired")
        }
        plan?.let(::apply)
        result
    }

    private suspend fun planAndPersist(before: AlarmEntity, edited: AlarmEntity, reason: String): Plan {
        val plan = ReconcilePlanner.plan(edited, time.now(), time.zone())
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
