package com.cascade.alarm.data.reliability

import android.content.Context
import com.cascade.alarm.data.SystemTestAlarm
import com.cascade.alarm.data.db.AlarmEventDao
import com.cascade.alarm.data.db.AlarmEventEntity
import com.cascade.alarm.engine.TimeSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** The missed-alarm detector and the test-alarm result, over the event log (SPEC §4.3). */
@Singleton
class ReliabilityRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val events: AlarmEventDao,
    private val time: TimeSource,
) {
    // Device-protected like the database, so it is readable whenever the database is.
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("reliability", Context.MODE_PRIVATE)

    suspend fun incidents(): List<Incident> {
        val now = time.now()
        return ReliabilityRules.incidents(events.since(now.minus(ReliabilityRules.WINDOW)), time.bootTime(), now, acknowledged())
    }

    /** "Got it": the card for this incident does not come back. */
    suspend fun acknowledge(eventId: String) {
        // Acknowledgements only matter while their event is inside the window, so keep just those.
        val now = time.now()
        val inWindow = ReliabilityRules.incidents(events.since(now.minus(ReliabilityRules.WINDOW)), time.bootTime(), now, emptySet())
            .map { it.eventId }.toSet()
        prefs.edit().putStringSet(ACKNOWLEDGED, (acknowledged() intersect inWindow) + eventId).apply()
    }

    suspend fun lastTest(): TestResult? = ReliabilityRules.lastTest(events.forAlarm(SystemTestAlarm.ALARM_ID))

    /** The recent event log as plain text, for sharing while testing on a device. */
    suspend fun eventLogText(): String {
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
        return events.recent(LOG_LINES).reversed().joinToString("\n") { line(it, format) }
    }

    private fun line(e: AlarmEventEntity, format: DateTimeFormatter) =
        "${format.format(e.timestamp)}  ${e.type}  ${e.alarmId.take(8)}  ${e.metadata.orEmpty()}"

    private fun acknowledged(): Set<String> = prefs.getStringSet(ACKNOWLEDGED, emptySet()).orEmpty()

    private companion object {
        const val ACKNOWLEDGED = "acknowledged"
        const val LOG_LINES = 500
    }
}
