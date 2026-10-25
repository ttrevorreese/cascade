package com.cascade.alarm.ui.sound

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.cascade.alarm.data.sound.VibrationSetting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Vibrates a pattern for a few seconds the way an alarm would, so it can be felt before saving. */
class VibrationPreview @Inject constructor(@ApplicationContext context: Context) {
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()
    private var stopJob: Job? = null

    /** False on phones whose motor is only on or off: strength then makes no difference. */
    val canChangeStrength: Boolean get() = vibrator?.hasAmplitudeControl() == true

    fun play(setting: VibrationSetting, scope: CoroutineScope) {
        stop()
        val motor = vibrator ?: return
        if (setting.isOff) return
        val (timings, amplitudes) = setting.waveform()
        val effect = VibrationEffect.createWaveform(timings, amplitudes, 0)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                motor.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                motor.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        }
        stopJob = scope.launch {
            delay(PREVIEW_MILLIS)
            stop()
        }
    }

    fun stop() {
        stopJob?.cancel()
        stopJob = null
        runCatching { vibrator?.cancel() }
    }

    private companion object {
        const val PREVIEW_MILLIS = 4_000L
    }
}
