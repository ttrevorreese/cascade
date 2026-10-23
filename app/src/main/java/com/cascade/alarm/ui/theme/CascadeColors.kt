package com.cascade.alarm.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * One theme's tokens (DESIGN.md §2.1). Values come from design/assets/themes.json via the
 * generated [GeneratedThemes]; screens use these roles and never a raw colour.
 *
 * Deliberately not Material's ColorScheme: the five-stop [ramp] has no equivalent there.
 */
@Immutable
data class CascadeColors(
    /** Light themes invert the surface direction: groundDeep and surface are lighter than ground. */
    val isLight: Boolean,
    /**
     * Difficulty climbs by getting darker instead of brighter (DESIGN.md §2.2a: Lantern, Marble).
     * Only the ramp-order check cares; the firing screen works the same either way.
     */
    val rampDescends: Boolean = false,
    val ground: Color,
    val groundDeep: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceMuted: Color,
    val line: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textQuiet: Color,
    /** Difficulty by position in the sequence, first to last. Only ever means difficulty. */
    val ramp: List<Color>,
    /** Text on top of ramp5. */
    val onRamp5: Color,
    /** The first alarm's firing-screen radial gradient, centre to edge. */
    val firing: List<Color>,
    val status: StatusColors,
) {
    fun ramp(stop: RampStop): Color = ramp[stop.ordinal]
}

@Immutable
data class StatusColors(val pass: Color, val warn: Color, val fail: Color)

data class ThemeSpec(
    val id: String,
    val name: String,
    val descriptor: String,
    /** Free themes; the rest are the Cascade Plus pack (DESIGN.md §11). */
    val isFree: Boolean,
    val note: String?,
    val colors: CascadeColors,
)

data class ThemeFamily(val id: String, val label: String, val themeIds: List<String>)

object CascadeThemes {
    val all: List<ThemeSpec> = GeneratedThemes.all
    val families: List<ThemeFamily> = GeneratedThemes.families
    val default: ThemeSpec = byId(GeneratedThemes.DEFAULT)

    fun byId(id: String): ThemeSpec = all.firstOrNull { it.id == id } ?: all.first { it.id == GeneratedThemes.DEFAULT }
}

val LocalCascadeColors = staticCompositionLocalOf { CascadeThemes.default.colors }
