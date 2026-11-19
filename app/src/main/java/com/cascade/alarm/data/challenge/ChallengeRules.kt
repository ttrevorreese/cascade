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

/**
 * SPEC §6.1's three tiers. Every answer is at least 1, subtractions leave at least 10 (hard: 2),
 * and no sum repeats within a ring (owner report, 2026-11-18: three medium sums in a row came to 0).
 */
object MathProblems {
    fun generate(seed: Long, difficulty: Difficulty, count: Int): List<MathProblem> {
        val random = Random(mix(seed))
        val problems = mutableListOf<MathProblem>()
        repeat(count.coerceIn(ChallengeConfig.MIN_PROBLEMS, ChallengeConfig.MAX_PROBLEMS)) {
            var problem = one(random, difficulty)
            // The tiers have hundreds of sums each, so a few tries always find a new one.
            var tries = 0
            while (problems.any { it.text == problem.text || it.answer == problem.answer } && tries++ < 20) problem = one(random, difficulty)
            problems += problem
        }
        return problems
    }

    /** One sum of [difficulty] for the editor to show what it is like. */
    fun sample(difficulty: Difficulty, seed: Long): MathProblem = generate(seed, difficulty, 1).first()

    private fun one(r: Random, difficulty: Difficulty): MathProblem = when (difficulty) {
        // Single-digit addition.
        Difficulty.EASY -> {
            val (a, b) = r.nextInt(2, 10) to r.nextInt(2, 10)
            MathProblem("$a + $b", a + b)
        }
        // Two-digit addition, or subtraction leaving at least 10.
        Difficulty.MEDIUM -> if (r.nextBoolean()) {
            val (a, b) = r.nextInt(11, 100) to r.nextInt(11, 100)
            MathProblem("$a + $b", a + b)
        } else {
            val a = r.nextInt(31, 100)
            val b = r.nextInt(11, a - 9)
            MathProblem("$a − $b", a - b)
        }
        // Multi-step with multiplication; the subtraction always leaves something.
        Difficulty.HARD -> {
            val (a, b) = r.nextInt(3, 13) to r.nextInt(3, 13)
            if (r.nextBoolean() || a * b < 14) {
                val c = r.nextInt(11, 100)
                MathProblem("$a × $b + $c", a * b + c)
            } else {
                val c = r.nextInt(11, a * b - 1)
                MathProblem("$a × $b − $c", a * b - c)
            }
        }
    }

    /** Spreads seeds that differ in a few bits (same alarm, next day) across the whole range. */
    private fun mix(seed: Long): Long {
        var z = seed + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}

object Phrases {
    private val WORDS = listOf(
        "kettle", "window", "morning", "coffee", "orange", "garden", "pencil", "river", "blanket", "lantern",
        "harbour", "copper", "meadow", "tunnel", "violet", "saddle", "marble", "cactus", "anchor", "candle",
    )

    /** A short phrase for a TypePhrase challenge with no phrase of its own; no word twice (2026-11-18). */
    fun generated(seed: Long, words: Int = 4): String =
        WORDS.shuffled(Random(seed)).take(words.coerceIn(1, WORDS.size)).joinToString(" ")

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
