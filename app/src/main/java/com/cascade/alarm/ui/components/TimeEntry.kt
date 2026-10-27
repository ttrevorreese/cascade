package com.cascade.alarm.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors
import java.time.LocalTime

/** Typing a time (owner request, 2026-10-26): "630", "6:30", "6.30 pm", "18:30" all work. */
object TimeEntryRules {
    val STEPS = listOf(-10, -5, -1, 1, 5, 10)

    /**
     * The time [text] means, or null. On a 12-hour clock a typed "am"/"pm" wins, else [pm] decides
     * for hours 1–12; hours 0 and 13–23 are read as 24-hour times whatever the clock.
     */
    fun parse(text: String, is24Hour: Boolean, pm: Boolean): LocalTime? {
        val lower = text.trim().lowercase().replace(" ", "")
        val suffix = Regex("([ap])\\.?(m\\.?)?$").find(lower)
        val typedPm = suffix?.groupValues?.get(1)?.let { it == "p" }
        val body = if (suffix != null) lower.substring(0, suffix.range.first) else lower
        val parts = body.split(':', '.', 'h')
        val (hour, minute) = when {
            parts.size == 2 && parts.all { p -> p.isNotEmpty() && p.all(Char::isDigit) } && parts[1].length == 2 ->
                parts[0].toInt() to parts[1].toInt()
            parts.size == 1 && body.isNotEmpty() && body.all(Char::isDigit) -> when (body.length) {
                1, 2 -> body.toInt() to 0
                3, 4 -> body.dropLast(2).toInt() to body.takeLast(2).toInt()
                else -> return null
            }
            else -> return null
        }
        if (minute !in 0..59) return null
        val twelveHour = !is24Hour || typedPm != null
        val h = if (twelveHour && hour in 1..12) {
            val afternoon = typedPm ?: pm
            when {
                hour == 12 -> if (afternoon) 12 else 0
                afternoon -> hour + 12
                else -> hour
            }
        } else {
            if (typedPm != null && hour !in 1..12) return null
            hour
        }
        return if (h in 0..23) LocalTime.of(h, minute) else null
    }

    /** What the box shows: "6:30" on a 12-hour clock (AM/PM is beside it), "06:30" on 24. */
    fun format(time: LocalTime, is24Hour: Boolean): String =
        if (is24Hour) "%02d:%02d".format(time.hour, time.minute)
        else "%d:%02d".format((time.hour + 11) % 12 + 1, time.minute)

    fun step(time: LocalTime, minutes: Int): LocalTime = time.plusMinutes(minutes.toLong())
}

/**
 * A time as a box to type in, AM/PM on a 12-hour clock, and −10 −5 −1 +1 +5 +10 buttons. Every
 * valid change is reported at once; while what is typed is not a time, the last good one stands.
 */
@Composable
fun TimeEntry(
    time: LocalTime,
    is24Hour: Boolean,
    onChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "time",
) {
    val c = cascadeColors
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf(TimeEntryRules.format(time, is24Hour)) }
    var invalid by remember { mutableStateOf(false) }
    val pm = time.hour >= 12
    // A change from the buttons or AM/PM rewrites the box; one typed here is left as typed.
    LaunchedEffect(time, is24Hour) {
        if (TimeEntryRules.parse(text, is24Hour, pm) != time) {
            text = TimeEntryRules.format(time, is24Hour)
            invalid = false
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(CascadeSpace.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CascadeSpace.m)) {
            OutlinedTextField(
                value = text,
                onValueChange = { typed ->
                    text = typed
                    val parsed = TimeEntryRules.parse(typed, is24Hour, pm)
                    invalid = parsed == null
                    if (parsed != null && parsed != time) onChange(parsed)
                },
                textStyle = CascadeType.homeNextAlarm.copy(color = c.textPrimary, fontSize = 44.sp),
                singleLine = true,
                isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier
                    .width(200.dp)
                    .testTag(tag)
                    .semantics { contentDescription = "Time" }
                    .onFocusChanged { if (!it.isFocused) { text = TimeEntryRules.format(time, is24Hour); invalid = false } },
            )
            if (!is24Hour) {
                Column(verticalArrangement = Arrangement.spacedBy(CascadeSpace.xs)) {
                    FilterChip(
                        selected = !pm,
                        onClick = { if (pm) onChange(time.minusHours(12)) },
                        label = { Text("AM") },
                        modifier = Modifier.testTag("$tag-am"),
                    )
                    FilterChip(
                        selected = pm,
                        onClick = { if (!pm) onChange(time.plusHours(12)) },
                        label = { Text("PM") },
                        modifier = Modifier.testTag("$tag-pm"),
                    )
                }
            }
        }
        if (invalid) {
            Text("Type a time like 6:30", style = CascadeType.body, color = c.textPrimary, modifier = Modifier.testTag("$tag-invalid"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CascadeSpace.xs)) {
            TimeEntryRules.STEPS.forEach { minutes ->
                val label = if (minutes < 0) "−${-minutes}" else "+$minutes"
                SecondaryButton(
                    label,
                    { focus.clearFocus(); onChange(TimeEntryRules.step(time, minutes)) },
                    Modifier.weight(1f).testTag("$tag-step$minutes").semantics {
                        contentDescription = if (minutes < 0) "${-minutes} minutes earlier" else "$minutes minutes later"
                    },
                    small = true,
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
    }
}
