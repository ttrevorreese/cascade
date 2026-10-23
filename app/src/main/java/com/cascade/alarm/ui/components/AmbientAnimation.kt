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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.cascade.alarm.ui.theme.cascadeColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The background animations (DESIGN.md §6, "Background animation"). Each theme family has one as
 * its default ([forFamily]); the user can pick another or none. All of them draw in the theme's
 * secondary text colour at low alpha, never a ramp colour, and stand still when Android's
 * animations are off.
 */
enum class AmbientAnimation(val key: String, val label: String, val description: String) {
    DRIFT("drift", "Drift", "Dots rising slowly"),
    EMBERS("embers", "Embers", "Sparks that rise and flicker out"),
    STARS("stars", "Stars", "Still points that twinkle"),
    SNOW("snow", "Snow", "Soft flakes falling"),
    DUST("dust", "Dust", "A few motes wandering"),
    BUBBLES("bubbles", "Bubbles", "Rings floating up"),
    RAIN("rain", "Rain", "Thin streaks falling"),
    PULSE("pulse", "Pulse", "Rings that swell and fade"),
    ORBITS("orbits", "Orbits", "Dots circling slowly"),
    GLINTS("glints", "Glints", "Brief sparkles"),
    ;

    companion object {
        fun byKey(key: String): AmbientAnimation? = entries.firstOrNull { it.key == key }

        /**
         * Each family's own animation (decided 2026-10-23). Clarity, the accessibility family,
         * has none: motion is a distraction for the people it is for.
         */
        private val FAMILY_DEFAULTS = mapOf(
            "landscape" to DRIFT,
            "earth" to EMBERS,
            "afterDark" to STARS,
            "stark" to DUST,
            "daylight" to DUST,
            "pastel" to BUBBLES,
            "terminal" to RAIN,
            "neon" to PULSE,
            "quiet" to SNOW,
            "clarity" to null,
            "retro" to ORBITS,
            "royal" to GLINTS,
            "mineral" to DUST,
        )

        /** Families this table knows; a test checks it covers every family in themes.json. */
        val FAMILIES: Set<String> get() = FAMILY_DEFAULTS.keys

        fun forFamily(familyId: String): AmbientAnimation? = FAMILY_DEFAULTS[familyId] ?: if (familyId in FAMILY_DEFAULTS) null else DRIFT
    }
}

/**
 * The animation behind the current screen, or null for none. Provided by MainActivity from the
 * user's choice and theme; null by default so previews and tests stay still.
 */
val LocalAmbientAnimation = compositionLocalOf<AmbientAnimation?> { null }

/**
 * The screen's ground with the background animation behind [content]. Screens that use it give
 * their Scaffold a transparent container. Not for the group screen or the escalation editor,
 * whose spine and rail are the only ornament (DESIGN.md §10), nor the firing screen.
 */
@Composable
fun AmbientBackdrop(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(cascadeColors.ground)) {
        LocalAmbientAnimation.current?.let { AmbientCanvas(it, Modifier.fillMaxSize(), animate = !animationsOff()) }
        content()
    }
}

/** Android's "Remove animations": everything stays where it is (DESIGN.md §9). */
@Composable
fun animationsOff(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
}

/** Random but fixed per particle, so every screen shows the same arrangement. */
private class Particle(val x: Float, val y: Float, val size: Float, val speed: Float, val phase: Float, val alpha: Float, val period: Float)

/**
 * Draws [animation]. Time is read only while drawing, so it repaints without recomposing
 * anything, at most about 30 times a second.
 */
@Composable
fun AmbientCanvas(animation: AmbientAnimation, modifier: Modifier = Modifier, animate: Boolean = true) {
    val colors = cascadeColors
    // Faint grey vanishes faster on a light ground; lift it a little there.
    val ink = Ink(colors.textSecondary, if (colors.isLight) LIGHT_BOOST else 1f)
    val particles = remember(animation) {
        val random = Random(SEED + animation.ordinal)
        List(countFor(animation)) {
            Particle(
                x = random.nextFloat(), y = random.nextFloat(), size = random.nextFloat(), speed = random.nextFloat(),
                phase = random.nextFloat(), alpha = random.nextFloat(), period = random.nextFloat(),
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
    Canvas(modifier.testTag("ambient-${animation.key}").clearAndSetSemantics {}) {
        val t = seconds.floatValue
        when (animation) {
            AmbientAnimation.DRIFT -> drift(particles, t, ink)
            AmbientAnimation.EMBERS -> embers(particles, t, ink)
            AmbientAnimation.STARS -> stars(particles, t, ink)
            AmbientAnimation.SNOW -> snow(particles, t, ink)
            AmbientAnimation.DUST -> dust(particles, t, ink)
            AmbientAnimation.BUBBLES -> bubbles(particles, t, ink)
            AmbientAnimation.RAIN -> rain(particles, t, ink)
            AmbientAnimation.PULSE -> pulse(particles, t, ink)
            AmbientAnimation.ORBITS -> orbits(particles, t, ink)
            AmbientAnimation.GLINTS -> glints(particles, t, ink)
        }
    }
}

private fun countFor(animation: AmbientAnimation) = when (animation) {
    AmbientAnimation.DRIFT -> 22
    AmbientAnimation.EMBERS -> 18
    AmbientAnimation.STARS -> 30
    AmbientAnimation.SNOW -> 26
    AmbientAnimation.DUST -> 14
    AmbientAnimation.BUBBLES -> 12
    AmbientAnimation.RAIN -> 18
    AmbientAnimation.PULSE -> 10
    AmbientAnimation.ORBITS -> 7
    AmbientAnimation.GLINTS -> 16
}

/** [a] + share·([b] − [a]). */
private fun lerp(a: Float, b: Float, share: Float) = a + (b - a) * share

private fun wave(t: Float, periodSeconds: Float, phase: Float) = sin(2 * PI * (t / periodSeconds + phase)).toFloat()

/** Position along a vertical loop: 0 at the bottom edge (plus margin) to 1 past the top. */
private fun DrawScope.travelled(p: Particle, t: Float, dpPerSecond: Float): Float {
    val travel = size.height + MARGIN.toPx() * 2
    return ((p.y * travel + dpPerSecond.dp.toPx() * t) % travel) / travel
}

private fun DrawScope.risingY(share: Float) = size.height + MARGIN.toPx() - share * (size.height + MARGIN.toPx() * 2)

private fun DrawScope.fallingY(share: Float) = -MARGIN.toPx() + share * (size.height + MARGIN.toPx() * 2)

private fun DrawScope.drift(particles: List<Particle>, t: Float, ink: Ink) = particles.forEach { p ->
    val y = risingY(travelled(p, t, lerp(3f, 9f, p.speed)))
    val x = p.x * size.width + wave(t, lerp(8f, 18f, p.period), p.phase) * lerp(4f, 14f, p.size).dp.toPx()
    drawCircle(ink.at(lerp(0.08f, 0.22f, p.alpha)), lerp(1.5f, 3.5f, p.size).dp.toPx(), Offset(x, y))
}

private fun DrawScope.embers(particles: List<Particle>, t: Float, ink: Ink) = particles.forEach { p ->
    val share = travelled(p, t, lerp(10f, 22f, p.speed))
    val x = p.x * size.width + wave(t, lerp(3f, 6f, p.period), p.phase) * lerp(3f, 8f, p.size).dp.toPx()
    val flicker = 0.6f + 0.4f * wave(t, lerp(0.6f, 1.4f, p.period), p.phase)
    // They burn out as they climb: full strength in the lower half, gone at the top.
    val burn = min(1f, max(0f, (1f - share) / 0.5f))
    drawCircle(ink.at(lerp(0.16f, 0.34f, p.alpha) * flicker * burn), lerp(1.2f, 2.8f, p.size).dp.toPx(), Offset(x, risingY(share)))
}

private fun DrawScope.stars(particles: List<Particle>, t: Float, ink: Ink) = particles.forEach { p ->
    val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * wave(t, lerp(3f, 7f, p.period), p.phase))
    drawCircle(ink.at(lerp(0.14f, 0.32f, p.alpha) * twinkle), lerp(1f, 2.2f, p.size).dp.toPx(), Offset(p.x * size.width, p.y * size.height))
}

private fun DrawScope.snow(particles: List<Particle>, t: Float, ink: Ink) = particles.forEach { p ->
    val y = fallingY(travelled(p, t, lerp(6f, 14f, p.speed)))
    val x = p.x * size.width + wave(t, lerp(6f, 12f, p.period), p.phase) * lerp(6f, 16f, p.size).dp.toPx()
    drawCircle(ink.at(lerp(0.08f, 0.20f, p.alpha)), lerp(1.5f, 3.5f, p.size).dp.toPx(), Offset(x, y))
}

private fun DrawScope.dust(particles: List<Particle>, t: Float, ink: Ink) = particles.forEach { p ->
    // Two slow waves on each axis at unrelated periods: a wander that never visibly repeats.
    val reach = lerp(12f, 30f, p.size).dp.toPx()
    val x = p.x * size.width + (wave(t, lerp(17f, 29f, p.period), p.phase) + 0.5f * wave(t, 11f, p.phase * 3f)) * reach
    val y = p.y * size.height + (wave(t, lerp(19f, 31f, p.speed), p.phase + 0.25f) + 0.5f * wave(t, 13f, p.phase * 5f)) * reach
    drawCircle(ink.at(lerp(0.12f, 0.26f, p.alpha)), lerp(1.3f, 2.5f, p.size).dp.toPx(), Offset(x, y))
}

private fun DrawScope.bubbles(particles: List<Particle>, t: Float, ink: Ink) = particles.forEach { p ->
    val y = risingY(travelled(p, t, lerp(8f, 16f, p.speed)))
    val x = p.x * size.width + wave(t, lerp(5f, 9f, p.period), p.phase) * lerp(4f, 10f, p.size).dp.toPx()
    drawCircle(ink.at(lerp(0.10f, 0.20f, p.alpha)), lerp(4f, 10f, p.size).dp.toPx(), Offset(x, y), style = Stroke(1.dp.toPx()))
}

private fun DrawScope.rain(particles: List<Particle>, t: Float, ink: Ink) = particles.forEach { p ->
    val length = lerp(10f, 24f, p.size).dp.toPx()
    val y = fallingY(travelled(p, t, lerp(25f, 50f, p.speed)))
    val x = p.x * size.width
    drawLine(ink.at(lerp(0.06f, 0.16f, p.alpha)), Offset(x, y - length), Offset(x, y), 1.dp.toPx())
}

private fun DrawScope.pulse(particles: List<Particle>, t: Float, ink: Ink) = particles.forEach { p ->
    val progress = ((t / lerp(4f, 7f, p.period) + p.phase) % 1f)
    val radius = lerp(2f, 14f, progress).dp.toPx()
    val alpha = lerp(0.10f, 0.24f, p.alpha) * (1f - progress)
    drawCircle(ink.at(alpha), radius, Offset(p.x * size.width, p.y * size.height), style = Stroke(1.dp.toPx()))
}

private fun DrawScope.orbits(particles: List<Particle>, t: Float, ink: Ink) = particles.forEach { p ->
    val centre = Offset(p.x * size.width, p.y * size.height)
    val rx = lerp(14f, 32f, p.size).dp.toPx()
    val ry = rx * lerp(0.3f, 0.6f, p.speed)
    val tilt = p.phase * PI.toFloat()
    // The orbit itself, very faint, then the dot on it.
    val ring = ink.at(lerp(0.04f, 0.08f, p.alpha))
    rotateEllipse(centre, rx, ry, tilt) { topLeft, ellipse -> drawOval(ring, topLeft, ellipse, style = Stroke(0.75.dp.toPx())) }
    val angle = 2 * PI * (t / lerp(10f, 20f, p.period) + p.phase)
    val ex = (cos(angle) * rx).toFloat()
    val ey = (sin(angle) * ry).toFloat()
    val dot = Offset(centre.x + ex * cos(tilt) - ey * sin(tilt), centre.y + ex * sin(tilt) + ey * cos(tilt))
    drawCircle(ink.at(lerp(0.14f, 0.26f, p.alpha)), 2.dp.toPx(), dot)
}

private inline fun DrawScope.rotateEllipse(centre: Offset, rx: Float, ry: Float, angle: Float, draw: DrawScope.(Offset, Size) -> Unit) {
    drawContext.transform.rotate(Math.toDegrees(angle.toDouble()).toFloat(), centre)
    draw(Offset(centre.x - rx, centre.y - ry), Size(rx * 2, ry * 2))
    drawContext.transform.rotate(-Math.toDegrees(angle.toDouble()).toFloat(), centre)
}

private fun DrawScope.glints(particles: List<Particle>, t: Float, ink: Ink) = particles.forEach { p ->
    // Dark most of the time, then a short sparkle: a sine raised to a high power.
    val shine = max(0f, wave(t, lerp(4f, 9f, p.period), p.phase)).pow(4)
    if (shine < 0.02f) return@forEach
    val centre = Offset(p.x * size.width, p.y * size.height)
    val arm = lerp(4f, 10f, p.size).dp.toPx() * shine
    val c = ink.at(lerp(0.22f, 0.40f, p.alpha) * shine)
    val stroke = 1.dp.toPx()
    drawLine(c, Offset(centre.x - arm, centre.y), Offset(centre.x + arm, centre.y), stroke)
    drawLine(c, Offset(centre.x, centre.y - arm), Offset(centre.x, centre.y + arm), stroke)
    drawCircle(c, 1.dp.toPx(), centre)
}

/** The one colour every animation draws in, and how much to lift its alpha on light grounds. */
private class Ink(private val color: Color, private val boost: Float) {
    fun at(alpha: Float) = color.copy(alpha = (alpha * boost).coerceAtMost(1f))
}

private const val LIGHT_BOOST = 1.35f

private val MARGIN = 26.dp
private const val SEED = 7
private const val FRAME_NANOS = 33_000_000L

/** The clock restarts hourly; the animation jumps once when it does, which nobody watches for an hour. */
private const val WRAP_SECONDS = 3600.0
