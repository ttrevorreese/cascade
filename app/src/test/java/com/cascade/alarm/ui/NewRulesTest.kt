package com.cascade.alarm.ui

import com.cascade.alarm.data.SystemTestAlarm
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.engine.bedtime.BedtimeRules
import com.cascade.alarm.ui.components.TimeEntryRules
import com.cascade.alarm.ui.group.NextMorningText
import com.cascade.alarm.widget.WidgetState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** The pure parts of the 2026-10-26 additions: time typing, "rings in", skip words, bedtime, widget. */
class NewRulesTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant()
    private fun group(id: String = "g", armed: Boolean = true) = AlarmGroupEntity(id, "Morning", "#000000", "default", armed, 0, Instant.EPOCH)
    private fun alarm(id: String, next: Instant?, groupId: String = "g") = AlarmEntity(
        id = id, groupId = groupId, positionInGroup = 0, hour = 6, minute = 0, daysOfWeek = emptySet(),
        label = null, isEnabled = true, timeoutMinutes = 15, nextTriggerAt = next,
    )

    // --- Typing a time ---

    @Test
    fun `typed times are read the way people type them`() {
        fun t(text: String, is24: Boolean = false, pm: Boolean = false) = TimeEntryRules.parse(text, is24, pm)
        assertEquals(LocalTime.of(6, 30), t("630"))
        assertEquals(LocalTime.of(6, 30), t("6:30"))
        assertEquals(LocalTime.of(6, 30), t("06.30"))
        assertEquals(LocalTime.of(18, 30), t("6:30", pm = true))
        assertEquals(LocalTime.of(18, 30), t("6:30 pm"))
        assertEquals(LocalTime.of(6, 30), t("6:30AM", pm = true))
        assertEquals(LocalTime.of(18, 30), t("18:30"))
        assertEquals(LocalTime.of(7, 0), t("7"))
        assertEquals(LocalTime.of(0, 15), t("12:15 am"))
        assertEquals(LocalTime.of(12, 15), t("12:15", pm = true))
        assertEquals(LocalTime.of(0, 5), t("0005", is24 = true))
        assertEquals(LocalTime.of(19, 0), t("7pm", is24 = true))
        assertEquals(LocalTime.of(7, 0), t("7", is24 = true))
    }

    @Test
    fun `what is not a time is refused`() {
        listOf("", "6:7", "6:75", "25:00", "abc", "12345", "6::30", "13pm", ":30").forEach {
            assertNull(TimeEntryRules.parse(it, false, false), it)
        }
    }

    @Test
    fun `the box shows the clock's own format and the steps wrap round midnight`() {
        assertEquals("6:05", TimeEntryRules.format(LocalTime.of(18, 5), false))
        assertEquals("12:00", TimeEntryRules.format(LocalTime.of(0, 0), false))
        assertEquals("18:05", TimeEntryRules.format(LocalTime.of(18, 5), true))
        assertEquals(LocalTime.of(0, 5), TimeEntryRules.step(LocalTime.of(23, 55), 10))
        assertEquals(LocalTime.of(23, 59), TimeEntryRules.step(LocalTime.MIDNIGHT, -1))
        assertEquals(listOf(-10, -5, -1, 1, 5, 10), TimeEntryRules.STEPS)
    }

    // --- Rings in ---

    @Test
    fun `rings in rounds up and names the day and time`() {
        assertEquals("in 1 min", RingsInText.until(Duration.ofSeconds(59)))
        assertEquals("in 20 min", RingsInText.until(Duration.ofMinutes(20)))
        assertEquals("in 7 h 20 min", RingsInText.until(Duration.ofMinutes(440)))
        assertEquals("in 8 h", RingsInText.until(Duration.ofHours(8)))
        assertEquals("in 1 day 2 h", RingsInText.until(Duration.ofHours(26)))
        assertEquals("in 3 days", RingsInText.until(Duration.ofDays(3)))
        assertEquals("in less than a minute", RingsInText.until(Duration.ZERO))
        assertEquals("Morning rings in 8 h, Mon 6:00 AM", RingsInText.message("Morning", at(19, 6, 0), at(18, 22, 0), false, zone))
        assertEquals("Nothing in Morning will ring", RingsInText.message("Morning", null, at(18, 22, 0), false, zone))
    }

    // --- Next morning words ---

    @Test
    fun `the group screen says what changed and when it rings`() {
        val now = at(18, 22, 0)
        val plain = NextMorningText.status(group(), at(19, 6, 0), now, false, zone)
        assertEquals(NextMorningText.Status("Next ring Mon 6:00 AM.", false), plain)
        val skipped = group().copy(skipUntil = LocalDateTime.of(2026, 10, 19, 6, 20, 1))
        assertEquals("Skipped. Next ring Tue 6:00 AM.", NextMorningText.status(skipped, at(20, 6, 0), now, false, zone).text)
        val paused = group().copy(skipUntil = LocalDateTime.of(2026, 10, 22, 0, 0))
        assertEquals("Paused until Thu 22 Oct. Next ring Thu 6:00 AM.", NextMorningText.status(paused, at(22, 6, 0), now, false, zone).text)
        val moved = group().copy(onceFrom = LocalDateTime.of(2026, 10, 19, 6, 0), onceShiftMinutes = 90)
        assertEquals("Next time only: starts Mon 7:30 AM instead of 6:00 AM.", NextMorningText.status(moved, at(19, 7, 30), now, false, zone).text)
        assertEquals("Next moved", NextMorningText.cardWord(moved, now, zone))
        // Over is over.
        assertNull(NextMorningText.cardWord(skipped, at(19, 7, 0), zone))
    }

    // --- Bedtime ---

    @Test
    fun `bedtime counts only regular rings of groups that are on`() {
        val snoozed = alarm("s", at(19, 5, 0)).copy(oneTimeTriggerAt = at(19, 5, 0))
        val regular = alarm("r", at(19, 6, 0))
        val off = alarm("o", at(19, 4, 0), groupId = "off")
        val test = alarm("t", at(19, 3, 0), groupId = SystemTestAlarm.GROUP_ID)
        val groups = listOf(group(), group("off", armed = false), group(SystemTestAlarm.GROUP_ID))
        assertEquals(at(19, 6, 0), BedtimeRules.nextRing(groups, listOf(snoozed, regular, off, test))?.second)
        assertEquals(at(18, 22, 0), BedtimeRules.remindAt(at(19, 6, 0), 480, at(18, 21, 0)))
        assertNull(BedtimeRules.remindAt(at(19, 6, 0), 480, at(18, 23, 0)))
        assertEquals("7½ h", BedtimeRules.sleepName(450))
        assertEquals(360, BedtimeRules.clampSleep(10))
    }

    // --- Widget ---

    @Test
    fun `the widget shows the next ring and offers skip only for a regular one`() {
        val groups = listOf(group())
        val regular = WidgetState.of(groups, listOf(alarm("a", at(19, 6, 0)), alarm("b", at(19, 6, 10))))!!
        assertEquals(at(19, 6, 0), regular.at)
        assertTrue(regular.skippable)
        val snooze = WidgetState.of(groups, listOf(alarm("a", at(19, 6, 5)).copy(oneTimeTriggerAt = at(19, 6, 5))))!!
        assertFalse(snooze.skippable)
        assertNull(WidgetState.of(listOf(group(armed = false)), listOf(alarm("a", at(19, 6, 0)))))
    }
}
