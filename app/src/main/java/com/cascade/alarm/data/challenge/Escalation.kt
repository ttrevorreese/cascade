package com.cascade.alarm.data.challenge

import com.cascade.alarm.data.db.AlarmEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration

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

    /** How far back an alarm that already rang still belongs to the same morning's cascade. */
    private val CASCADE_WINDOW: Duration = Duration.ofHours(12)

    /**
     * The alarms of [alarm]'s cascade, in ring order: those that are on, [alarm] itself, and those
     * that rang in the [CASCADE_WINDOW] before it. A one-shot alarm switches itself off as it rings,
     * so without the last rule each one-shot alarm would think it was the first (fixed 2026-10-25).
     */
    fun cascade(alarm: AlarmEntity, groupAlarms: List<AlarmEntity>): List<AlarmEntity> {
        val firedAt = alarm.lastFiredFor
        fun rangBefore(other: AlarmEntity): Boolean {
            val at = other.lastFiredFor ?: return false
            return firedAt != null && !at.isAfter(firedAt) && Duration.between(at, firedAt) <= CASCADE_WINDOW
        }
        return groupAlarms.sortedBy { it.positionInGroup }
            .filter { it.isEnabled || it.id == alarm.id || rangBefore(it) }
    }

    /**
     * [alarm]'s place for escalation within its [cascade], so switched-off alarms do not count and
     * the last alarm that actually rings gets the hardest rung.
     */
    fun position(alarm: AlarmEntity, groupAlarms: List<AlarmEntity>): Int =
        cascade(alarm, groupAlarms).indexOfFirst { it.id == alarm.id }.coerceAtLeast(0)

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
