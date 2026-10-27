package com.cascade.alarm.engine

import com.cascade.alarm.engine.bedtime.BedtimeReminder
import com.cascade.alarm.widget.NextAlarmWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What follows the schedule: the bedtime reminder and the widget. Runs on its own scope, after
 * the change is saved, so a failure or a slow launcher can never hold up or break a ring.
 */
@Singleton
class ScheduleSideEffects @Inject constructor(
    private val bedtime: BedtimeReminder,
    private val widget: NextAlarmWidgetUpdater,
) : ScheduleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A burst of changes (a save reconciles once per edit) is handled once.
    private val changes = Channel<Unit>(Channel.CONFLATED).also { channel ->
        scope.launch {
            for (change in channel) {
                try { bedtime.update() } catch (e: Exception) { EngineLog.e("bedtime update failed", e) }
                try { widget.update() } catch (e: Exception) { EngineLog.e("widget update failed", e) }
            }
        }
    }

    override fun scheduleChanged() {
        changes.trySend(Unit)
    }

    /** Settings changed what the reminder does, without a schedule change. */
    fun refresh() = scheduleChanged()
}
