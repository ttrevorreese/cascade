package com.cascade.alarm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms")
    suspend fun all(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun get(id: String): AlarmEntity?

    @Query("SELECT * FROM alarms WHERE id = :id")
    fun observe(id: String): Flow<AlarmEntity?>

    @Upsert
    suspend fun upsert(alarm: AlarmEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(alarm: AlarmEntity)
}

@Dao
interface ActiveRingDao {
    @Query("SELECT * FROM active_ring WHERE slot = 0")
    suspend fun get(): ActiveRingEntity?

    @Query("SELECT * FROM active_ring WHERE slot = 0")
    fun observe(): Flow<ActiveRingEntity?>

    @Upsert
    suspend fun set(ring: ActiveRingEntity)

    @Query("DELETE FROM active_ring")
    suspend fun clear()
}
