package com.cascade.alarm.data.challenge

import kotlin.random.Random

/** Steps (SPEC §6.4): progress counted from the step counter's value when the challenge starts. */
data class StepProgress(val baseline: Long? = null, val latest: Long? = null) {
    fun record(counter: Long): StepProgress = if (baseline == null) StepProgress(counter, counter) else copy(latest = counter)

    /** Steps since the challenge started. The counter only resets at reboot, but never go negative. */
    val walked: Int get() = if (baseline == null || latest == null) 0 else (latest - baseline).coerceAtLeast(0).toInt()

    fun done(target: Int): Boolean = walked >= target
}

/** Memory sequence (SPEC §6.6): Simon-style, over four pads. */
object MemorySequence {
    const val PADS = 4
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 10

    fun generate(seed: Long, length: Int): List<Int> {
        val random = Random(seed)
        return List(length.coerceIn(MIN_LENGTH, MAX_LENGTH)) { random.nextInt(PADS) }
    }

    /** Whether [entered] so far is a correct start of [sequence]. */
    fun onTrack(sequence: List<Int>, entered: List<Int>): Boolean =
        entered.size <= sequence.size && entered.indices.all { entered[it] == sequence[it] }
}
