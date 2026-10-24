package com.cascade.alarm.data.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.util.Locale

class ReadAloudTest {
    private val sixTen = LocalTime.of(6, 10)

    @Test
    fun `says the time then the label, in the phone's clock style`() {
        assertEquals("It's 6:10 AM. Take your pills.", ReadAloud.text("Take your pills", sixTen, is24Hour = false))
        assertEquals("It's 18:05. Gym.", ReadAloud.text("Gym", LocalTime.of(18, 5), is24Hour = true))
    }

    @Test
    fun `keeps the label's own ending and says only the time without one`() {
        assertEquals("It's 6:10 AM. Up!", ReadAloud.text("  Up!  ", sixTen, is24Hour = false))
        assertEquals("It's 6:10 AM.", ReadAloud.text(null, sixTen, is24Hour = false))
        assertEquals("It's 6:10 AM.", ReadAloud.text("   ", sixTen, is24Hour = false))
    }

    private fun voice(name: String, engine: String = "g", locale: Locale = Locale.US, quality: Int = 400, network: Boolean = false, missing: Boolean = false) =
        VoiceOption(engine, if (engine == "g") "Google" else "Samsung", name, locale, quality, network, missing)

    @Test
    fun `only installed offline voices in the phone's language are offered`() {
        val all = listOf(
            voice("net", network = true),
            voice("missing", missing = true),
            voice("french", locale = Locale.FRANCE),
            voice("ok"),
            voice("ok"),
        )
        assertEquals(listOf("ok"), ReadAloud.usable(all, "en").map { it.name })
    }

    @Test
    fun `voices are numbered within each engine, best quality first`() {
        val usable = ReadAloud.usable(
            listOf(voice("b", quality = 400), voice("a", quality = 500), voice("s1", engine = "s")),
            "en",
        )
        val named = ReadAloud.named(usable, Locale.US)
        assertEquals(listOf("a", "b", "s1"), named.map { it.option.name })
        assertEquals(listOf("Voice 1", "Voice 2", "Voice 1"), named.map { it.title })
        assertEquals("English (United States) · High quality", named[0].detail)
    }

    @Test
    fun `the cache key changes with the voice and speed but not the label shown`() {
        val choice = VoiceChoice("g", "a", 1f, label = "Voice 1")
        assertEquals(choice.key, choice.copy(label = "renamed").key)
        assertNotEquals(choice.key, choice.copy(rate = 1.2f).key)
        assertNotEquals(choice.key, choice.copy(voiceName = "b").key)
    }
}
