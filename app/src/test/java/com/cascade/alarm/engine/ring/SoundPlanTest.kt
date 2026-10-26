package com.cascade.alarm.engine.ring

import com.cascade.alarm.R
import com.cascade.alarm.data.sound.SoundConfig
import com.cascade.alarm.data.sound.Tones
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random

class SoundPlanTest {
    private val classic = SoundSource.Bundled(R.raw.tone_classic)
    private val copied = File("sounds/abc.mp3")

    @Test
    fun `a tone plays, with Classic behind it`() {
        val plan = SoundPlan.of(SoundConfig.BuiltIn("klaxon")) { null }
        assertEquals(listOf(SoundSource.Bundled(R.raw.tone_klaxon), classic), plan.sources)
        assertNull(plan.skipped)
    }

    @Test
    fun `Classic is not tried twice`() =
        assertEquals(listOf(classic), SoundPlan.of(SoundConfig.BuiltIn("classic")) { null }.sources)

    @Test
    fun `an unknown tone falls back to Classic and says so`() {
        val plan = SoundPlan.of(SoundConfig.BuiltIn("from-a-newer-version")) { null }
        assertEquals(listOf(classic), plan.sources)
        assertEquals("unknown_tone", plan.skipped)
    }

    @Test
    fun `a copied file plays, with Classic behind it`() {
        val plan = SoundPlan.of(SoundConfig.DeviceAudio("abc.mp3", "Song.mp3")) { name -> copied.takeIf { name == "abc.mp3" } }
        assertEquals(listOf(SoundSource.LocalFile(copied), classic), plan.sources)
        assertNull(plan.skipped)
    }

    @Test
    fun `a missing file falls back to Classic and says so`() {
        val plan = SoundPlan.of(SoundConfig.DeviceAudio("gone.mp3", "Song.mp3")) { null }
        assertEquals(listOf(classic), plan.sources)
        assertEquals("file_missing", plan.skipped)
    }

    @Test
    fun `a Spotify group rings its backup on the phone`() {
        val plan = SoundPlan.of(SoundConfig.SpotifyPlaylist("spotify:playlist:x", "Wake up", SoundConfig.BuiltIn("klaxon"))) { null }
        assertEquals(listOf(SoundSource.Bundled(R.raw.tone_klaxon), classic), plan.sources)
        assertNull(plan.skipped)
    }

    @Test
    fun `random plays one of the chosen tones, with Classic behind it`() {
        val chosen = SoundConfig.RandomTone(listOf("chimes", "klaxon", "digital"))
        val played = (0 until 200).map { seed -> SoundPlan.of(chosen, Random(seed)) { null }.sources }.toSet()
        assertEquals(
            setOf(R.raw.tone_chimes, R.raw.tone_klaxon, R.raw.tone_digital).map { listOf(SoundSource.Bundled(it), classic) }.toSet(),
            played,
        )
    }

    @Test
    fun `random skips tones this version does not know, and with none left plays Classic`() {
        val plan = SoundPlan.of(SoundConfig.RandomTone(listOf("from-a-newer-version", "klaxon")), Random(1)) { null }
        assertEquals(listOf(SoundSource.Bundled(R.raw.tone_klaxon), classic), plan.sources)
        val none = SoundPlan.of(SoundConfig.RandomTone(listOf("from-a-newer-version")), Random(1)) { null }
        assertEquals(listOf(classic), none.sources)
        assertEquals("unknown_tone", none.skipped)
    }

    @Test
    fun `random survives storage and can be Spotify's backup`() {
        val random = SoundConfig.RandomTone(listOf("dawn", "klaxon"))
        assertEquals(random, SoundConfig.decode(SoundConfig.encode(random)))
        assertEquals(random, SoundConfig.SpotifyPlaylist("spotify:playlist:x", "Wake up", random).local)
    }

    @Test
    fun `every built-in tone has a distinct key and sound`() {
        assertEquals(Tones.ALL.size, Tones.ALL.map { it.key }.toSet().size)
        assertEquals(Tones.ALL.size, Tones.ALL.map { it.res }.toSet().size)
    }
}
