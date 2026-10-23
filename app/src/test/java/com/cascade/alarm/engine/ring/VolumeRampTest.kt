package com.cascade.alarm.engine.ring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.cascade.alarm.data.sound.SoundSettings
import kotlin.math.pow
import kotlin.math.sqrt

class VolumeRampTest {
    private val floor = 0.05f

    @Test
    fun `starts at the floor`() = assertEquals(floor, VolumeRamp.gain(0, 30, floor), 1e-6f)

    @Test
    fun `reaches full volume at the end of the ramp and stays there`() {
        assertEquals(1f, VolumeRamp.gain(30_000, 30, floor), 1e-6f)
        assertEquals(1f, VolumeRamp.gain(10 * 60_000, 30, floor), 1e-6f)
    }

    @Test
    fun `zero-length ramp is full volume immediately`() = assertEquals(1f, VolumeRamp.gain(0, 0, floor))

    @Test
    fun `ramp longer than the spec maximum is clamped to 120 seconds`() =
        assertEquals(1f, VolumeRamp.gain(120_000, 600, floor), 1e-6f)

    @Test
    fun `negative elapsed time is treated as the start`() = assertEquals(floor, VolumeRamp.gain(-500, 30, floor), 1e-6f)

    @Test
    fun `rises evenly in decibels so the midpoint is the geometric mean`() =
        assertEquals(sqrt(floor), VolumeRamp.gain(15_000, 30, floor), 1e-4f)

    @Test
    fun `start level is perceived loudness, so half as loud is -10 dB`() {
        assertEquals(1f, VolumeRamp.startGain(100), 1e-6f)
        assertEquals(10.0.pow(-10.0 / 20).toFloat(), VolumeRamp.startGain(50), 1e-4f)
        assertEquals(10.0.pow(-20.0 / 20).toFloat(), VolumeRamp.startGain(25), 1e-4f)
    }

    @Test
    fun `the default start is close to the old fixed floor`() =
        assertEquals(VolumeRamp.DEFAULT_FLOOR, VolumeRamp.startGain(SoundSettings.DEFAULT_START_PERCENT), 0.01f)

    @Test
    fun `a higher start is never quieter`() {
        val gains = (SoundSettings.MIN_START_PERCENT..SoundSettings.MAX_START_PERCENT).map(VolumeRamp::startGain)
        assertEquals(gains.sorted(), gains)
        assertTrue(gains.first() > 0f)
    }

    @Test
    fun `is monotonic and bounded`() {
        var previous = 0f
        for (ms in 0L..40_000L step 250) {
            val g = VolumeRamp.gain(ms, 30, floor)
            assertTrue(g >= previous && g in floor..1f, "gain $g at $ms ms")
            previous = g
        }
    }
}
