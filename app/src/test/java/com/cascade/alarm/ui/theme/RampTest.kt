package com.cascade.alarm.ui.theme

import com.cascade.alarm.ui.theme.RampStop.RAMP1
import com.cascade.alarm.ui.theme.RampStop.RAMP2
import com.cascade.alarm.ui.theme.RampStop.RAMP3
import com.cascade.alarm.ui.theme.RampStop.RAMP4
import com.cascade.alarm.ui.theme.RampStop.RAMP5
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RampTest {
    private fun stops(total: Int) = (0 until total).map { rampStopFor(it, total) }

    @Test
    fun `the design table for one to five alarms`() {
        assertEquals(listOf(RAMP5), stops(1))
        assertEquals(listOf(RAMP1, RAMP5), stops(2))
        assertEquals(listOf(RAMP1, RAMP3, RAMP5), stops(3))
        assertEquals(listOf(RAMP1, RAMP2, RAMP4, RAMP5), stops(4))
        assertEquals(listOf(RAMP1, RAMP2, RAMP3, RAMP4, RAMP5), stops(5))
    }

    @Test
    fun `longer sequences spread evenly, anchored at both ends, never going down`() {
        for (total in 6..12) {
            val s = stops(total)
            assertEquals(RAMP1, s.first(), "total=$total")
            assertEquals(RAMP5, s.last(), "total=$total")
            assertEquals(s, s.sorted(), "total=$total")
        }
        assertEquals(listOf(RAMP1, RAMP2, RAMP3, RAMP3, RAMP4, RAMP5), stops(6))
    }

    @Test
    fun `positions past the end repeat ramp5`() {
        assertEquals(RAMP5, rampStopFor(7, 3))
        assertEquals(RAMP5, rampStopFor(0, 0))
    }

    @Test
    fun `progress runs from 0 at the first alarm to 1 at the last`() {
        assertEquals(0f, sequenceProgress(0, 4))
        assertEquals(1f, sequenceProgress(3, 4))
        assertEquals(1f, sequenceProgress(0, 1))
    }
}
