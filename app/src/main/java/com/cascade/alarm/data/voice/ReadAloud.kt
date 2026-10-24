package com.cascade.alarm.data.voice

import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Which voice reads alarm labels aloud, app-wide (owner request, 2026-10-24). Null [engine] or
 * [voiceName] means the phone's default; [label] is what Settings shows for it.
 */
data class VoiceChoice(
    val engine: String? = null,
    val voiceName: String? = null,
    val rate: Float = 1f,
    val label: String? = null,
) {
    /** Changes whenever the spoken audio would; the speech cache is keyed on it. */
    val key: String get() = "${engine.orEmpty()}|${voiceName.orEmpty()}|$rate"
}

/** A voice one of the phone's text-to-speech engines offers. */
data class VoiceOption(
    val engine: String,
    val engineLabel: String,
    val name: String,
    val locale: Locale,
    /** [android.speech.tts.Voice.getQuality]: 400 normal, 500 high, 300 low. */
    val quality: Int,
    val needsNetwork: Boolean,
    val notInstalled: Boolean,
)

/** A usable voice with the name the picker shows. */
data class NamedVoice(val option: VoiceOption, val title: String, val detail: String)

object ReadAloud {
    /** The tone plays alone this long first, so the label is not the first thing heard. */
    val FIRST_AFTER: Duration = Duration.ofSeconds(4)

    /** Pause between readings, counted from the end of one to the start of the next. */
    val EVERY: Duration = Duration.ofSeconds(30)

    /** Share of the tone's volume kept while the label is being read. */
    const val TONE_GAIN = 0.25f

    val RATES = listOf(0.8f to "Slower", 1f to "Normal", 1.2f to "Faster")

    /** What the picker reads when you try a voice. */
    const val SAMPLE_LABEL = "Time to get up."

    /** "It's 6:10 AM. Take your pills." The label alone is said as written, with a full stop. */
    fun text(label: String?, time: LocalTime, is24Hour: Boolean): String {
        val clock = time.format(DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", Locale.US))
        val said = label?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.last() in ".!?") it else "$it." }
        return listOfNotNull("It's $clock.", said).joinToString(" ")
    }

    /**
     * The voices an alarm can rely on: installed, working offline (an alarm cannot wait on the
     * network) and in [language]. Ordered by engine, then region, best quality first.
     */
    fun usable(all: List<VoiceOption>, language: String): List<VoiceOption> =
        all.filter { !it.needsNetwork && !it.notInstalled && it.locale.language == language }
            .distinctBy { it.engine to it.name }
            .sortedWith(compareBy<VoiceOption>({ it.engineLabel }, { it.locale.displayCountry }, { -it.quality }, { it.name }))

    /**
     * Engines give voices code names ("en-us-x-iol-local"), so the picker numbers them within each
     * engine and says the region and quality; the user chooses by listening.
     */
    fun named(voices: List<VoiceOption>, displayLocale: Locale = Locale.getDefault()): List<NamedVoice> =
        voices.groupBy { it.engine }.values.flatMap { inEngine ->
            inEngine.mapIndexed { i, voice ->
                val region = voice.locale.getDisplayName(displayLocale)
                val quality = if (voice.quality >= HIGH_QUALITY) " · High quality" else ""
                NamedVoice(voice, "Voice ${i + 1}", "$region$quality")
            }
        }

    private const val HIGH_QUALITY = 500
}
