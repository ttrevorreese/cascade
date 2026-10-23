package com.cascade.alarm.ui.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Look-and-feel choices that never affect an alarm. Kept out of the alarm database on purpose:
 * nothing on the ring path reads them.
 */
@Singleton
class UiPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val mutableBackgroundMotion = MutableStateFlow(prefs.getBoolean(BACKGROUND_MOTION, true))

    /** The floating dots behind some screens. On by default (owner's request, 2026-10-23). */
    val backgroundMotion: StateFlow<Boolean> = mutableBackgroundMotion.asStateFlow()

    fun setBackgroundMotion(on: Boolean) {
        prefs.edit().putBoolean(BACKGROUND_MOTION, on).apply()
        mutableBackgroundMotion.value = on
    }

    private companion object {
        const val BACKGROUND_MOTION = "background_motion"
    }
}
