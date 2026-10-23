package com.cascade.alarm.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThemeResolutionTest {
    private val freeOnly = { theme: ThemeSpec -> theme.isFree }
    private val everything = { _: ThemeSpec -> true }

    @Test
    fun `a group's own theme wins when it is owned`() =
        assertEquals("citrus", ThemeResolution.resolve("citrus", "night", everything).id)

    @Test
    fun `no group theme means the app theme`() =
        assertEquals("night", ThemeResolution.resolve(null, "night", freeOnly).id)

    @Test
    fun `an unowned group theme falls back to the app theme silently`() =
        assertEquals("night", ThemeResolution.resolve("citrus", "night", freeOnly).id)

    @Test
    fun `an unknown id falls back the same way`() =
        assertEquals("night", ThemeResolution.resolve("from-a-backup-typo", "night", everything).id)

    @Test
    fun `an unowned app theme falls back to Forest`() {
        assertEquals(CascadeThemes.default.id, ThemeResolution.resolve(null, "citrus", freeOnly).id)
        assertEquals(CascadeThemes.default.id, ThemeResolution.resolve(null, null, freeOnly).id)
    }

    @Test
    fun `an ownership check that fails counts only free themes`() {
        val broken = { _: ThemeSpec -> error("billing unavailable") }
        assertEquals("night", ThemeResolution.resolve("citrus", "night", broken).id)
        assertEquals("beacon", ThemeResolution.resolve("beacon", "night", broken).id)
    }

    @Test
    fun `a group theme is reported locked only when it is set, known and unowned`() {
        assertTrue(ThemeResolution.groupThemeLocked("citrus", freeOnly))
        assertFalse(ThemeResolution.groupThemeLocked("citrus", everything))
        assertFalse(ThemeResolution.groupThemeLocked("night", freeOnly))
        assertFalse(ThemeResolution.groupThemeLocked(null, freeOnly))
        assertFalse(ThemeResolution.groupThemeLocked("from-a-backup-typo", freeOnly))
    }

    @Test
    fun `the free themes are Forest, Night and the four Clarity themes`() =
        assertEquals(setOf("forest", "night", "beacon", "lantern", "hazard", "compass"), CascadeThemes.all.filter { it.isFree }.map { it.id }.toSet())
}
