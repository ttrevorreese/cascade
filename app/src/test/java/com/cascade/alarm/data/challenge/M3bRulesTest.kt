package com.cascade.alarm.data.challenge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class M3bRulesTest {
    /**
     * A synthetic photo: a lit background with several shapes and some texture, as a continuous
     * function of (0..1, 0..1). Real photos have more structure than this, so it is a fair test
     * of the hard case: scenes that are mostly smooth.
     */
    private class Scene(seed: Int) {
        private val r = Random(seed)
        private val gx = r.nextDouble(-60.0, 60.0)
        private val gy = r.nextDouble(-60.0, 60.0)
        private val shapes = List(r.nextInt(3, 6)) {
            doubleArrayOf(r.nextDouble(), r.nextDouble(), r.nextDouble(0.06, 0.22), r.nextDouble(-90.0, 90.0), if (r.nextBoolean()) 1.0 else 0.0)
        }

        fun at(x: Double, y: Double): Double {
            var v = 120 + gx * (x - 0.5) + gy * (y - 0.5)
            for ((cx, cy, size, delta, square) in shapes.map { it.toList() }) {
                val inside = if (square == 1.0) abs(x - cx) < size && abs(y - cy) < size
                else (x - cx) * (x - cx) + (y - cy) * (y - cy) < size * size
                if (inside) v += delta
            }
            return v
        }
    }

    private operator fun <T> List<T>.component5() = this[4]

    /** Averages the scene over each cell of an n x n grid covering [x0, x0+span] x [y0, y0+span]. */
    private fun sample(
        scene: Scene, n: Int, x0: Double, y0: Double, span: Double,
        brightness: Double = 0.0, contrast: Double = 1.0, noise: Int = 0, seed: Int = 0,
    ): IntArray {
        val noiseRandom = Random(seed)
        return IntArray(n * n) { i ->
            val (cx, cy) = i % n to i / n
            var sum = 0.0
            for (sy in 0 until 4) for (sx in 0 until 4) {
                sum += scene.at(x0 + span * (cx + (sx + 0.5) / 4) / n, y0 + span * (cy + (sy + 0.5) / 4) / n)
            }
            val v = (sum / 16 - 120) * contrast + 120 + brightness + if (noise > 0) noiseRandom.nextInt(-noise, noise + 1) else 0
            v.toInt().coerceIn(0, 255)
        }
    }

    private fun reference(scene: Scene) = PhotoHash.signature(sample(scene, PhotoHash.GRID, 0.1, 0.1, PhotoHash.REFERENCE_CROP.toDouble()))

    /** A new photo of [scene]: framed off by up to ([ox], [oy]) of the frame, in other light, with noise. */
    private fun retake(scene: Scene, ox: Double = 0.0, oy: Double = 0.0, brightness: Double = 0.0, contrast: Double = 1.0, noise: Int = 0) =
        sample(scene, PhotoHash.SEARCH, ox, oy, 1.0, brightness, contrast, noise, seed = 99)

    @Test
    fun `the same scene re-shot in other light, off-centre and noisy, matches`() {
        val scores = (1..40).mapNotNull { seed ->
            val scene = Scene(seed)
            val ref = reference(scene) ?: return@mapNotNull null
            PhotoHash.similarity(ref, retake(scene, ox = 0.04, oy = -0.03, brightness = -35.0, contrast = 0.65, noise = 3))
        }
        assertTrue(scores.size >= 30, "too many synthetic scenes rejected as plain")
        val passed = scores.count { it >= PhotoHash.DEFAULT_THRESHOLD }
        assertTrue(passed >= scores.size - 1, "only $passed of ${scores.size} matched; lowest ${scores.sorted().take(3)}")
    }

    @Test
    fun `different scenes do not match`() {
        val falsePasses = (1..20).sumOf { refSeed ->
            val ref = reference(Scene(refSeed)) ?: return@sumOf 0
            (1000 until 1030).count { PhotoHash.matches(ref, retake(Scene(it)), PhotoHash.DEFAULT_THRESHOLD) }
        }
        // Measured when the threshold was set (2026-10-22): re-shot scenes scored 0.80-0.93 (median
        // 0.86); unrelated pairs had median 0.61, and about 2% reached 0.75. Lenient on purpose (SPEC
        // §6.3), and random snapping gains nothing because give-up appears after three misses.
        assertTrue(falsePasses <= 18, "$falsePasses of about 600 unrelated pairs matched (more than 3%)")
    }

    @Test
    fun `a plain reference is refused and a malformed one never matches`() {
        assertEquals(null, PhotoHash.signature(IntArray(PhotoHash.GRID * PhotoHash.GRID) { 128 }))
        assertEquals(0f, PhotoHash.similarity("", retake(Scene(1))))
    }

    @Test
    fun `steps count from the first reading and never go negative`() {
        var p = StepProgress()
        assertEquals(0, p.walked)
        p = p.record(10_000)
        assertEquals(0, p.walked)
        p = p.record(10_012)
        assertEquals(12, p.walked)
        assertFalse(p.done(20))
        assertTrue(p.record(10_020).done(20))
        assertEquals(0, p.record(5).walked) // counter reset by a reboot mid-challenge
    }

    @Test
    fun `memory sequences are seeded, sized and checked step by step`() {
        val s = MemorySequence.generate(42, 5)
        assertEquals(s, MemorySequence.generate(42, 5))
        assertEquals(5, s.size)
        assertTrue(s.all { it in 0 until MemorySequence.PADS })
        assertEquals(MemorySequence.MIN_LENGTH, MemorySequence.generate(1, 1).size)
        assertTrue(MemorySequence.onTrack(s, s.take(3)))
        assertFalse(MemorySequence.onTrack(s, s.take(2) + ((s[2] + 1) % MemorySequence.PADS)))
    }

}
