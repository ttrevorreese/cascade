package com.cascade.alarm.data.challenge

import com.cascade.alarm.data.db.AlarmEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EscalationTest {
    private fun alarm(id: String, position: Int, enabled: Boolean = true, override: ChallengeConfig? = null) = AlarmEntity(
        id = id, groupId = "g", positionInGroup = position, hour = 6, minute = position * 10, daysOfWeek = emptySet(),
        label = null, isEnabled = enabled, volumeRampSeconds = 30, timeoutMinutes = 15, challengeOverride = override,
    )

    private val ladder = EscalationPolicy(listOf(ChallengeConfig.Math(Difficulty.EASY, 1), ChallengeConfig.TypePhrase(), ChallengeConfig.Steps(20)))

    @Test
    fun `each alarm gets the rung for its place, and the last rung repeats`() {
        val alarms = (0..4).map { alarm("a$it", it) }
        val resolved = alarms.map { Escalation.resolve(it, ladder, Escalation.position(it, alarms)) }
        assertEquals(listOf(ladder.steps[0], ladder.steps[1], ladder.steps[2], ladder.steps[2], ladder.steps[2]), resolved)
    }

    @Test
    fun `switched-off alarms do not count, so the last alarm that rings gets the hardest rung`() {
        val alarms = listOf(alarm("a", 0), alarm("b", 1, enabled = false), alarm("c", 2))
        assertEquals(1, Escalation.position(alarms[2], alarms))
        assertEquals(ladder.steps[1], Escalation.resolve(alarms[2], ladder, Escalation.position(alarms[2], alarms)))
    }

    @Test
    fun `a one-shot alarm that switched itself off as it rang still counts itself`() {
        val alarms = listOf(alarm("a", 0), alarm("b", 1, enabled = false))
        assertEquals(1, Escalation.position(alarms[1], alarms))
    }

    @Test
    fun `an alarm's own challenge overrides its rung, and None means plain dismiss`() {
        val own = ChallengeConfig.BarcodeScan("cascade:1", "Mirror")
        assertEquals(own, Escalation.resolve(alarm("a", 0, override = own), ladder, 0))
        assertNull(Escalation.resolve(alarm("a", 0, override = ChallengeConfig.None), ladder, 2))
    }

    @Test
    fun `an empty ladder means no challenge, and a None rung keeps that place easy`() {
        assertNull(Escalation.resolve(alarm("a", 0), EscalationPolicy(), 3))
        val withNone = EscalationPolicy(listOf(ChallengeConfig.None, ChallengeConfig.Math()))
        assertNull(Escalation.resolve(alarm("a", 0), withNone, 0))
        assertEquals(ChallengeConfig.Math(), Escalation.resolve(alarm("b", 1), withNone, 1))
    }

    @Test
    fun `the difficulty meter rises with the challenge`() {
        assertEquals(0, Escalation.difficulty(null))
        assertEquals(0, Escalation.difficulty(ChallengeConfig.None))
        assertTrue(Escalation.difficulty(ChallengeConfig.Math(Difficulty.EASY)) < Escalation.difficulty(ChallengeConfig.Math(Difficulty.HARD)))
        assertEquals(5, Escalation.difficulty(ChallengeConfig.BarcodeScan("x", "")))
        val suggested = Escalation.SUGGESTED.steps.map(Escalation::difficulty)
        assertEquals(suggested.sorted(), suggested, "the suggested ladder only climbs")
    }

    @Test
    fun `which alarms each rung covers`() {
        assertEquals(1..1, Escalation.alarmsFor(0, rungs = 3, alarmsOn = 5))
        assertEquals(3..5, Escalation.alarmsFor(2, rungs = 3, alarmsOn = 5))
        assertEquals(2..2, Escalation.alarmsFor(1, rungs = 3, alarmsOn = 2))
        assertNull(Escalation.alarmsFor(2, rungs = 3, alarmsOn = 2))
    }

    @Test
    fun `ladders survive storage and an unreadable one is empty`() {
        val policy = EscalationPolicy(Escalation.SUGGESTED.steps + ChallengeConfig.None)
        assertEquals(policy, EscalationPolicy.decode(EscalationPolicy.encode(policy)))
        assertEquals(EscalationPolicy(), EscalationPolicy.decode("{}"))
        assertEquals(EscalationPolicy(), EscalationPolicy.decode("garbage"))
    }
}
