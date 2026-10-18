package com.cascade.alarm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmEventDao {
    @Insert
    suspend fun insert(event: AlarmEventEntity)

    @Query("SELECT * FROM alarm_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AlarmEventEntity>>

    @Query("SELECT * FROM alarm_events WHERE alarmId = :alarmId ORDER BY timestamp ASC")
    suspend fun forAlarm(alarmId: String): List<AlarmEventEntity>
}
