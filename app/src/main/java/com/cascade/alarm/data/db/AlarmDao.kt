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

    @Query("SELECT * FROM alarms")
    fun observeAll(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun get(id: String): AlarmEntity?

    @Query("SELECT * FROM alarms WHERE id = :id")
    fun observe(id: String): Flow<AlarmEntity?>

    @Query("SELECT * FROM alarms WHERE groupId = :groupId ORDER BY positionInGroup")
    suspend fun inGroup(groupId: String): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE groupId = :groupId ORDER BY positionInGroup")
    fun observeInGroup(groupId: String): Flow<List<AlarmEntity>>

    @Upsert
    suspend fun upsert(alarm: AlarmEntity)

    @Upsert
    suspend fun upsertAll(alarms: List<AlarmEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(alarm: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM alarms WHERE groupId = :groupId")
    suspend fun deleteInGroup(groupId: String)
}

@Dao
interface AlarmGroupDao {
    @Query("SELECT * FROM alarm_groups ORDER BY sortOrder")
    suspend fun all(): List<AlarmGroupEntity>

    @Query("SELECT * FROM alarm_groups ORDER BY sortOrder")
    fun observeAll(): Flow<List<AlarmGroupEntity>>

    @Query("SELECT * FROM alarm_groups WHERE id = :id")
    suspend fun get(id: String): AlarmGroupEntity?

    @Query("SELECT * FROM alarm_groups WHERE id = :id")
    fun observe(id: String): Flow<AlarmGroupEntity?>

    @Upsert
    suspend fun upsert(group: AlarmGroupEntity)

    @Upsert
    suspend fun upsertAll(groups: List<AlarmGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(group: AlarmGroupEntity)

    @Query("DELETE FROM alarm_groups WHERE id = :id")
    suspend fun delete(id: String)
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
