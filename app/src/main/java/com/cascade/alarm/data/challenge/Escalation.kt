package com.cascade.alarm.data.challenge

import com.cascade.alarm.data.db.AlarmEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

/**
 * A group's ladder (SPEC §5): [steps] index maps to an alarm's place in the sequence, and the last
 * entry repeats for any alarms beyond it. Empty means no challenges unless an alarm sets its own.
 */
@Serializable
data class EscalationPolicy(val steps: List<ChallengeConfig> = emptyList()) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(policy: EscalationPolicy): String = json.encodeToString(serializer(), policy)

        /** Never throws: a ladder this version cannot read is treated as empty. */
        fun decode(text: String): EscalationPolicy =
            runCatching { json.decodeFromString(serializer(), text) }.getOrDefault(EscalationPolicy())
    }
}

object Escalation {
    /** One-tap starting point in the escalation editor (SPEC §5, decided 2026-10-22). */
    val SUGGESTED = EscalationPolicy(
        listOf(
            ChallengeConfig.Math(Difficulty.EASY, 2),
            ChallengeConfig.TypePhrase(),
            ChallengeConfig.Math(Difficulty.HARD, 3),
            ChallengeConfig.Steps(30),
        ),
    )

    /** How far either side of a ring the same morning's cascade reaches. */
    val CASCADE_WINDOW: Duration = Duration.ofHours(12)

    /**
     * The alarms of [alarm]'s cascade, in ring order.
     *
     * For a ring at [ringAt] (the trigger that went off): [alarm] itself, the group's alarms that
     * rang in the [CASCADE_WINDOW] before it, and those that are on and due within the window
     * after it. A one-shot alarm switches itself off as it rings, and an alarm can repeat on other
     * days than the rest of its group, so "is on" alone is not the cascade (fixed 2026-10-25).
     *
     * Without [ringAt] (the editor and group screen): [alarm] and the alarms that are on.
     */
    fun cascade(alarm: AlarmEntity, groupAlarms: List<AlarmEntity>, ringAt: Instant? = null): List<AlarmEntity> {
        fun inCascade(other: AlarmEntity): Boolean {
            if (other.id == alarm.id) return true
            if (ringAt == null) return other.isEnabled
            val rang = other.lastFiredFor
            if (rang != null && !rang.isAfter(ringAt) && Duration.between(rang, ringAt) <= CASCADE_WINDOW) return true
            val due = other.nextTriggerAt
            return other.isEnabled && due != null && !due.isBefore(ringAt) && Duration.between(ringAt, due) <= CASCADE_WINDOW
        }
        return groupAlarms.sortedBy { it.positionInGroup }.filter(::inCascade)
    }

    /**
     * [alarm]'s place for escalation within its [cascade], so switched-off alarms do not count and
     * the last alarm that actually rings gets the last rung.
     */
    fun position(alarm: AlarmEntity, groupAlarms: List<AlarmEntity>, ringAt: Instant? = null): Int =
        cascade(alarm, groupAlarms, ringAt).indexOfFirst { it.id == alarm.id }.coerceAtLeast(0)

    /** The rung for [position]; the last rung repeats. Null when the ladder is empty. */
    fun rung(policy: EscalationPolicy, position: Int): ChallengeConfig? =
        policy.steps.getOrNull(position) ?: policy.steps.lastOrNull()

    /**
     * What dismissing [alarm] takes (SPEC §5): its own override, else its rung. Null means the
     * plain Dismiss button, and so does an explicit [ChallengeConfig.None].
     */
    fun resolve(alarm: AlarmEntity, policy: EscalationPolicy, position: Int): ChallengeConfig? =
        (alarm.challengeOverride ?: rung(policy, position)).takeUnless { it == ChallengeConfig.None }

    /**
     * Which alarms rung [index] of [rungs] covers, given [alarmsOn] alarms that are on (1-based
     * numbers), or null if none do yet. The last rung covers everything after it.
     */
    fun alarmsFor(index: Int, rungs: Int, alarmsOn: Int): IntRange? {
        if (index >= alarmsOn) return null
        val last = if (index == rungs - 1) alarmsOn else index + 1
        return (index + 1)..last
    }

    /** 0..5 for the escalation editor's difficulty meter (DESIGN.md §6). */
    fun difficulty(config: ChallengeConfig?): Int = when (config) {
        null, ChallengeConfig.None -> 0
        is ChallengeConfig.TypePhrase -> 1
        is ChallengeConfig.Math -> when (config.difficulty) {
            Difficulty.EASY -> 1
            Difficulty.MEDIUM -> 2
            Difficulty.HARD -> 3
        }
        is ChallengeConfig.Memory -> if (config.sequenceLength <= 4) 2 else 3
        is ChallengeConfig.Steps -> if (config.count <= 30) 3 else 4
        is ChallengeConfig.PhotoMatch -> 4
        is ChallengeConfig.BarcodeScan -> 5
    }
}
