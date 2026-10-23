package com.cascade.alarm.ui

import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.Difficulty

/** Challenges in plain words, for rows, rungs and the alarm editor (DESIGN.md §7). */
object ChallengeText {
    /** A few words: "Sums", "Scan a code". Null for no challenge. */
    fun short(config: ChallengeConfig?): String? = when (config) {
        null, ChallengeConfig.None -> null
        is ChallengeConfig.Math -> "Sums"
        is ChallengeConfig.TypePhrase -> "Type a phrase"
        is ChallengeConfig.BarcodeScan -> "Scan a code"
        is ChallengeConfig.PhotoMatch -> "Match a photo"
        is ChallengeConfig.Steps -> "Walk"
        is ChallengeConfig.Memory -> "Repeat a sequence"
    }

    /** With its settings: "2 easy sums", "Scan the code at Bathroom mirror". */
    fun full(config: ChallengeConfig?): String = when (config) {
        null, ChallengeConfig.None -> "No challenge, just Dismiss"
        is ChallengeConfig.Math -> {
            val level = when (config.difficulty) {
                Difficulty.EASY -> "easy"
                Difficulty.MEDIUM -> "medium"
                Difficulty.HARD -> "hard"
            }
            if (config.problemCount == 1) "1 $level sum" else "${config.problemCount} $level sums"
        }
        is ChallengeConfig.TypePhrase -> if (config.phrase.isBlank()) "Type four random words" else "Type \"${config.phrase}\""
        is ChallengeConfig.BarcodeScan -> if (config.label.isBlank()) "Scan your code" else "Scan the code at ${config.label}"
        is ChallengeConfig.PhotoMatch -> if (config.label.isBlank()) "Match your photo" else "Match the photo of ${config.label.lowercase()}"
        is ChallengeConfig.Steps -> "Walk ${config.count} steps"
        is ChallengeConfig.Memory -> "Repeat a sequence of ${config.sequenceLength}"
    }
}
