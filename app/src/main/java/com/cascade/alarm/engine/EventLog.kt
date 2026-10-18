package com.cascade.alarm.engine

import com.cascade.alarm.data.db.AlarmEventDao
import com.cascade.alarm.data.db.AlarmEventEntity
import com.cascade.alarm.data.db.EventType
import org.json.JSONObject
import javax.inject.Inject

/** Writes the append-only audit log and mirrors each entry to logcat. */
class EventLog @Inject constructor(
    private val dao: AlarmEventDao,
    private val time: TimeSource,
) {
    suspend fun record(alarmId: String, type: EventType, vararg metadata: Pair<String, Any?>) {
        val json = metadata.takeIf { it.isNotEmpty() }
            ?.let { pairs -> JSONObject().apply { pairs.forEach { (k, v) -> put(k, v?.toString()) } }.toString() }
        EngineLog.i("event $type alarm=$alarmId ${json.orEmpty()}")
        dao.insert(AlarmEventEntity(alarmId = alarmId, type = type, timestamp = time.now(), metadata = json))
    }
}
