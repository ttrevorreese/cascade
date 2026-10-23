package com.cascade.alarm.data.challenge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Difficulty { EASY, MEDIUM, HARD }

/**
 * What must be done to dismiss an alarm (SPEC §5, §6). Stored as JSON in
 * `alarms.challengeOverride` (null there means "follow the group's ladder") and in each group's
 * [EscalationPolicy].
 */
@Serializable
sealed interface ChallengeConfig {
    /**
     * Plain Dismiss, chosen on purpose: as an alarm's override it opts out of its group's ladder;
     * as a rung it keeps that position easy. An alarm with no override at all follows the ladder.
     */
    @Serializable @SerialName("none")
    data object None : ChallengeConfig

    @Serializable @SerialName("math")
    data class Math(val difficulty: Difficulty = Difficulty.MEDIUM, val problemCount: Int = 3) : ChallengeConfig

    /** A blank [phrase] means a generated one at alarm time. */
    @Serializable @SerialName("phrase")
    data class TypePhrase(val phrase: String = "") : ChallengeConfig

    /** [label] says where the code lives, e.g. "Bathroom mirror". */
    @Serializable @SerialName("barcode")
    data class BarcodeScan(val expectedPayload: String, val label: String) : ChallengeConfig

    @Serializable @SerialName("steps")
    data class Steps(val count: Int = 20) : ChallengeConfig

    /**
     * [signature] is the reference's fingerprint ([PhotoHash]); it lives in the database so the
     * challenge works before first unlock. The photo [referencePhotoId] is only for showing, and
     * stays in credential-encrypted storage. [label] says what it is, e.g. "Toothbrush".
     */
    @Serializable @SerialName("photo")
    data class PhotoMatch(
        val referencePhotoId: String,
        val threshold: Float = PhotoHash.DEFAULT_THRESHOLD,
        val signature: String = "",
        val label: String = "",
    ) : ChallengeConfig

    @Serializable @SerialName("memory")
    data class Memory(val sequenceLength: Int = 5) : ChallengeConfig

    companion object {
        const val MIN_PROBLEMS = 1
        const val MAX_PROBLEMS = 10
        const val MIN_STEPS = 10
        const val MAX_STEPS = 200

        /**
         * Stands in when a configured challenge cannot run (a permission was revoked, its data is
         * unreadable before first unlock, or the stored config cannot be read): still a
         * challenge, never undismissable (SPEC §6.8).
         */
        val FALLBACK: ChallengeConfig = Math(Difficulty.MEDIUM, 3)

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(config: ChallengeConfig): String = json.encodeToString(serializer(), config)

        /** Never throws: a config this version cannot read becomes [FALLBACK]. */
        fun decode(text: String): ChallengeConfig =
            runCatching { json.decodeFromString(serializer(), text) }.getOrDefault(FALLBACK)
    }
}
