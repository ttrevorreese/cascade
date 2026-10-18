package com.cascade.alarm.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

enum class EventType { SCHEDULED, FIRED, DISMISSED, SNOOZED, MISSED, CHEAT_DETECTED }

/** Append-only audit log (SPEC §5). Never updated or deleted by the engine. */
@Entity(
    tableName = "alarm_events",
    indices = [Index("alarmId"), Index("timestamp")],
)
data class AlarmEventEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val alarmId: String,
    val type: EventType,
    val timestamp: Instant,
    /** Free-form JSON. */
    val metadata: String? = null,
)
