package com.cascade.alarm.debug

import com.cascade.alarm.data.billing.Entitlements
import com.cascade.alarm.ui.settings.UiPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Look-and-feel settings for [DebugCommandReceiver]; kept apart from the engine's entry point. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugUiEntryPoint {
    fun uiPreferences(): UiPreferences
    fun entitlements(): Entitlements
}
