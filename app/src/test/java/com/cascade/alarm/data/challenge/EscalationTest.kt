package com.cascade.alarm.data.challenge

import com.cascade.alarm.data.db.AlarmEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

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

    private val six = Instant.parse("2026-10-25T06:00:00Z")
    private fun at(minutes: Int) = six.plusSeconds(minutes * 60L)
    private fun due(a: AlarmEntity) = a.copy(nextTriggerAt = at(a.positionInGroup * 10))
    private fun rang(a: AlarmEntity) = a.copy(isEnabled = false, nextTriggerAt = null, lastFiredFor = at(a.positionInGroup * 10))

    @Test
    fun `one-shot alarms that already rang this morning still count, so each later one climbs the ladder`() {
        // a and b rang and switched themselves off; c is ringing now; d is still to come.
        val alarms = listOf(rang(alarm("a", 0)), rang(alarm("b", 1)), rang(alarm("c", 2)), due(alarm("d", 3)))
        assertEquals(2, Escalation.position(alarms[2], alarms, at(20)))
        assertEquals(ladder.steps[2], Escalation.resolve(alarms[2], ladder, Escalation.position(alarms[2], alarms, at(20))))
        assertEquals(4, Escalation.cascade(alarms[2], alarms, at(20)).size)
        // The ring path holds the row as it was before the fire was recorded; only the ring time matters.
        val beforeFire = due(alarm("c", 2))
        assertEquals(2, Escalation.position(beforeFire, alarms, at(20)))
    }

    @Test
    fun `the same holds on the next morning, when every row still carries yesterday's ring`() {
        val yesterday = { a: AlarmEntity -> a.copy(lastFiredFor = at(a.positionInGroup * 10).minusSeconds(86_400)) }
        val alarms = listOf(rang(alarm("a", 0)), rang(alarm("b", 1)), yesterday(due(alarm("c", 2))))
        assertEquals(2, Escalation.position(alarms[2], alarms, at(20)))
    }

    @Test
    fun `an alarm that rang on an earlier day, or was switched off by hand, does not count`() {
        val alarms = listOf(
            alarm("a", 0, enabled = false).copy(lastFiredFor = at(0).minusSeconds(24 * 3600)),
            alarm("b", 1, enabled = false),
            due(alarm("c", 2)),
        )
        assertEquals(0, Escalation.position(alarms[2], alarms, at(20)))
    }

    @Test
    fun `an alarm on that is not due this morning (other days) is not in the cascade`() {
        val saturday = Instant.parse("2026-10-31T06:20:00Z")
        val alarms = listOf(rang(alarm("a", 0)), due(alarm("b", 1)), alarm("c", 2).copy(nextTriggerAt = saturday))
        assertEquals(listOf("a", "b"), Escalation.cascade(alarms[1], alarms, at(10)).map { it.id })
    }

    @Test
    fun `alarms due at the same minute are both in the cascade`() {
        val alarms = listOf(due(alarm("a", 0)), alarm("b", 1).copy(nextTriggerAt = at(0)))
        assertEquals(listOf("a", "b"), Escalation.cascade(alarms[0], alarms, at(0)).map { it.id })
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
