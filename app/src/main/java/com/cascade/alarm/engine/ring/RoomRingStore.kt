package com.cascade.alarm.engine.ring

import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.Escalation
import com.cascade.alarm.data.challenge.EscalationPolicy
import com.cascade.alarm.data.db.ActiveRingDao
import com.cascade.alarm.data.db.ActiveRingEntity
import com.cascade.alarm.data.db.AlarmDao
import com.cascade.alarm.data.db.AlarmGroupDao
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.EventType
import com.cascade.alarm.engine.EventLog
import com.cascade.alarm.engine.schedule.Reconciler
import java.time.Instant
import javax.inject.Inject

class RoomRingStore @Inject constructor(
    private val reconciler: Reconciler,
    private val alarmDao: AlarmDao,
    private val groupDao: AlarmGroupDao,
    private val activeRingDao: ActiveRingDao,
    private val events: EventLog,
) : RingStore {
    override suspend fun recordFire(alarmId: String, scheduledFor: Instant) = reconciler.recordFire(alarmId, scheduledFor)
    override suspend fun alarm(alarmId: String) = alarmDao.get(alarmId)
    override suspend fun activeRing() = activeRingDao.get()
    override suspend fun setActiveRing(ring: ActiveRingEntity) = activeRingDao.set(ring)
    override suspend fun clearActiveRing() = activeRingDao.clear()
    override suspend fun log(alarmId: String, type: EventType, vararg metadata: Pair<String, Any?>) =
        events.record(alarmId, type, *metadata)
    override suspend fun afterDismiss(alarmId: String) = reconciler.scheduleWakeCheck(alarmId)
    override suspend fun sequencePosition(alarm: AlarmEntity): SequencePosition {
        val group = alarmDao.inGroup(alarm.groupId)
        // A one-shot alarm switches itself off when it rings, so it still counts itself.
        val total = group.count { it.isEnabled || it.id == alarm.id }.coerceAtLeast(1)
        return SequencePosition(Escalation.position(alarm, group), total)
    }

    /** Resolved at fire time, never cached, so a ladder edited last night applies this morning. */
    override suspend fun challengeFor(alarm: AlarmEntity): ChallengeConfig? {
        val policy = groupDao.get(alarm.groupId)?.escalationPolicy ?: EscalationPolicy()
        return Escalation.resolve(alarm, policy, Escalation.position(alarm, alarmDao.inGroup(alarm.groupId)))
    }
}
