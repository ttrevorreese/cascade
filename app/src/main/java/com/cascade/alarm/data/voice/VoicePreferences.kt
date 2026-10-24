package com.cascade.alarm.data.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The read-aloud voice. Device-protected, because a ring before the first unlock reads it too. */
@Singleton
class VoicePreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val choice: StateFlow<VoiceChoice> = mutable.asStateFlow()

    fun set(choice: VoiceChoice) {
        prefs.edit()
            .putString(ENGINE, choice.engine)
            .putString(VOICE, choice.voiceName)
            .putFloat(RATE, choice.rate)
            .putString(LABEL, choice.label)
            .apply()
        mutable.value = choice
    }

    private fun read() = VoiceChoice(
        engine = prefs.getString(ENGINE, null),
        voiceName = prefs.getString(VOICE, null),
        rate = prefs.getFloat(RATE, 1f),
        label = prefs.getString(LABEL, null),
    )

    private companion object {
        const val NAME = "voice"
        const val ENGINE = "engine"
        const val VOICE = "voice"
        const val RATE = "rate"
        const val LABEL = "label"
    }
}
