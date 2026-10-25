package com.cascade.alarm.data.sound

/**
 * How a group's alarms vibrate (owner request, 2026-10-25: off entirely, or a pattern other than
 * the default). Stored on the group by key; an unknown key reads as [STEADY], never as silence.
 */
enum class VibrationPattern(
    val key: String,
    val title: String,
    val description: String,
    /** Alternating off/on durations in ms, starting with off; repeated while the alarm rings. */
    val timings: LongArray,
    /** Share of the chosen strength for each segment (0 = still). */
    val levels: FloatArray,
) {
    OFF("off", "Off", "No vibration", longArrayOf(), floatArrayOf()),
    STEADY("steady", "Steady", "A long buzz, then a pause", longArrayOf(0, 800, 600), floatArrayOf(0f, 1f, 0f)),
    PULSE(
        "pulse", "Pulse", "Three quick taps, then a pause",
        longArrayOf(0, 120, 100, 120, 100, 120, 700), floatArrayOf(0f, 1f, 0f, 1f, 0f, 1f, 0f),
    ),
    HEARTBEAT("heartbeat", "Heartbeat", "Two beats, then a rest", longArrayOf(0, 100, 120, 180, 1000), floatArrayOf(0f, 0.7f, 0f, 1f, 0f)),
    WAVE("wave", "Wave", "Swells up and fades away", waveTimings(), waveLevels()),
    SLOW("slow", "Slow", "A short buzz every three seconds", longArrayOf(0, 400, 2600), floatArrayOf(0f, 1f, 0f)),
    ;

    companion object {
        val DEFAULT = STEADY

        fun byKey(key: String?): VibrationPattern = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

private const val WAVE_STEPS = 10
private const val WAVE_STEP_MILLIS = 100L
private const val WAVE_REST_MILLIS = 600L

/** Up in ten steps, down in ten, then a rest. */
private fun waveTimings() = LongArray(2 * WAVE_STEPS + 1) { if (it == 2 * WAVE_STEPS) WAVE_REST_MILLIS else WAVE_STEP_MILLIS }

private fun waveLevels() = FloatArray(2 * WAVE_STEPS + 1) { i ->
    when {
        i < WAVE_STEPS -> (i + 1f) / WAVE_STEPS
        i < 2 * WAVE_STEPS -> (2 * WAVE_STEPS - i).toFloat() / WAVE_STEPS
        else -> 0f
    }
}

/** A group's vibration as the ring path uses it. */
data class VibrationSetting(
    val pattern: VibrationPattern = VibrationPattern.DEFAULT,
    val strengthPercent: Int = DEFAULT_STRENGTH,
    /** Seconds the sound plays alone before the vibration joins it. */
    val delaySeconds: Int = 0,
) {
    val isOff get() = pattern == VibrationPattern.OFF

    /** Timings and amplitudes (1–255, 0 for still) for VibrationEffect.createWaveform. */
    fun waveform(): Pair<LongArray, IntArray> {
        val top = MAX_AMPLITUDE * strengthPercent.coerceIn(MIN_STRENGTH, MAX_STRENGTH) / 100f
        val amplitudes = IntArray(pattern.levels.size) { i ->
            val level = pattern.levels[i]
            if (level <= 0f) 0 else (top * level).toInt().coerceIn(1, MAX_AMPLITUDE)
        }
        return pattern.timings to amplitudes
    }

    companion object {
        /** When the sound cannot play at all, the alarm vibrates whatever the group says (fail open). */
        val LAST_RESORT = VibrationSetting(VibrationPattern.STEADY, MAX_STRENGTH, 0)

        const val MIN_STRENGTH = 30
        const val MAX_STRENGTH = 100
        const val DEFAULT_STRENGTH = 100
        private const val MAX_AMPLITUDE = 255

        /** Light, medium, strong. */
        val STRENGTH_CHOICES = listOf(30, 60, 100)
        val DELAY_CHOICES = listOf(0, 30, 60, 120)

        fun of(patternKey: String?, strengthPercent: Int, delaySeconds: Int) = VibrationSetting(
            VibrationPattern.byKey(patternKey),
            strengthPercent.coerceIn(MIN_STRENGTH, MAX_STRENGTH),
            delaySeconds.coerceIn(0, DELAY_CHOICES.last()),
        )

        fun strengthName(percent: Int) = when {
            percent <= 30 -> "Light"
            percent <= 60 -> "Medium"
            else -> "Strong"
        }

        fun delayName(seconds: Int) = when (seconds) {
            0 -> "Right away"
            60 -> "After 1 min"
            120 -> "After 2 min"
            else -> "After $seconds s"
        }
    }
}
