package com.cascade.alarm.data.challenge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ChallengeRulesTest {
    private val at = Instant.parse("2026-10-22T10:00:00Z")

    @Test
    fun `the same ring always gets the same problems, a different ring different ones`() {
        val seed = challengeSeed("a", at)
        assertEquals(MathProblems.generate(seed, Difficulty.HARD, 5), MathProblems.generate(challengeSeed("a", at), Difficulty.HARD, 5))
        assertNotEquals(MathProblems.generate(seed, Difficulty.HARD, 5), MathProblems.generate(challengeSeed("a", at.plusSeconds(600)), Difficulty.HARD, 5))
    }

    @Test
    fun `each tier stays in its range and every answer is right`() {
        for (s in 0L until 300L) {
            MathProblems.generate(s, Difficulty.EASY, 3).forEach {
                assertTrue(it.answer in 2..18, it.text)
                assertEquals(it.answer, solve(it.text), it.text)
            }
            MathProblems.generate(s, Difficulty.MEDIUM, 3).forEach {
                assertTrue(it.answer >= 0, it.text)
                assertEquals(it.answer, solve(it.text), it.text)
            }
            MathProblems.generate(s, Difficulty.HARD, 3).forEach {
                assertTrue('×' in it.text && it.answer >= 0, it.text)
                assertEquals(it.answer, solve(it.text), it.text)
            }
        }
    }

    @Test
    fun `problem count is kept between 1 and 10`() {
        assertEquals(1, MathProblems.generate(1, Difficulty.EASY, 0).size)
        assertEquals(10, MathProblems.generate(1, Difficulty.EASY, 50).size)
    }

    /** Evaluates "a op b [op c]" with × binding tighter, to check the generator's answers independently. */
    private fun solve(text: String): Int {
        val t = text.split(" ")
        val first = if (t.getOrNull(1) == "×") t[0].toInt() * t[2].toInt() else null
        return if (first != null) {
            if (t.size == 3) first else if (t[3] == "+") first + t[4].toInt() else first - t[4].toInt()
        } else if (t[1] == "+") t[0].toInt() + t[2].toInt() else t[0].toInt() - t[2].toInt()
    }

    @Test
    fun `phrases match ignoring case and spacing`() {
        assertTrue(Phrases.matches("Kettle on the  window", "  kettle ON the window "))
        assertFalse(Phrases.matches("kettle on the window", "kettle on the windows"))
    }

    @Test
    fun `generated phrases are four words and repeatable`() {
        assertEquals(4, Phrases.generated(7).split(" ").size)
        assertEquals(Phrases.generated(7), Phrases.generated(7))
    }

    @Test
    fun `typing is accepted, pasting is not`() {
        assertTrue(Phrases.acceptEdit("kett", "kettl"))
        assertTrue(Phrases.acceptEdit("kettle", "kett")) // deleting is fine
        assertTrue(Phrases.acceptEdit("kettle", "kettle o")) // a keyboard may add a space too
        assertFalse(Phrases.acceptEdit("", "kettle on the window"))
    }

    @Test
    fun `barcodes compare exactly`() {
        assertTrue(Barcodes.matches("cascade:1234", "cascade:1234"))
        assertFalse(Barcodes.matches("cascade:1234", "CASCADE:1234"))
    }

    @Test
    fun `give up appears after three misses or three minutes`() {
        assertFalse(GiveUp.available(2, Duration.ofMinutes(2)))
        assertTrue(GiveUp.available(3, Duration.ZERO))
        assertTrue(GiveUp.available(0, Duration.ofMinutes(3)))
    }

    @Test
    fun `configs survive storage, and one this version cannot read becomes the fallback`() {
        val configs = listOf(
            ChallengeConfig.Math(Difficulty.HARD, 5),
            ChallengeConfig.TypePhrase("kettle on"),
            ChallengeConfig.BarcodeScan("cascade:1", "Bathroom mirror"),
            ChallengeConfig.Steps(40),
            ChallengeConfig.Memory(6),
        )
        configs.forEach { assertEquals(it, ChallengeConfig.decode(ChallengeConfig.encode(it))) }
        assertEquals(ChallengeConfig.FALLBACK, ChallengeConfig.decode("""{"type":"teleport"}"""))
        assertEquals(ChallengeConfig.FALLBACK, ChallengeConfig.decode("not json"))
    }
}
