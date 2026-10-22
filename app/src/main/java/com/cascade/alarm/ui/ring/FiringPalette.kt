package com.cascade.alarm.ui.ring

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.cascade.alarm.ui.theme.CascadeColors
import com.cascade.alarm.ui.theme.sequenceProgress

/**
 * How a firing screen looks at a given point in its sequence (DESIGN.md §2.4, §6). The first
 * alarm is the theme's dark radial glow; the last is full-bleed ramp5 with onRamp5 text; those
 * between interpolate. Text is whichever of textPrimary and onRamp5 reads better on the result.
 */
data class FiringPalette(
    /** Radial gradient stops at 8%, 48% and the edge. */
    val gradient: List<Color>,
    val text: Color,
    val timeSize: TextUnit,
    val timeWeight: FontWeight,
) {
    companion object {
        val STOPS = listOf(0.08f, 0.48f, 1f)

        fun of(colors: CascadeColors, position: Int, total: Int): FiringPalette {
            val t = sequenceProgress(position, total)
            val last = colors.ramp.last()
            val gradient = colors.firing.map { lerp(it, last, t) }
            // The text sits over the inner part of the gradient; judge it on the worst of those stops.
            val text = listOf(colors.textPrimary, colors.onRamp5).maxBy { candidate ->
                gradient.take(2).minOf { contrast(candidate, it) }
            }
            // Light and large at the first alarm, heavier and tighter at the last (DESIGN.md §4).
            val weight = listOf(300, 400, 500, 600).minBy { kotlin.math.abs(it - (300 + 300 * t)) }
            return FiringPalette(gradient, text, lerp(92.sp, 68.sp, t), FontWeight(weight))
        }

        fun contrast(a: Color, b: Color): Float {
            val (la, lb) = a.luminance() to b.luminance()
            return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
        }
    }
}
