package com.cascade.alarm.data.billing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlusOwnershipTest {
    @Test
    fun `a completed purchase unlocks`() {
        assertEquals(true, PlusOwnership.after(listOf(true), complete = false))
        assertEquals(true, PlusOwnership.after(listOf(true), complete = true))
    }

    @Test
    fun `a full answer from Play without Plus means it was refunded`() =
        assertEquals(false, PlusOwnership.after(emptyList(), complete = true))

    @Test
    fun `a pending purchase is not owned yet, and only a full answer says so`() {
        assertNull(PlusOwnership.after(listOf(false), complete = false))
        assertEquals(false, PlusOwnership.after(listOf(false), complete = true))
    }

    @Test
    fun `an update about something else leaves ownership alone`() =
        assertNull(PlusOwnership.after(emptyList(), complete = false))
}
