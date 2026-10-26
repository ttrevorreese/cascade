package com.cascade.alarm.engine.sim

import com.cascade.alarm.data.SnoozeState
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.Difficulty
import com.cascade.alarm.data.db.EventType
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Ninety days of a randomised user on the real engine, then checks that hold whatever they did:
 * every alarm that should have rung rang; nothing rang that should not; each ring had the right
 * place, rung and snooze offer. Seeds are fixed, so a failure always reproduces.
 */
@RunWith(RobolectricTestRunner::class)
class SoakTest {
    private val zone = ZoneId.of("America/New_York")
    private val start = LocalDateTime.of(2026, 10, 18, 22, 0)
    private val days = 90L
    private val ladder = listOf(ChallengeConfig.Math(Difficulty.EASY, 2), ChallengeConfig.TypePhrase("up"), ChallengeConfig.Math(Difficulty.HARD, 3))
    private val weekdays = DayOfWeek.entries.take(5).toSet()
    private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    private val work = listOf("06:00", "06:10", "06:20", "06:30")
    private val lazy = listOf("08:00", "08:15")

    @Test fun `ninety days, seed 1`() = soak(1)
    @Test fun `ninety days, seed 2`() = soak(2)
    @Test fun `ninety days, seed 3`() = soak(3)
    @Test fun `ninety days, seed 4, with reboots and process deaths`() = soak(4, trouble = true)
    @Test fun `ninety days, seed 5, with reboots and process deaths`() = soak(5, trouble = true)
    @Test fun `ninety days of one-shot alarms switched back on each evening, seed 6`() = soak(6, oneShot = true)
    @Test fun `ninety days of one-shot alarms switched back on each evening, seed 7, with trouble`() = soak(7, trouble = true, oneShot = true)

    /**
     * [oneShot]: the work alarms ring once and switch themselves off; the user switches them back
     * on at 21:00 before each weekday.
     */
    private fun soak(seed: Int, trouble: Boolean = false, oneShot: Boolean = false) = runTest(timeout = kotlin.time.Duration.parse("5m")) {
        val sim = EngineSim(this as TestScope, start, zone)
        val random = Random(seed)
        try {
            val (_, workIds) = sim.group(
                "Work", *work.toTypedArray(), days = if (oneShot) emptySet() else weekdays, ladder = ladder, wakeCheckMinutes = 3,
                snoozeMinutes = 5, snoozeLimit = 2, labels = listOf("W1", "W2", "W3", "W4"),
            )
            // Created on Sunday evening, the one-shot alarms are already set for Monday.
            sim.group("Lazy", *lazy.toTypedArray(), days = weekend, snoozeMinutes = 9, snoozeLimit = 3, labels = listOf("L1", "L2"))
            val end = start.plusDays(days).atZone(zone).toInstant()
            while (sim.now().isBefore(end)) {
                val ring = sim.ringing
                if (ring != null) {
                    // Somewhere between jumping up and sleeping through to the next alarm.
                    sim.advanceBy(Duration.ofSeconds(random.nextLong(5, 14 * 60)))
                    val still = sim.ringing ?: continue
                    val roll = random.nextInt(100)
                    when {
                        still.isWakeCheck -> if (roll < 80) sim.dismiss() else sim.advanceBy(Duration.ofMinutes(2))
                        roll < 55 -> sim.dismiss()
                        roll < 65 -> sim.giveUp()
                        roll < 90 && still.snooze is SnoozeState.Offer -> sim.snooze()
                        else -> Unit // sleeps on
                    }
                    continue
                }
                val next = sim.alarmManager.pending.values.minOrNull()?.takeIf { it.isBefore(end) } ?: end
                val evening = sim.local().toLocalDate().atTime(21, 0).let { if (it.isAfter(sim.local())) it else it.plusDays(1) }.atZone(zone).toInstant()
                if (oneShot && evening.isBefore(next)) {
                    sim.advanceTo(evening)
                    if (sim.local().toLocalDate().plusDays(1).dayOfWeek in weekdays) workIds.forEach { sim.repo.setAlarmEnabled(it, true) }
                    continue
                }
                if (trouble && random.nextInt(100) < 4 && Duration.between(sim.now(), next) > Duration.ofMinutes(3)) {
                    // A short power cut or the process being reclaimed, before the next alarm.
                    sim.advanceTo(sim.now().plus(Duration.between(sim.now(), next).dividedBy(2)))
                    if (random.nextBoolean()) sim.reboot(Duration.ofSeconds(random.nextLong(20, 90))) else sim.killProcess()
                    continue
                }
                sim.advanceTo(next)
            }
            check(sim, trouble)
        } finally {
            sim.close()
        }
    }

    private suspend fun check(sim: EngineSim, trouble: Boolean) {
        val events = sim.events()
        val fired = events.filter { it.type == EventType.FIRED && it.meta["scheduledFor"] != null }
        val firedAt = fired.map { it.label to Instant.parse(it.meta.getValue("scheduledFor")) }.toSet()

        // 1. Every alarm that was due rang.
        val expected = mutableSetOf<Pair<String, Instant>>()
        val first = start.toLocalDate().plusDays(1)
        for (d in 0 until days) {
            val date: LocalDate = first.plusDays(d)
            val (labels, times) = when (date.dayOfWeek) {
                in weekdays -> listOf("W1", "W2", "W3", "W4") to work
                else -> listOf("L1", "L2") to lazy
            }
            labels.zip(times).forEach { (label, t) -> expected += label to date.atTime(LocalTime.parse(t)).atZone(zone).toInstant() }
        }
        val missing = expected - firedAt
        assertTrue("did not ring: ${missing.sortedBy { it.second }.take(5)}", missing.isEmpty())

        // 2. Nothing rang that was not due: each fire is a due alarm, a snooze it asked for, or its wake-up check.
        val snoozedUntil = events.filter { it.type == EventType.SNOOZED }.map { it.label to Instant.parse(it.meta.getValue("until")) }.toSet()
        val strays = fired.filter { e ->
            val key = e.label to Instant.parse(e.meta.getValue("scheduledFor"))
            key !in expected && key !in snoozedUntil && e.meta["wakeCheck"] != "true"
        }
        assertTrue("rang without being due: ${strays.take(5)}", strays.isEmpty())

        // 3. Each ring of a due alarm had its place in the sequence, its rung and the right snooze offer.
        val position = mapOf("W1" to 0, "W2" to 1, "W3" to 2, "W4" to 3, "L1" to 0, "L2" to 1)
        for (ring in sim.rings.filter { !it.wakeCheck }) {
            val label = checkNotNull(ring.label)
            val total = if (label.startsWith("W")) 4 else 2
            assertEquals("$label at ${ring.at} position", position.getValue(label), ring.position)
            assertEquals("$label at ${ring.at} total", total, ring.total)
            if (label.startsWith("W")) assertEquals("$label rung", ladder[minOf(ring.position, ladder.lastIndex)], ring.challenge)
            if (ring.position == total - 1) assertEquals("$label last", SnoozeState.NotOnLast, ring.snooze)
        }

        // 4. A wake-up check only ever follows the group's last alarm.
        assertTrue(sim.rings.filter { it.wakeCheck }.all { it.label == "W4" || it.label == "L2" })

        // 5. Snoozes never exceeded the limit within one morning's ring of an alarm.
        assertTrue(sim.rings.all { (it.snooze as? SnoozeState.Offer)?.left?.let { left -> left in 1..3 } ?: true })

        // Sanity: the randomised user did a bit of everything.
        val kinds = events.map { it.type }.toSet()
        assertTrue(kinds.containsAll(listOf(EventType.DISMISSED, EventType.SNOOZED, EventType.GAVE_UP, EventType.WAKE_CHECK_PASSED)))
        if (trouble) assertTrue("rebooted ${sim.reboots}×, killed ${sim.processDeaths}×", sim.reboots > 0 && sim.processDeaths > 0)
    }
}
