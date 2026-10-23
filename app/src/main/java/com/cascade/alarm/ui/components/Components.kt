package com.cascade.alarm.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors
import com.cascade.alarm.ui.theme.rampStopFor

/** Armed is ramp1 and disarmed is line; the knob is textPrimary armed, textSecondary disarmed (DESIGN.md §6). */
@Composable
fun CascadeSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = cascadeColors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedTrackColor = c.ramp[0],
            checkedThumbColor = c.textPrimary,
            checkedBorderColor = c.ramp[0],
            uncheckedTrackColor = c.line,
            uncheckedThumbColor = c.textSecondary,
            uncheckedBorderColor = c.line,
        ),
    )
}

/** The primary action: flat, textPrimary on ground. Never a ramp colour (DESIGN.md §1). */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = cascadeColors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 52.dp),
        shape = CascadeShapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = c.textPrimary, contentColor = c.ground),
        elevation = null,
    ) { Text(text, style = CascadeType.button) }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    val c = cascadeColors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        shape = if (small) CascadeShapes.small else CascadeShapes.medium,
        border = BorderStroke(1.dp, c.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.textPrimary),
        contentPadding = contentPadding,
    ) { Text(text, style = CascadeType.button) }
}

/** A 48dp icon button; [description] is required because every icon-only button needs one (§9). */
@Composable
fun CascadeIconButton(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = cascadeColors
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = c.textSecondary, disabledContentColor = c.textQuiet.copy(alpha = 0.5f)),
    ) { Icon(icon, contentDescription = description) }
}

/** Flat top bar over the screen's ground (and its floating dots, where a screen has them), sentence-case title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CascadeTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val c = cascadeColors
    TopAppBar(
        title = { Text(title, style = CascadeType.screenTitle, color = c.textPrimary) },
        navigationIcon = { if (onBack != null) CascadeIconButton(CascadeIcons.Back, "Back", onBack) },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
    )
}

/**
 * The group card's miniature of its difficulty ramp: one dot per alarm joined by gradient
 * segments (DESIGN.md §6). Decorative; the card states the step count in words beside it.
 */
@Composable
fun RampPreview(total: Int, modifier: Modifier = Modifier, dot: Dp = 7.dp, segment: Dp = 14.dp) {
    if (total <= 0) return
    val c = cascadeColors
    val colors = (0 until total).map { c.ramp(rampStopFor(it, total)) }
    Canvas(modifier.width(dot * total + segment * (total - 1)).height(dot)) {
        val r = dot.toPx() / 2
        val step = (dot + segment).toPx()
        for (i in 0 until total - 1) {
            val start = Offset(r + i * step, r)
            val end = Offset(r + (i + 1) * step, r)
            drawLine(Brush.linearGradient(listOf(colors[i], colors[i + 1]), start, end), start, end, strokeWidth = 2.dp.toPx())
        }
        colors.forEachIndexed { i, color -> drawCircle(color, r, Offset(r + i * step, r)) }
    }
}

/**
 * Where this alarm sits in its sequence, so a half-asleep user knows whether more are coming.
 * The current alarm is larger and earlier ones are filled, so position reads without colour.
 */
@Composable
fun SequenceDots(position: Int, total: Int, onColor: Color, modifier: Modifier = Modifier) {
    if (total <= 0) return
    val c = cascadeColors
    Row(
        modifier.semantics { contentDescription = "Alarm ${position + 1} of $total" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until total) {
            val size = if (i == position) 14.dp else 9.dp
            val ramp = c.ramp(rampStopFor(i, total))
            Canvas(Modifier.size(size)) {
                when {
                    i <= position -> {
                        drawCircle(ramp)
                        drawCircle(onColor, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                    }
                    else -> drawCircle(onColor.copy(alpha = 0.55f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                }
            }
        }
    }
}

/** Line icons: 24 grid, 2dp stroke, rounded caps and joins (DESIGN.md §8). */
object CascadeIcons {
    private fun line(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()

    val Back = line("back") { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }
    val ChevronUp = line("up") { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) }
    val ChevronDown = line("down") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
    val Add = line("add") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }

    /** Two sliders, each with a round knob: settings. */
    val Settings = line("settings") {
        moveTo(4f, 8f); lineTo(6f, 8f); moveTo(11f, 8f); lineTo(20f, 8f)
        moveTo(11f, 8f); arcToRelative(2.5f, 2.5f, 0f, true, true, -5f, 0f); arcToRelative(2.5f, 2.5f, 0f, true, true, 5f, 0f)
        moveTo(4f, 16f); lineTo(13f, 16f); moveTo(18f, 16f); lineTo(20f, 16f)
        moveTo(18f, 16f); arcToRelative(2.5f, 2.5f, 0f, true, true, -5f, 0f); arcToRelative(2.5f, 2.5f, 0f, true, true, 5f, 0f)
    }
}
