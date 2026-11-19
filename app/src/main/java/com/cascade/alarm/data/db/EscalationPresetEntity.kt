package com.cascade.alarm.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.cascade.alarm.data.challenge.EscalationPolicy
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * A ladder saved by name, to load into any group's escalation editor (owner request, 2026-11-18).
 * Loading copies it: a group never points at a preset, so editing or deleting one changes no group.
 */
@Entity(tableName = "escalation_presets")
data class EscalationPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val policy: EscalationPolicy,
    val createdAt: Instant,
)

@Dao
interface EscalationPresetDao {
    @Query("SELECT * FROM escalation_presets ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<EscalationPresetEntity>>

    @Query("SELECT * FROM escalation_presets ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<EscalationPresetEntity>

    @Upsert
    suspend fun upsert(preset: EscalationPresetEntity)

    @Query("DELETE FROM escalation_presets WHERE id = :id")
    suspend fun delete(id: String)
}
