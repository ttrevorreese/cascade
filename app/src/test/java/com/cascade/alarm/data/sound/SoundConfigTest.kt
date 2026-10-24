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
    fun `a Spotify choice keeps its backup through a round trip`() {
        val spotify = SoundConfig.SpotifyPlaylist("spotify:playlist:x", "Wake up", SoundConfig.BuiltIn("dawn"))
        assertEquals(spotify, SoundConfig.decode(SoundConfig.encode(spotify)))
    }

    @Test
    fun `the local sound of a Spotify choice is its backup, never another Spotify choice`() {
        val dawn = SoundConfig.BuiltIn("dawn")
        assertEquals(dawn, SoundConfig.SpotifyPlaylist("spotify:album:x", "A", dawn).local)
        val nested = SoundConfig.SpotifyPlaylist("spotify:album:x", "A", SoundConfig.SpotifyPlaylist("spotify:album:y", "B", dawn))
        assertEquals(SoundConfig.DEFAULT, nested.local)
        assertEquals(dawn, dawn.local)
    }

    @Test
    fun `changing the local sound keeps the Spotify playlist`() {
        val spotify = SoundConfig.SpotifyPlaylist("spotify:playlist:x", "Wake up", SoundConfig.BuiltIn("dawn"))
        val file = SoundConfig.DeviceAudio("abc.mp3", "Song.mp3")
        assertEquals(spotify.copy(fallback = file), spotify.withLocal(file))
        assertEquals(file, SoundConfig.BuiltIn("dawn").withLocal(file))
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
