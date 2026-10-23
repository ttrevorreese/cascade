package com.cascade.alarm.ui.ring

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cascade.alarm.ui.theme.CascadeThemes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FiringPaletteTest {
    private val forest = CascadeThemes.default.colors

    @Test
    fun `the first alarm is the theme's dark glow with light text`() {
        val p = FiringPalette.of(forest, 0, 4)
        assertEquals(forest.firing, p.gradient)
        assertEquals(forest.textPrimary, p.text)
        assertEquals(92.sp, p.timeSize)
        assertEquals(FontWeight.Light, p.timeWeight)
    }

    @Test
    fun `the last alarm, and a single alarm, are full-bleed ramp5 with dark text`() {
        for ((position, total) in listOf(3 to 4, 0 to 1)) {
            val p = FiringPalette.of(forest, position, total)
            assertTrue(p.gradient.all { it == forest.ramp.last() })
            assertEquals(forest.onRamp5, p.text)
            assertEquals(68.sp, p.timeSize)
            assertEquals(FontWeight.SemiBold, p.timeWeight)
        }
    }

    @Test
    fun `everything on the firing screen stays readable at every step of every theme`() {
        // The ask line and label are body-size text, so the 4.5:1 floor applies (DESIGN.md §9).
        val failures = CascadeThemes.all.flatMap { theme ->
            (1..6).flatMap { total ->
                (0 until total).mapNotNull { position ->
                    val p = FiringPalette.of(theme.colors, position, total)
                    val worst = p.gradient.take(2).minOf { FiringPalette.contrast(p.text, it) }
                    if (worst < 4.5f) "${theme.id} ${position + 1}/$total: ${"%.2f".format(worst)}" else null
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString(" | "))
    }
}
