package com.cascade.alarm.di

import com.cascade.alarm.BuildConfig
import com.cascade.alarm.data.billing.PlayPlusStore
import com.cascade.alarm.data.billing.PlusStore
import com.cascade.alarm.data.billing.TestPlusStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider

@Module
@InstallIn(SingletonComponent::class)
object BillingModule {
    /** Debug builds unlock Plus without Play, so it can be tried on a phone before it is sold. */
    @Provides
    fun plusStore(play: Provider<PlayPlusStore>, test: Provider<TestPlusStore>): PlusStore =
        if (BuildConfig.DEBUG) test.get() else play.get()
}
