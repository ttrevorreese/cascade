package com.cascade.alarm.data.challenge

import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * A ring's challenge content is generated from this seed, so force-quitting and reopening shows
 * the same problems (SPEC §6.1). It depends only on what the persisted ring already records.
 */
fun challengeSeed(alarmId: String, scheduledFor: Instant): Long =
    (alarmId.hashCode().toLong() shl 32) xor scheduledFor.epochSecond

data class MathProblem(val text: String, val answer: Int)

/** SPEC §6.1's three tiers. */
object MathProblems {
    fun generate(seed: Long, difficulty: Difficulty, count: Int): List<MathProblem> {
        val random = Random(seed)
        return List(count.coerceIn(ChallengeConfig.MIN_PROBLEMS, ChallengeConfig.MAX_PROBLEMS)) { one(random, difficulty) }
    }

    private fun one(r: Random, difficulty: Difficulty): MathProblem = when (difficulty) {
        // Single-digit addition.
        Difficulty.EASY -> {
            val (a, b) = r.nextInt(1, 10) to r.nextInt(1, 10)
            MathProblem("$a + $b", a + b)
        }
        // Two-digit addition or subtraction, never negative.
        Difficulty.MEDIUM -> {
            val (a, b) = r.nextInt(10, 100) to r.nextInt(10, 100)
            if (r.nextBoolean()) MathProblem("$a + $b", a + b)
            else MathProblem("${maxOf(a, b)} − ${minOf(a, b)}", maxOf(a, b) - minOf(a, b))
        }
        // Multi-step with multiplication, never negative.
        Difficulty.HARD -> {
            val (a, b, c) = Triple(r.nextInt(3, 13), r.nextInt(3, 13), r.nextInt(10, 100))
            if (r.nextBoolean()) MathProblem("$a × $b + $c", a * b + c)
            else MathProblem("$a × $b − ${minOf(c, a * b)}", a * b - minOf(c, a * b))
        }
    }
}

object Phrases {
    private val WORDS = listOf(
        "kettle", "window", "morning", "coffee", "orange", "garden", "pencil", "river", "blanket", "lantern",
        "harbour", "copper", "meadow", "tunnel", "violet", "saddle", "marble", "cactus", "anchor", "candle",
    )

    /** A short phrase for a TypePhrase challenge with no phrase of its own. */
    fun generated(seed: Long, words: Int = 4): String {
        val random = Random(seed)
        return List(words) { WORDS[random.nextInt(WORDS.size)] }.joinToString(" ")
    }

    /** Case-insensitive and whitespace-normalised (SPEC §6.5). */
    fun normalize(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ")

    fun matches(target: String, typed: String): Boolean = normalize(target) == normalize(typed)

    /**
     * Rejects edits that insert more than a couple of characters at once: that is a paste, or a
     * keyboard's clipboard suggestion, which the text field's own menu cannot block.
     */
    fun acceptEdit(before: String, after: String): Boolean = after.length - before.length <= 2
}

object Barcodes {
    /** Exact comparison (SPEC §6.2). */
    fun matches(expected: String, scanned: String): Boolean = expected == scanned
}

/** When the "give up" escape hatch appears (SPEC §6.7): after enough misses, or 3 minutes in. */
object GiveUp {
    const val FAILED_ATTEMPTS = 3
    val AFTER: Duration = Duration.ofMinutes(3)

    fun available(failedAttempts: Int, ringingFor: Duration): Boolean =
        failedAttempts >= FAILED_ATTEMPTS || ringingFor >= AFTER
}
