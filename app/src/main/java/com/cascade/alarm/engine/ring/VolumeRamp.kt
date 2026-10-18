package com.cascade.alarm.engine.ring

import kotlin.math.pow

/**
 * Player gain over the ramp window (SPEC §4.2: configurable 0–120 s from a configurable floor).
 * Loudness is perceived logarithmically, so gain rises linearly in decibels rather than amplitude;
 * a linear-amplitude ramp sounds like it jumps to full volume in the first few seconds.
 *
 * Gain is relative to the device's alarm-stream volume, which this does not change.
 */
object VolumeRamp {
    const val DEFAULT_FLOOR = 0.05f // about -26 dB
    const val MAX_RAMP_SECONDS = 120

    fun gain(elapsedMillis: Long, rampSeconds: Int, floor: Float = DEFAULT_FLOOR): Float {
        val ramp = rampSeconds.coerceAtMost(MAX_RAMP_SECONDS)
        if (ramp <= 0) return 1f
        val progress = (elapsedMillis.toDouble() / (ramp * 1000L)).coerceIn(0.0, 1.0)
        return floor.toDouble().pow(1.0 - progress).toFloat().coerceIn(floor, 1f)
    }
}
