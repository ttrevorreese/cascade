package com.cascade.alarm.data.sound

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VibrationTest {
    @Test
    fun `the default is the old steady buzz at full strength, straight away`() {
        val (timings, amplitudes) = VibrationSetting().waveform()
        assertArrayEquals(longArrayOf(0, 800, 600), timings)
        assertArrayEquals(intArrayOf(0, 255, 0), amplitudes)
    }

    @Test
    fun `strength scales every buzz and never reaches zero`() {
        val (_, light) = VibrationSetting(VibrationPattern.HEARTBEAT, 30).waveform()
        assertArrayEquals(intArrayOf(0, 53, 0, 76, 0), light)
        val (_, wave) = VibrationSetting(VibrationPattern.WAVE, 30).waveform()
        assertTrue(wave.dropLast(1).all { it >= 1 })
        assertEquals(0, wave.last())
    }

    @Test
    fun `every pattern has a level for each timing`() {
        VibrationPattern.entries.forEach { assertEquals(it.timings.size, it.levels.size, it.key) }
    }

    @Test
    fun `an unknown pattern vibrates rather than going silent, and values are kept in range`() {
        assertEquals(VibrationPattern.STEADY, VibrationSetting.of("buzzy", 500, 9999).pattern)
        assertEquals(VibrationSetting(VibrationPattern.STEADY, 100, 120), VibrationSetting.of("buzzy", 500, 9999))
        assertEquals(VibrationSetting(VibrationPattern.OFF, 30, 0), VibrationSetting.of("off", 0, -5))
        assertTrue(VibrationSetting.of("off", 100, 0).isOff)
    }
}
