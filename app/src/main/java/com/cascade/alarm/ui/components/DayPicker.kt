package com.cascade.alarm.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

private val WEEKDAYS = DayOfWeek.entries.take(5).toSet()
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
private val EVERY_DAY = DayOfWeek.entries.toSet()

/**
 * The week as seven letters in the locale's order (S M T W T F S in the US), each tapped on or
 * off, with Weekdays, Weekends and Every day below (owner request, 2026-11-18). No day = rings once.
 * Selected days are filled in the text colour: ramp colours only ever mean difficulty.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayPicker(days: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit, locale: Locale, modifier: Modifier = Modifier, tag: String = "day") {
    val c = cascadeColors
    val first = WeekFields.of(locale).firstDayOfWeek
    val week = (0L until 7L).map { first.plus(it) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            week.forEach { day ->
                val on = day in days
                Surface(
                    shape = CircleShape,
                    color = if (on) c.textPrimary else c.surface,
                    border = if (on) null else BorderStroke(1.5.dp, c.line),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(min = 40.dp, max = 48.dp)
                        .aspectRatio(1f)
                        .toggleable(value = on, role = Role.Checkbox) { onChange(if (on) days - day else days + day) }
                        .semantics { contentDescription = day.getDisplayName(TextStyle.FULL, locale) }
                        .testTag("$tag-$day"),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            day.getDisplayName(TextStyle.NARROW, locale),
                            style = CascadeType.rowLabel,
                            color = if (on) c.ground else c.textSecondary,
                        )
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CascadeSpace.s), verticalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
            val compact = PaddingValues(horizontal = 14.dp)
            QuickDays("Weekdays", WEEKDAYS, days, onChange, compact, "$tag-weekdays")
            QuickDays("Weekends", WEEKEND, days, onChange, compact, "$tag-weekends")
            QuickDays("Every day", EVERY_DAY, days, onChange, compact, "$tag-every-day")
            if (days.isNotEmpty()) SecondaryButton("Once", { onChange(emptySet()) }, Modifier.testTag("$tag-once"), small = true, contentPadding = compact)
        }
    }
}

/** Sets exactly [preset]; tapping it again when it is already the selection clears it. */
@Composable
private fun QuickDays(
    label: String,
    preset: Set<DayOfWeek>,
    days: Set<DayOfWeek>,
    onChange: (Set<DayOfWeek>) -> Unit,
    padding: PaddingValues,
    tag: String,
) {
    SecondaryButton(label, { onChange(if (days == preset) emptySet() else preset) }, Modifier.testTag(tag), small = true, contentPadding = padding)
}
