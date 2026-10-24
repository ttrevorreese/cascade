package com.cascade.alarm.data.voice

import android.content.Context
import android.os.UserManager
import android.text.format.DateFormat
import com.cascade.alarm.data.db.AlarmDao
import com.cascade.alarm.data.db.time
import com.cascade.alarm.engine.EngineLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes each read-aloud alarm's speech ahead of time, whenever alarms or the voice change, so a
 * ring before the first unlock (when no speech engine runs) still says its label. Started from the
 * app's screens, which only open once the phone is unlocked.
 */
@Singleton
class SpeechWarmer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarms: AlarmDao,
    private val voice: VoicePreferences,
    private val cache: SpeechCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(alarms.observeAll(), voice.choice) { all, choice -> all to choice }
                .debounce(DEBOUNCE_MILLIS)
                .collectLatest { (all, choice) ->
                    if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return@collectLatest
                    val speaking = all.filter { it.readLabelAloud }
                    val is24Hour = DateFormat.is24HourFormat(context)
                    try {
                        cache.keepOnly(speaking.map { it.id }.toSet())
                        speaking.forEach { cache.prepare(it.id, ReadAloud.text(it.label, it.time, is24Hour), choice) }
                    } catch (e: Exception) {
                        EngineLog.e("cannot prepare spoken labels", e)
                    }
                }
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 2_000L
    }
}
