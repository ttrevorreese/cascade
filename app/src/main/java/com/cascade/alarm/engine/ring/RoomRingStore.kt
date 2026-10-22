package com.cascade.alarm.engine.ring

import com.cascade.alarm.data.db.ActiveRingDao
import com.cascade.alarm.data.db.ActiveRingEntity
import com.cascade.alarm.data.db.AlarmDao
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.EventType
import com.cascade.alarm.engine.EventLog
import com.cascade.alarm.engine.schedule.Reconciler
import java.time.Instant
import javax.inject.Inject

class RoomRingStore @Inject constructor(
    private val reconciler: Reconciler,
    private val alarmDao: AlarmDao,
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
        // A one-shot alarm switches itself off when it rings, so it still counts itself.
        val sequence = alarmDao.inGroup(alarm.groupId).filter { it.isEnabled || it.id == alarm.id }
        return SequencePosition(sequence.indexOfFirst { it.id == alarm.id }.coerceAtLeast(0), sequence.size.coerceAtLeast(1))
    }
}
