package com.cascade.alarm.ui.components

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.cascade.alarm.ui.theme.cascadeColors
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Whether the floating dots are on. Provided by MainActivity from [com.cascade.alarm.ui.settings.UiPreferences];
 * off by default so previews and tests stay still.
 */
val LocalBackgroundMotion = compositionLocalOf { false }

/**
 * The screen's ground with the floating dots behind [content], when they are on. Screens that use
 * it give their Scaffold a transparent container. Not for the group screen or the escalation
 * editor, whose spine and rail are the only ornament (DESIGN.md §10), nor the firing screen.
 */
@Composable
fun AmbientBackdrop(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(cascadeColors.ground)) {
        if (LocalBackgroundMotion.current) AmbientDots(Modifier.fillMaxSize(), animate = !animationsOff())
        content()
    }
}

/** Android's "Remove animations": the dots stay where they are (DESIGN.md §9). */
@Composable
private fun animationsOff(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
}

private class Dot(
    /** Horizontal position as a share of the width. */
    val x: Float,
    /** Where it starts, as a share of the height it travels. */
    val start: Float,
    val radiusDp: Float,
    val riseDpPerSecond: Float,
    val swayDp: Float,
    val swaySeconds: Float,
    val phase: Float,
    val alpha: Float,
)

/**
 * Small, faint dots drifting slowly upward with a little sideways sway. Drawn in the theme's
 * secondary text colour at low alpha, never a ramp colour (ramp colours only mean difficulty).
 * Time is read only while drawing, so the dots repaint without recomposing anything, at most
 * about 30 times a second.
 */
@Composable
fun AmbientDots(modifier: Modifier = Modifier, animate: Boolean = true) {
    val color = cascadeColors.textSecondary
    val dots = remember {
        val random = Random(SEED)
        List(COUNT) {
            Dot(
                x = random.nextFloat(),
                start = random.nextFloat(),
                radiusDp = 1.5f + random.nextFloat() * 2f,
                riseDpPerSecond = 3f + random.nextFloat() * 6f,
                swayDp = 4f + random.nextFloat() * 10f,
                swaySeconds = 8f + random.nextFloat() * 10f,
                phase = random.nextFloat(),
                alpha = 0.08f + random.nextFloat() * 0.14f,
            )
        }
    }
    val seconds = remember { mutableFloatStateOf(0f) }
    if (animate) {
        LaunchedEffect(Unit) {
            val origin = withFrameNanos { it }
            var drawn = origin
            while (true) {
                withFrameNanos { now ->
                    if (now - drawn >= FRAME_NANOS) {
                        drawn = now
                        // Wrapped so the float keeps its precision however long the screen stays open.
                        seconds.floatValue = ((now - origin) / 1e9 % WRAP_SECONDS).toFloat()
                    }
                }
            }
        }
    }
    Canvas(modifier.testTag("ambient-dots").clearAndSetSemantics {}) {
        val t = seconds.floatValue
        val margin = 8.dp.toPx()
        val travel = size.height + margin * 2
        for (dot in dots) {
            val risen = (dot.start * travel + dot.riseDpPerSecond.dp.toPx() * t) % travel
            val y = size.height + margin - risen
            val x = dot.x * size.width + sin(2 * PI * (t / dot.swaySeconds + dot.phase)).toFloat() * dot.swayDp.dp.toPx()
            drawCircle(color.copy(alpha = dot.alpha), dot.radiusDp.dp.toPx(), Offset(x, y))
        }
    }
}

private const val COUNT = 22
private const val SEED = 7
private const val FRAME_NANOS = 33_000_000L

/** The clock restarts hourly; the dots jump once when it does, which nobody watches for an hour. */
private const val WRAP_SECONDS = 3600.0
