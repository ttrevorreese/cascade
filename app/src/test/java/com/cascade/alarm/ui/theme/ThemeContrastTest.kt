package com.cascade.alarm.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The checks DESIGN.md §2.3 says were verified and must be re-run whenever a colour changes.
 * They run against the generated table, so editing themes.json re-checks them automatically.
 */
class ThemeContrastTest {
    private fun channel(c: Float) = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val (la, lb) = luminance(a) to luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun `there are twenty themes in five families of four, Forest default, Forest and Night free`() {
        assertEquals(20, CascadeThemes.all.size)
        assertEquals(5, CascadeThemes.families.size)
        CascadeThemes.families.forEach { assertEquals(4, it.themeIds.size, it.label) }
        assertEquals(CascadeThemes.all.map { it.id }.toSet(), CascadeThemes.families.flatMap { it.themeIds }.toSet())
        assertEquals("forest", CascadeThemes.default.id)
        assertEquals(setOf("forest", "night"), CascadeThemes.all.filter { it.isFree }.map { it.id }.toSet())
        assertEquals(setOf("paper", "sand", "chalk", "blush"), CascadeThemes.all.filter { it.colors.isLight }.map { it.id }.toSet())
    }

    @Test
    fun `body text reaches 4_5 to 1 and quiet text 3 to 1 against the ground`() {
        CascadeThemes.all.forEach { t ->
            val c = t.colors
            assertTrue(contrast(c.textPrimary, c.ground) >= 4.5, "${t.id} textPrimary")
            assertTrue(contrast(c.textSecondary, c.ground) >= 4.5, "${t.id} textSecondary")
            assertTrue(contrast(c.textQuiet, c.ground) >= 3.0, "${t.id} textQuiet")
        }
    }

    @Test
    fun `text on ramp5 reaches 4_5 to 1`() {
        CascadeThemes.all.forEach { t -> assertTrue(contrast(t.colors.onRamp5, t.colors.ramp.last()) >= 4.5, t.id) }
    }

    @Test
    fun `the ramp climbs in luminance at every stop, not just in hue`() {
        CascadeThemes.all.forEach { t ->
            val l = t.colors.ramp.map(::luminance)
            l.zipWithNext().forEachIndexed { i, (a, b) -> assertTrue(b > a, "${t.id} ramp${i + 1} -> ramp${i + 2}") }
        }
    }

    @Test
    fun `light themes use the darker status colours`() {
        CascadeThemes.all.forEach { t ->
            val expected = if (t.colors.isLight) GeneratedThemes.statusLight else GeneratedThemes.status
            assertEquals(expected, t.colors.status, t.id)
        }
    }
}
