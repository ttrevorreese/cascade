package com.cascade.alarm.ui.appearance

import androidx.compose.ui.semantics.heading
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.cascade.alarm.ui.components.CascadeIcons
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeThemes
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.ThemeFamily
import com.cascade.alarm.ui.theme.ThemeSpec
import com.cascade.alarm.ui.theme.cascadeColors

/**
 * Families with an idea worth a sentence before choosing (DESIGN.md §2: "the picker should say so
 * in a line rather than letting someone discover it at 6:35am").
 */
internal fun familyLine(familyId: String): String? = when (familyId) {
    "quiet" -> "These end pale: the last alarm gets louder and harder, not brighter."
    "terminal" -> "One colour each. Later alarms are brighter, not a different colour."
    "neon" -> "The loudest colours in the set."
    "clarity" -> "The easiest to read: the strongest contrast, and all four are free."
    else -> null
}

/** The chip that shows every family at once, each under its own heading (owner request, 2026-10-25). */
val ALL_FAMILIES = ThemeFamily("all", "All", emptyList())

/**
 * The themes under the chips: one family, or with [ALL_FAMILIES] chosen every family in [families]
 * in turn, each under its name with a line between them. An item per family keeps the list lazy.
 */
fun LazyListScope.themeFamilies(
    chosen: ThemeFamily,
    families: List<ThemeFamily>,
    selectedId: String?,
    owns: (ThemeSpec) -> Boolean,
    onPick: (ThemeSpec) -> Unit,
) {
    if (chosen.id != ALL_FAMILIES.id) {
        item { FamilyGrid(chosen, selectedId, owns, onPick) }
        return
    }
    itemsIndexed(families, key = { _, it -> "family-section-${it.id}" }) { index, family ->
        Column(Modifier.testTag("family-section-${family.id}")) {
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = CascadeSpace.gutter, vertical = CascadeSpace.l), color = cascadeColors.line)
            Text(
                family.label,
                style = CascadeType.rowLabel,
                color = cascadeColors.textPrimary,
                modifier = Modifier.padding(horizontal = CascadeSpace.gutter).padding(bottom = CascadeSpace.s).semantics { heading() },
            )
            FamilyGrid(family, selectedId, owns, onPick)
        }
    }
}

/** The family chips: thirteen families are what make fifty-two themes navigable (DESIGN.md §12). */
@Composable
fun FamilyChips(
    selected: ThemeFamily,
    onSelect: (ThemeFamily) -> Unit,
    modifier: Modifier = Modifier,
    families: List<ThemeFamily> = CascadeThemes.families,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = CascadeSpace.gutter),
        horizontalArrangement = Arrangement.spacedBy(CascadeSpace.s),
    ) {
        families.forEach { family ->
            FilterChip(
                selected = family.id == selected.id,
                onClick = { onSelect(family) },
                label = { Text(family.label) },
                shape = CascadeShapes.small,
                modifier = Modifier.testTag("family-${family.id}"),
            )
        }
    }
}

/**
 * One family's themes, two to a row, each drawn in its own colours (DESIGN.md §12). Generated from
 * the families list: a new theme in themes.json appears here with no UI change.
 */
@Composable
fun FamilyGrid(family: ThemeFamily, selectedId: String?, owns: (ThemeSpec) -> Boolean, onPick: (ThemeSpec) -> Unit) {
    Column(Modifier.padding(horizontal = CascadeSpace.gutter), verticalArrangement = Arrangement.spacedBy(CascadeSpace.m)) {
        familyLine(family.id)?.let { Text(it, style = CascadeType.body, color = cascadeColors.textSecondary) }
        family.themeIds.map(CascadeThemes::byId).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(CascadeSpace.m)) {
                row.forEach { theme ->
                    ThemePreview(theme, selected = theme.id == selectedId, locked = !owns(theme), onClick = { onPick(theme) }, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * A theme drawn in itself: its ground, a raised surface with a time in its primary text, and its
 * ramp. Locked themes are shown in full with a padlock; seeing what you would get is the sell.
 */
@Composable
fun ThemePreview(theme: ThemeSpec, selected: Boolean, locked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val app = cascadeColors
    val t = theme.colors
    Column(
        modifier
            .testTag("theme-${theme.id}")
            .semantics {
                this.selected = selected
                contentDescription = listOfNotNull(theme.name, theme.descriptor, "locked".takeIf { locked }).joinToString(", ")
            },
    ) {
        Surface(
            onClick = onClick,
            shape = CascadeShapes.medium,
            color = t.ground,
            // The outline is in the page's own text colour: a ramp colour here would mean difficulty.
            border = if (selected) BorderStroke(2.dp, app.textPrimary) else BorderStroke(1.dp, app.line),
            modifier = Modifier.fillMaxWidth().height(112.dp),
        ) {
            Box(Modifier.padding(10.dp)) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.surface).padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text("6:30", style = CascadeType.cardTitle, color = t.textPrimary)
                    Text("Workday", style = CascadeType.caption, color = t.textSecondary)
                }
                RampStrip(theme, Modifier.align(Alignment.BottomStart).padding(start = 2.dp, bottom = 2.dp))
                if (locked) {
                    Icon(
                        CascadeIcons.Lock,
                        contentDescription = null,
                        tint = t.textSecondary,
                        modifier = Modifier.align(Alignment.BottomEnd).size(18.dp),
                    )
                }
            }
        }
        Text(theme.name, style = CascadeType.rowLabel, color = app.textPrimary, modifier = Modifier.padding(top = CascadeSpace.xs))
        Text(theme.descriptor, style = CascadeType.caption, color = app.textSecondary)
    }
}

/** The theme's five ramp stops joined by gradient segments, as on a group card. */
@Composable
private fun RampStrip(theme: ThemeSpec, modifier: Modifier = Modifier) {
    val ramp = theme.colors.ramp
    Canvas(modifier.width(92.dp).height(8.dp)) {
        val r = size.height / 2
        val step = (size.width - r * 2) / (ramp.size - 1)
        for (i in 0 until ramp.size - 1) {
            val a = Offset(r + i * step, r)
            val b = Offset(r + (i + 1) * step, r)
            drawLine(Brush.horizontalGradient(listOf(ramp[i], ramp[i + 1]), a.x, b.x), a, b, 2.dp.toPx())
        }
        ramp.forEachIndexed { i, color -> drawCircle(color, r, Offset(r + i * step, r)) }
    }
}
