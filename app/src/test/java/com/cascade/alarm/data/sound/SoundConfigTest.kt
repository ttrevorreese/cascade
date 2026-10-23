package com.cascade.alarm.data.sound

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SoundConfigTest {
    @Test
    fun `the database default is the encoded default`() =
        assertEquals(SoundConfig.encode(SoundConfig.DEFAULT), SoundConfig.DEFAULT_JSON)

    @Test
    fun `configs survive a round trip`() {
        listOf(SoundConfig.BuiltIn("dawn"), SoundConfig.DeviceAudio("abc.mp3", "Morning song.mp3")).forEach {
            assertEquals(it, SoundConfig.decode(SoundConfig.encode(it)))
        }
    }

    @Test
    fun `an unreadable config plays the default`() {
        assertEquals(SoundConfig.DEFAULT, SoundConfig.decode("{}"))
        assertEquals(SoundConfig.DEFAULT, SoundConfig.decode("""{"type":"spotify","playlist":"x"}"""))
        assertEquals(SoundConfig.DEFAULT, SoundConfig.decode("not json"))
    }

    @Test
    fun `ramp and start are kept to their ranges`() {
        assertEquals(30, SoundSettings.clampRamp(0))
        assertEquals(60, SoundSettings.clampRamp(120))
        assertEquals(45, SoundSettings.clampRamp(45))
        assertEquals(5, SoundSettings.clampStart(0))
        assertEquals(60, SoundSettings.clampStart(100))
    }

    @Test
    fun `the default tone is one of the tones`() = assertEquals(Tones.DEFAULT, Tones.byKey((SoundConfig.DEFAULT as SoundConfig.BuiltIn).toneKey))
}
