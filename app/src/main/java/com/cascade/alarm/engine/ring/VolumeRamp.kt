package com.cascade.alarm.engine.ring

import kotlin.math.ln
import kotlin.math.pow

/**
 * Player gain over the ramp window (SPEC §4.2: a configurable window from a configurable floor).
 * Loudness is perceived logarithmically, so gain rises linearly in decibels rather than amplitude;
 * a linear-amplitude ramp sounds like it jumps to full volume in the first few seconds.
 *
 * Gain is relative to the device's alarm-stream volume, which this does not change.
 */
object VolumeRamp {
    const val DEFAULT_FLOOR = 0.05f // about -26 dB
    const val MAX_RAMP_SECONDS = 120

    /**
     * Perceived loudness doubles every +10 dB: loudness L is 10·log2(L) dB, so the amplitude gain
     * is 10^(log2(L) / 2) = L^(log2(10) / 2).
     */
    private val LOUDNESS_TO_GAIN = ln(10.0) / ln(2.0) / 2

    fun gain(elapsedMillis: Long, rampSeconds: Int, floor: Float = DEFAULT_FLOOR): Float {
        val ramp = rampSeconds.coerceAtMost(MAX_RAMP_SECONDS)
        if (ramp <= 0) return 1f
        val progress = (elapsedMillis.toDouble() / (ramp * 1000L)).coerceIn(0.0, 1.0)
        return floor.toDouble().pow(1.0 - progress).toFloat().coerceIn(floor, 1f)
    }

    /**
     * The floor for a ramp that starts at [percent] of full *perceived* loudness, which is what the
     * "Start at" slider means: 50% sounds half as loud (-10 dB, gain ≈ 0.32), not half the amplitude.
     */
    fun startGain(percent: Int): Float = (percent.coerceIn(1, 100) / 100.0).pow(LOUDNESS_TO_GAIN).toFloat()
}
