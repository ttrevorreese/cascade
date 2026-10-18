package com.cascade.alarm.di

import android.content.Context
import com.cascade.alarm.data.db.AlarmEventDao
import com.cascade.alarm.data.db.CascadeDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): CascadeDatabase = CascadeDatabase.create(context)

    @Provides
    fun alarmEventDao(db: CascadeDatabase): AlarmEventDao = db.alarmEventDao()
}
