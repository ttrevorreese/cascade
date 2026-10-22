package com.cascade.alarm.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

/** Spacing scale and radii from DESIGN.md §5. */
object CascadeSpace {
    val gutter = 24.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 22.dp
    val xxl = 30.dp
}

object CascadeShapes {
    val small = RoundedCornerShape(10.dp)
    val medium = RoundedCornerShape(15.dp)
    val card = RoundedCornerShape(18.dp)
    val firing = RoundedCornerShape(22.dp)
}

/**
 * Provides the Cascade tokens and a Material scheme derived from them, so stock components
 * (text fields, dialogs, the time picker) sit in the theme too. Never dynamic colour and never
 * the system dark-mode setting (DESIGN.md §2.1, §10). Ramp colours are kept out of the Material
 * scheme on purpose: they only ever mean difficulty, so no component may pick them up by default.
 */
@Composable
fun CascadeTheme(theme: ThemeSpec = CascadeThemes.default, content: @Composable () -> Unit) {
    val c = theme.colors
    val scheme = if (c.isLight) {
        lightColorScheme(
            primary = c.textPrimary, onPrimary = c.ground, secondary = c.textSecondary, onSecondary = c.ground,
            background = c.ground, onBackground = c.textPrimary, surface = c.ground, onSurface = c.textPrimary,
            surfaceVariant = c.surface, onSurfaceVariant = c.textSecondary, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surfaceHigh, surfaceContainerHighest = c.surfaceHigh, surfaceContainerLow = c.surface,
            surfaceContainerLowest = c.groundDeep, outline = c.line, outlineVariant = c.line, error = c.status.fail,
            primaryContainer = c.surfaceHigh, onPrimaryContainer = c.textPrimary,
            secondaryContainer = c.surfaceHigh, onSecondaryContainer = c.textPrimary,
            tertiaryContainer = c.surfaceHigh, onTertiaryContainer = c.textPrimary,
        )
    } else {
        darkColorScheme(
            primary = c.textPrimary, onPrimary = c.ground, secondary = c.textSecondary, onSecondary = c.ground,
            background = c.ground, onBackground = c.textPrimary, surface = c.ground, onSurface = c.textPrimary,
            surfaceVariant = c.surface, onSurfaceVariant = c.textSecondary, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surfaceHigh, surfaceContainerHighest = c.surfaceHigh, surfaceContainerLow = c.surface,
            surfaceContainerLowest = c.groundDeep, outline = c.line, outlineVariant = c.line, error = c.status.fail,
            primaryContainer = c.surfaceHigh, onPrimaryContainer = c.textPrimary,
            secondaryContainer = c.surfaceHigh, onSecondaryContainer = c.textPrimary,
            tertiaryContainer = c.surfaceHigh, onTertiaryContainer = c.textPrimary,
        )
    }
    val typography = Typography(
        headlineSmall = CascadeType.screenTitle,
        titleLarge = CascadeType.screenTitle,
        titleMedium = CascadeType.cardTitle,
        titleSmall = CascadeType.rowLabel,
        bodyLarge = CascadeType.rowLabel,
        bodyMedium = CascadeType.body,
        bodySmall = CascadeType.caption,
        labelLarge = CascadeType.button,
        labelMedium = CascadeType.caption,
        labelSmall = CascadeType.caption,
    )
    val shapes = Shapes(small = CascadeShapes.small, medium = CascadeShapes.medium, large = CascadeShapes.card)
    CompositionLocalProvider(LocalCascadeColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}

/** The active theme's tokens. */
val cascadeColors: CascadeColors
    @Composable @ReadOnlyComposable get() = LocalCascadeColors.current
