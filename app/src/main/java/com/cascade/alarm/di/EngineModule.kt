package com.cascade.alarm.di

import android.app.AlarmManager
import android.content.Context
import com.cascade.alarm.engine.ScheduleObserver
import com.cascade.alarm.engine.ScheduleSideEffects
import com.cascade.alarm.engine.SystemTimeSource
import com.cascade.alarm.engine.TimeSource
import com.cascade.alarm.engine.ring.RingStore
import com.cascade.alarm.engine.ring.RoomRingStore
import com.cascade.alarm.engine.schedule.AlarmScheduler
import com.cascade.alarm.engine.schedule.AndroidAlarmScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {
    @Binds
    abstract fun timeSource(impl: SystemTimeSource): TimeSource

    @Binds
    abstract fun alarmScheduler(impl: AndroidAlarmScheduler): AlarmScheduler

    @Binds
    abstract fun ringStore(impl: RoomRingStore): RingStore

    @Binds
    abstract fun scheduleObserver(impl: ScheduleSideEffects): ScheduleObserver

    companion object {
        @Provides
        fun alarmManager(@ApplicationContext context: Context): AlarmManager =
            context.getSystemService(AlarmManager::class.java)
    }
}
