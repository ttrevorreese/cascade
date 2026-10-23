package com.cascade.alarm.engine.ring

import androidx.annotation.RawRes
import com.cascade.alarm.data.sound.SoundConfig
import com.cascade.alarm.data.sound.Tones
import java.io.File

/** Something the player can try to play. */
sealed interface SoundSource {
    data class Bundled(@RawRes val res: Int) : SoundSource
    data class LocalFile(val file: File) : SoundSource
}

/**
 * The order to try sounds in for a ring: the group's choice, then Classic. The player's last
 * resort after these is the phone's own alarm sound, and vibration runs throughout.
 *
 * [skipped] says why the group's choice is not in the list at all (it can still fail later, while
 * playing, which the player reports itself).
 */
data class SoundPlan(val sources: List<SoundSource>, val skipped: String? = null) {
    companion object {
        private val CLASSIC = SoundSource.Bundled(Tones.DEFAULT.res)

        /** [fileFor] finds a copied audio file; it must not throw. */
        fun of(config: SoundConfig, fileFor: (String) -> File?): SoundPlan = when (config) {
            is SoundConfig.BuiltIn -> {
                val tone = Tones.byKey(config.toneKey)
                if (tone == null) SoundPlan(listOf(CLASSIC), skipped = "unknown_tone")
                else SoundPlan(listOf(SoundSource.Bundled(tone.res), CLASSIC).distinct())
            }
            is SoundConfig.DeviceAudio -> {
                val file = fileFor(config.fileName)
                if (file == null) SoundPlan(listOf(CLASSIC), skipped = "file_missing")
                else SoundPlan(listOf(SoundSource.LocalFile(file), CLASSIC))
            }
        }
    }
}
