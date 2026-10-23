package com.cascade.alarm.data.sound

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What an alarm plays (SPEC §5). Set per group, so every alarm in a sequence sounds the same
 * (decided 2026-10-23). Stored as JSON in `alarm_groups.sound`. Spotify joins in M7, with a
 * required local fallback (SPEC §9).
 */
@Serializable
sealed interface SoundConfig {
    /** One of [Tones]; an unknown key plays [Tones.DEFAULT]. */
    @Serializable @SerialName("builtin")
    data class BuiltIn(val toneKey: String) : SoundConfig

    /**
     * A file the user picked, copied into the app's device-protected storage as [fileName] so it
     * plays before the first unlock after a reboot ([SoundFiles]). [displayName] is the original name.
     */
    @Serializable @SerialName("file")
    data class DeviceAudio(val fileName: String, val displayName: String) : SoundConfig

    companion object {
        val DEFAULT: SoundConfig = BuiltIn(Tones.DEFAULT.key)

        /** Room's column default; must stay equal to `encode(DEFAULT)`. */
        const val DEFAULT_JSON = """{"type":"builtin","toneKey":"classic"}"""

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(config: SoundConfig): String = json.encodeToString(serializer(), config)

        /** Never throws: a config this version cannot read plays [DEFAULT]. */
        fun decode(text: String): SoundConfig =
            runCatching { json.decodeFromString(serializer(), text) }.getOrDefault(DEFAULT)
    }
}

/** A group's sound settings: what plays and how it gets louder (SPEC §4.2). */
object SoundSettings {
    /** How long the ramp takes to reach full volume; the owner asked for 30 s to 1 min. */
    const val MIN_RAMP_SECONDS = 30
    const val MAX_RAMP_SECONDS = 60
    const val DEFAULT_RAMP_SECONDS = 45
    const val RAMP_STEP_SECONDS = 5

    /** Where the ramp starts, as perceived loudness in percent of full ([com.cascade.alarm.engine.ring.VolumeRamp.startGain]). */
    const val MIN_START_PERCENT = 5
    const val MAX_START_PERCENT = 60
    const val DEFAULT_START_PERCENT = 15
    const val START_STEP_PERCENT = 5

    fun clampRamp(seconds: Int): Int = seconds.coerceIn(MIN_RAMP_SECONDS, MAX_RAMP_SECONDS)

    fun clampStart(percent: Int): Int = percent.coerceIn(MIN_START_PERCENT, MAX_START_PERCENT)
}
