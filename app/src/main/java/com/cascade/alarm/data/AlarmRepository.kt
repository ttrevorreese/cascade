package com.cascade.alarm.data

import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupDefaults
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.data.db.CascadeDatabase
import com.cascade.alarm.engine.TimeSource
import com.cascade.alarm.engine.schedule.Reconciler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The UI's way into alarms and groups. Every write runs inside [Reconciler.mutate], so the
 * AlarmManager schedule is rebuilt in the same transaction as the edit (SPEC §4.1, M2).
 */
@Singleton
class AlarmRepository @Inject constructor(
    private val db: CascadeDatabase,
    private val reconciler: Reconciler,
    private val time: TimeSource,
) {
    private val groups get() = db.alarmGroupDao()
    private val alarms get() = db.alarmDao()

    fun observeGroupSummaries(): Flow<List<GroupSummary>> =
        combine(groups.observeAll(), alarms.observeAll()) { allGroups, allAlarms ->
            val byGroup = allAlarms.groupBy { it.groupId }
            allGroups.filter { it.id != SystemTestAlarm.GROUP_ID }
                .map { AlarmRules.summarize(it, byGroup[it.id].orEmpty()) }
        }

    fun observeGroup(id: String): Flow<AlarmGroupEntity?> = groups.observe(id)

    fun observeAlarms(groupId: String): Flow<List<AlarmEntity>> = alarms.observeInGroup(groupId)

    suspend fun alarm(id: String): AlarmEntity? = alarms.get(id)

    /** An unsaved alarm for [groupId], pre-filled as the next step of the sequence. */
    suspend fun newAlarmDraft(groupId: String): AlarmEntity =
        AlarmRules.newAlarm(UUID.randomUUID().toString(), groupId, alarms.inGroup(groupId))

    // --- Groups ---

    suspend fun createGroup(name: String): String = reconciler.mutate("group_create") {
        val id = UUID.randomUUID().toString()
        val nextOrder = (visibleGroups().maxOfOrNull { it.sortOrder } ?: -1) + 1
        groups.upsert(
            AlarmGroupEntity(
                id = id,
                name = groupName(name),
                colorHex = AlarmGroupDefaults.COLOR_HEX,
                iconKey = AlarmGroupDefaults.ICON_KEY,
                isArmed = true,
                sortOrder = nextOrder,
                createdAt = time.now(),
            ),
        )
        id
    }

    suspend fun renameGroup(id: String, name: String) = reconciler.mutate("group_rename") {
        groups.get(id)?.let { groups.upsert(it.copy(name = groupName(name))) }
    }

    /** The master toggle. Never touches the alarms' own isEnabled values (SPEC §5). */
    suspend fun setGroupArmed(id: String, armed: Boolean) = reconciler.mutate("group_armed_$armed") {
        groups.get(id)?.let { groups.upsert(it.copy(isArmed = armed)) }
    }

    suspend fun deleteGroup(id: String) = reconciler.mutate("group_delete") {
        // Explicit as well as the foreign-key cascade, so it cannot depend on PRAGMA foreign_keys.
        alarms.deleteInGroup(id)
        groups.delete(id)
    }

    suspend fun moveGroup(id: String, delta: Int) = reconciler.mutate("group_move") {
        val ordered = visibleGroups()
        val moved = AlarmRules.move(ordered, ordered.indexOfFirst { it.id == id }, delta)
        groups.upsertAll(moved.mapIndexed { index, group -> group.copy(sortOrder = index) })
    }

    // --- Alarms ---

    /** Creates or updates an alarm. Only user-editable fields are taken from [alarm]. */
    suspend fun saveAlarm(alarm: AlarmEntity) = reconciler.mutate("alarm_save") {
        val existing = alarms.get(alarm.id)
        val base = existing ?: alarm.copy(
            positionInGroup = alarms.inGroup(alarm.groupId).size,
            nextTriggerAt = null,
            oneTimeTriggerAt = null,
            lastFiredFor = null,
        )
        alarms.upsert(
            base.copy(
                hour = alarm.hour.coerceIn(0, 23),
                minute = alarm.minute.coerceIn(0, 59),
                daysOfWeek = alarm.daysOfWeek,
                label = AlarmRules.normalizeLabel(alarm.label),
                isEnabled = alarm.isEnabled,
                volumeRampSeconds = AlarmRules.clampRamp(alarm.volumeRampSeconds),
                timeoutMinutes = AlarmRules.clampTimeout(alarm.timeoutMinutes),
            ),
        )
    }

    /** Turning an alarm off also cancels a pending snooze or test ring for it. */
    suspend fun setAlarmEnabled(id: String, enabled: Boolean) = reconciler.mutate("alarm_enabled_$enabled") {
        alarms.get(id)?.let {
            alarms.upsert(it.copy(isEnabled = enabled, oneTimeTriggerAt = if (enabled) it.oneTimeTriggerAt else null))
        }
    }

    suspend fun deleteAlarm(id: String) = reconciler.mutate("alarm_delete") {
        val alarm = alarms.get(id) ?: return@mutate
        alarms.delete(id)
        renumber(alarms.inGroup(alarm.groupId))
    }

    /** Reordering changes positionInGroup, which drives escalation from M4. */
    suspend fun moveAlarm(id: String, delta: Int) = reconciler.mutate("alarm_move") {
        val alarm = alarms.get(id) ?: return@mutate
        val ordered = alarms.inGroup(alarm.groupId)
        renumber(AlarmRules.move(ordered, ordered.indexOfFirst { it.id == id }, delta))
    }

    // --- Test alarm ---

    /** Rings the hidden test alarm after [seconds], through the real AlarmManager path. */
    suspend fun ringTestAlarmIn(seconds: Long) = reconciler.mutate("test_ring") {
        ensureTestAlarmRows()
        alarms.get(SystemTestAlarm.ALARM_ID)?.let {
            alarms.upsert(it.copy(oneTimeTriggerAt = time.now().plusSeconds(seconds)))
        }
    }

    suspend fun editTestAlarm(reason: String, transform: (AlarmEntity) -> AlarmEntity) = reconciler.mutate(reason) {
        ensureTestAlarmRows()
        alarms.get(SystemTestAlarm.ALARM_ID)?.let { alarms.upsert(transform(it)) }
    }

    private suspend fun ensureTestAlarmRows() {
        groups.insertIfAbsent(SystemTestAlarm.group(time.now()))
        alarms.insertIfAbsent(SystemTestAlarm.alarm())
    }

    private suspend fun visibleGroups() = groups.all().filter { it.id != SystemTestAlarm.GROUP_ID }

    private suspend fun renumber(ordered: List<AlarmEntity>) =
        alarms.upsertAll(ordered.mapIndexed { index, alarm -> alarm.copy(positionInGroup = index) })

    private fun groupName(name: String) = name.trim().ifEmpty { "Untitled group" }
}
