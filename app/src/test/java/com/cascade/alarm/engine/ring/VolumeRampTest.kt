package com.cascade.alarm.engine.ring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
    fun `is monotonic and bounded`() {
        var previous = 0f
        for (ms in 0L..40_000L step 250) {
            val g = VolumeRamp.gain(ms, 30, floor)
            assertTrue(g >= previous && g in floor..1f, "gain $g at $ms ms")
            previous = g
        }
    }
}
