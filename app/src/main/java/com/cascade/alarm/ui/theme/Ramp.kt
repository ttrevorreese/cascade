package com.cascade.alarm.ui.theme

import kotlin.math.roundToInt

enum class RampStop { RAMP1, RAMP2, RAMP3, RAMP4, RAMP5 }

/**
 * The ramp stop for the alarm at [position] (0-based) of [total] (DESIGN.md §3). The first and
 * last alarms always anchor ramp1 and ramp5; those between are spread evenly and rounded to the
 * nearest stop. A single alarm is the last one, so it gets ramp5. Every screen that colours an
 * alarm goes through here so they agree.
 */
fun rampStopFor(position: Int, total: Int): RampStop {
    if (total <= 1 || position >= total - 1) return RampStop.RAMP5
    val last = RampStop.entries.lastIndex
    val index = (position.coerceAtLeast(0) * last.toFloat() / (total - 1)).roundToInt()
    return RampStop.entries[index]
}

/** How far along the sequence [position] is, 0 for the first alarm and 1 for the last. */
fun sequenceProgress(position: Int, total: Int): Float =
    if (total <= 1) 1f else (position.toFloat() / (total - 1)).coerceIn(0f, 1f)
