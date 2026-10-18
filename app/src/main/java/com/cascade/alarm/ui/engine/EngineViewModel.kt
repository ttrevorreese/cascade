package com.cascade.alarm.ui.engine

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cascade.alarm.data.HardcodedAlarm
import com.cascade.alarm.data.db.ActiveRingDao
import com.cascade.alarm.data.db.AlarmDao
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmEventDao
import com.cascade.alarm.data.db.AlarmEventEntity
import com.cascade.alarm.engine.TimeSource
import com.cascade.alarm.engine.ring.AlarmRingService
import com.cascade.alarm.engine.ring.RingState
import com.cascade.alarm.engine.schedule.Reconciler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

data class EngineUiState(
    val alarm: AlarmEntity? = null,
    val ringing: Boolean = false,
    val events: List<AlarmEventEntity> = emptyList(),
)

@HiltViewModel
class EngineViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reconciler: Reconciler,
    private val alarmDao: AlarmDao,
    private val activeRingDao: ActiveRingDao,
    private val ringState: RingState,
    private val time: TimeSource,
    eventDao: AlarmEventDao,
) : ViewModel() {

    val state: StateFlow<EngineUiState> = combine(
        alarmDao.observe(HardcodedAlarm.ID),
        activeRingDao.observe(),
        eventDao.observeRecent(EVENT_LIMIT),
    ) { alarm, ring, events -> EngineUiState(alarm, ring != null, events) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EngineUiState())

    init {
        viewModelScope.launch {
            alarmDao.insertIfAbsent(HardcodedAlarm.DEFAULT)
            // Opening the app re-arms everything, which also recovers from a force-stop.
            reconciler.reconcileAll("app_start")
            // SPEC §6.7: a ring that never completed resumes when the app is opened.
            if (activeRingDao.get() != null && ringState.current.value == null) {
                ContextCompat.startForegroundService(context, AlarmRingService.resumeIntent(context, afterBoot = false))
            }
        }
    }

    fun setTime(hour: Int, minute: Int) = edit("edit_time") { it.copy(hour = hour, minute = minute) }

    fun setRepeatDaily(daily: Boolean) =
        edit("edit_repeat") { it.copy(daysOfWeek = if (daily) DayOfWeek.entries.toSet() else emptySet()) }

    fun setEnabled(enabled: Boolean) = edit("edit_enabled") { it.copy(isEnabled = enabled) }

    fun ringInOneMinute() = edit("test_ring") { it.copy(oneTimeTriggerAt = time.now().plusSeconds(60)) }

    private fun edit(reason: String, transform: (AlarmEntity) -> AlarmEntity) {
        viewModelScope.launch { reconciler.edit(HardcodedAlarm.ID, reason, transform) }
    }

    private companion object {
        const val EVENT_LIMIT = 30
    }
}
