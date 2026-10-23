package com.cascade.alarm.di

import com.cascade.alarm.data.billing.PlayPlusStore
import com.cascade.alarm.data.billing.PlusStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BillingModule {
    @Binds
    abstract fun plusStore(store: PlayPlusStore): PlusStore
}
