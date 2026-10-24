package com.cascade.alarm.data

import com.cascade.alarm.data.challenge.EscalationPolicy
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupDefaults
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.data.db.CascadeDatabase
import com.cascade.alarm.data.db.time
import com.cascade.alarm.data.sound.SoundConfig
import com.cascade.alarm.data.sound.SoundFiles
import com.cascade.alarm.data.sound.SoundSettings
import com.cascade.alarm.engine.TimeSource
import com.cascade.alarm.engine.schedule.WakeCheck
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
    private val soundFiles: SoundFiles,
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
    suspend fun newAlarmDraft(groupId: String, defaults: NewAlarmDefaults = NewAlarmDefaults()): AlarmEntity =
        AlarmRules.newAlarm(UUID.randomUUID().toString(), groupId, alarms.inGroup(groupId), defaults)

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

    suspend fun setShiftWithFirst(id: String, enabled: Boolean) = reconciler.mutate("group_shift_setting") {
        groups.get(id)?.let { groups.upsert(it.copy(shiftWithFirst = enabled)) }
    }

    /** Turning the wake-up check off also cancels one already pending in the group. */
    suspend fun setWakeCheck(id: String, enabled: Boolean, delayMinutes: Int) = reconciler.mutate("group_wake_check_$enabled") {
        val group = groups.get(id) ?: return@mutate
        groups.upsert(group.copy(wakeCheckEnabled = enabled, wakeCheckDelayMinutes = WakeCheck.clampDelay(delayMinutes)))
        if (!enabled) alarms.upsertAll(alarms.inGroup(id).map { it.copy(wakeCheckAt = null) })
    }

    /** Saves the group's ladder; rungs are normalised like an alarm's own challenge. */
    suspend fun setEscalationPolicy(id: String, policy: EscalationPolicy) = reconciler.mutate("group_escalation") {
        val group = groups.get(id) ?: return@mutate
        groups.upsert(group.copy(escalationPolicy = EscalationPolicy(policy.steps.mapNotNull(AlarmRules::normalizeChallenge))))
    }

    /** Sets what every alarm in the group plays and how it gets louder; drops a replaced file. */
    suspend fun setGroupSound(id: String, sound: SoundConfig, rampSeconds: Int, startPercent: Int) {
        reconciler.mutate("group_sound") {
            val group = groups.get(id) ?: return@mutate
            groups.upsert(
                group.copy(
                    sound = sound,
                    rampSeconds = SoundSettings.clampRamp(rampSeconds),
                    rampStartPercent = SoundSettings.clampStart(startPercent),
                ),
            )
        }
        pruneSoundFiles()
    }

    /** The group's own theme, or null to follow the app theme (DESIGN.md §11). */
    suspend fun setGroupTheme(id: String, themeId: String?) = reconciler.mutate("group_theme") {
        groups.get(id)?.let { groups.upsert(it.copy(themeId = themeId)) }
    }

    /** Deletes copied audio files that no group plays any more (also ones picked but not saved). */
    suspend fun pruneSoundFiles() {
        val inUse = groups.all().mapNotNullTo(mutableSetOf()) { (it.sound as? SoundConfig.DeviceAudio)?.fileName }
        soundFiles.prune(inUse)
    }

    /** Moves every alarm in the group by [minutes] (the group screen's −5 / +5 buttons). */
    suspend fun shiftGroup(id: String, minutes: Long) = reconciler.mutate("group_shift") {
        alarms.upsertAll(alarms.inGroup(id).map { AlarmRules.shifted(it, minutes) })
    }

    suspend fun deleteGroup(id: String) {
        reconciler.mutate("group_delete") {
            // Explicit as well as the foreign-key cascade, so it cannot depend on PRAGMA foreign_keys.
            alarms.deleteInGroup(id)
            groups.delete(id)
        }
        pruneSoundFiles()
    }

    suspend fun moveGroup(id: String, delta: Int) = reconciler.mutate("group_move") {
        val ordered = visibleGroups()
        val moved = AlarmRules.move(ordered, ordered.indexOfFirst { it.id == id }, delta)
        groups.upsertAll(moved.mapIndexed { index, group -> group.copy(sortOrder = index) })
    }

    // --- Alarms ---

    /**
     * Creates or updates an alarm. Only user-editable fields are taken from [alarm]. A new time on
     * the group's first alarm moves the others by the same amount, unless the group opts out.
     */
    suspend fun saveAlarm(alarm: AlarmEntity) = reconciler.mutate("alarm_save") {
        val existing = alarms.get(alarm.id)
        val base = existing ?: alarm.copy(
            positionInGroup = alarms.inGroup(alarm.groupId).size,
            nextTriggerAt = null,
            oneTimeTriggerAt = null,
            lastFiredFor = null,
        )
        val saved = base.copy(
            hour = alarm.hour.coerceIn(0, 23),
            minute = alarm.minute.coerceIn(0, 59),
            daysOfWeek = alarm.daysOfWeek,
            label = AlarmRules.normalizeLabel(alarm.label),
            isEnabled = alarm.isEnabled,
            timeoutMinutes = AlarmRules.clampTimeout(alarm.timeoutMinutes),
            challengeOverride = AlarmRules.normalizeChallenge(alarm.challengeOverride),
            snoozeEnabled = alarm.snoozeEnabled,
            snoozeMinutes = Snooze.clampMinutes(alarm.snoozeMinutes),
            snoozeLimit = Snooze.clampLimit(alarm.snoozeLimit),
        )
        alarms.upsert(saved)
        if (existing != null && existing.positionInGroup == 0 && groups.get(saved.groupId)?.shiftWithFirst == true) {
            val minutes = AlarmRules.shiftMinutes(existing.time, saved.time)
            if (minutes != 0L) {
                alarms.upsertAll(alarms.inGroup(saved.groupId).filter { it.id != saved.id }.map { AlarmRules.shifted(it, minutes) })
            }
        }
    }

    /** Turning an alarm off also cancels a pending snooze, test ring or wake-up check for it. */
    suspend fun setAlarmEnabled(id: String, enabled: Boolean) = reconciler.mutate("alarm_enabled_$enabled") {
        alarms.get(id)?.let {
            alarms.upsert(
                if (enabled) it.copy(isEnabled = true) else it.copy(isEnabled = false, oneTimeTriggerAt = null, wakeCheckAt = null),
            )
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
