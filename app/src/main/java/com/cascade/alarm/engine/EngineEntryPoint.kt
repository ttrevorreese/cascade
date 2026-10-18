package com.cascade.alarm.engine

import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.data.db.ActiveRingDao
import com.cascade.alarm.engine.ring.RingState
import com.cascade.alarm.engine.schedule.Reconciler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Lets broadcast receivers reach the engine. Used instead of @AndroidEntryPoint on receivers,
 * which would need a super.onReceive call that Kotlin rejects on the abstract base method.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface EngineEntryPoint {
    fun reconciler(): Reconciler
    fun activeRingDao(): ActiveRingDao
    fun ringState(): RingState
    fun alarmRepository(): AlarmRepository
}
