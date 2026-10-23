package com.cascade.alarm.data.challenge

import java.util.BitSet
import kotlin.math.abs

/**
 * Perceptual fingerprint for the photo-match challenge (SPEC §6.3). No object recognition: with
 * the overall lighting removed, the reference records, for each pair of neighbouring cells on a
 * small grayscale grid, which one is brighter, horizontally and vertically, and which of those pairs are clear edges. Matching
 * compares only the clear edges, so flat areas (a wall, a mirror) and changes in overall light
 * do not count, and the reference is searched for inside the new photo, so framing can be off.
 *
 * It cannot be turned back into a picture, which is why it can live in the device-protected
 * database and work before first unlock, while the photo itself does not (SPEC §6.8).
 */
object PhotoHash {
    /** The reference is a [GRID] x [GRID] sampling of the photo's central [REFERENCE_CROP]. */
    const val GRID = 17
    const val REFERENCE_CROP = 0.8f

    /** A new photo is sampled whole at [SEARCH] x [SEARCH], the reference's scale, to allow for framing. */
    const val SEARCH = 21

    private const val PAIRS = (GRID - 1) * GRID * 2

    /**
     * Same scene if at least this share of the reference's edges agree. Unrelated photos land
     * near 0.5. Generous on purpose: a false rejection at 6am is worse than an occasional
     * lenient pass (SPEC §6.3). See PhotoHashTest for the margins this was set against.
     */
    const val DEFAULT_THRESHOLD = 0.75f

    /** A reference with fewer clear edges than this is too plain to tell apart from anything. */
    private const val MIN_EDGES = PAIRS / 10

    /**
     * The fingerprint of a reference photo's [grid] ([GRID] x [GRID] brightness values, row by
     * row), or null if it is too plain to be a useful reference.
     */
    fun signature(grid: IntArray): String? {
        require(grid.size == GRID * GRID) { "expected a ${GRID}x$GRID grid" }
        val diffs = pairDiffs(highPass(grid, GRID), GRID, 0, 0)
        // "Clear" is relative to the photo's own contrast (the stronger half of its edges), but
        // never so low that sensor noise on a flat surface counts.
        val tolerance = maxOf(MIN_TOLERANCE, diffs.map(::abs).sorted()[diffs.size / 2])
        val brighter = BitSet(PAIRS)
        val clear = BitSet(PAIRS)
        diffs.forEachIndexed { i, d ->
            if (d > 0) brighter.set(i)
            if (abs(d) >= tolerance) clear.set(i)
        }
        if (clear.cardinality() < MIN_EDGES) return null
        return hex(brighter) + hex(clear)
    }

    /**
     * How well a new photo's [grid] ([SEARCH] x [SEARCH]) matches [signature], 0..1: the best
     * share of agreeing clear edges over every placement of the reference inside it.
     */
    fun similarity(signature: String, grid: IntArray): Float {
        require(grid.size == SEARCH * SEARCH) { "expected a ${SEARCH}x$SEARCH grid" }
        if (signature.length != PAIRS / 2) return 0f
        val brighter = unhex(signature.substring(0, PAIRS / 4))
        val clear = unhex(signature.substring(PAIRS / 4))
        val edges = clear.cardinality()
        if (edges == 0) return 0f
        val structure = highPass(grid, SEARCH)
        var best = 0f
        for (dy in 0..SEARCH - GRID) for (dx in 0..SEARCH - GRID) {
            val diffs = pairDiffs(structure, SEARCH, dx, dy)
            var agree = 0
            var i = clear.nextSetBit(0)
            while (i >= 0) {
                if ((diffs[i] > 0) == brighter[i]) agree++
                i = clear.nextSetBit(i + 1)
            }
            best = maxOf(best, agree.toFloat() / edges)
        }
        return best
    }

    fun matches(signature: String, grid: IntArray, threshold: Float): Boolean = similarity(signature, grid) >= threshold

    /** Horizontal then vertical neighbour differences of the [GRID] window at ([dx], [dy]) of a [width]-wide grid. */
    private fun pairDiffs(grid: IntArray, width: Int, dx: Int, dy: Int): IntArray {
        val out = IntArray(PAIRS)
        var k = 0
        fun at(x: Int, y: Int) = grid[(y + dy) * width + (x + dx)]
        for (y in 0 until GRID) for (x in 0 until GRID - 1) out[k++] = at(x, y) - at(x + 1, y)
        for (y in 0 until GRID - 1) for (x in 0 until GRID) out[k++] = at(x, y) - at(x, y + 1)
        return out
    }

    private const val MIN_TOLERANCE = 6
    private const val BLUR_RADIUS = 2

    /**
     * Each cell minus the average around it: removes overall light and slow gradients (a window
     * to one side, a lamp) so only the scene's own structure is compared.
     */
    private fun highPass(grid: IntArray, width: Int): IntArray = IntArray(grid.size) { i ->
        val (x, y) = i % width to i / width
        var sum = 0
        var count = 0
        for (ny in maxOf(0, y - BLUR_RADIUS)..minOf(width - 1, y + BLUR_RADIUS)) {
            for (nx in maxOf(0, x - BLUR_RADIUS)..minOf(width - 1, x + BLUR_RADIUS)) {
                sum += grid[ny * width + nx]
                count++
            }
        }
        grid[i] - sum / count
    }

    private fun hex(bits: BitSet): String =
        bits.toByteArray().copyOf(PAIRS / 8).joinToString("") { "%02x".format(it) }

    private fun unhex(text: String): BitSet =
        BitSet.valueOf(ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
}
