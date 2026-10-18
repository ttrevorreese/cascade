package com.cascade.alarm.engine.schedule

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Transition instants used below were read from the JDK tz database, e.g.
 * America/New_York 2026: gap 2026-03-08T02:00 EST→EDT, overlap 2026-11-01T02:00 EDT→EST.
 */
class NextOccurrenceTest {
    private val newYork = ZoneId.of("America/New_York")
    private val weekdays = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)

    private fun next(time: String, days: Set<DayOfWeek>, after: Instant, zone: ZoneId = newYork) =
        NextOccurrence.after(LocalTime.parse(time), days, after, zone)

    private fun local(dateTime: String, zone: ZoneId = newYork): Instant =
        LocalDateTime.parse(dateTime).atZone(zone).toInstant()

    private fun utc(text: String) = Instant.parse(text)

    @Nested
    inner class Basics {
        @Test
        fun `later today fires today`() {
            assertEquals(local("2026-10-18T07:00"), next("07:00", emptySet(), local("2026-10-18T06:00")))
        }

        @Test
        fun `already passed today fires tomorrow`() {
            assertEquals(local("2026-10-19T07:00"), next("07:00", emptySet(), local("2026-10-18T08:00")))
        }

        @Test
        fun `exactly at alarm time schedules the following day`() {
            // The engine computes the next occurrence right after firing; "now" must never be returned.
            assertEquals(local("2026-10-19T07:00"), next("07:00", emptySet(), local("2026-10-18T07:00")))
        }

        @Test
        fun `one millisecond before alarm time fires today`() {
            assertEquals(
                local("2026-10-18T07:00"),
                next("07:00", emptySet(), local("2026-10-18T07:00").minusMillis(1)),
            )
        }

        @Test
        fun `daily alarm with all seven days matches one-shot timing`() {
            val after = local("2026-10-18T23:59")
            assertEquals(next("06:30", emptySet(), after), next("06:30", DayOfWeek.entries.toSet(), after))
        }

        @Test
        fun `half-hour offset zone`() {
            val kolkata = ZoneId.of("Asia/Kolkata")
            assertEquals(utc("2026-10-19T01:30:00Z"), next("07:00", emptySet(), utc("2026-10-18T12:00:00Z"), kolkata))
        }
    }

    @Nested
    inner class WeekBoundaries {
        @Test
        fun `weekday alarm on Friday after it rang skips the weekend`() {
            // 2026-10-23 is a Friday.
            assertEquals(local("2026-10-26T07:00"), next("07:00", weekdays, local("2026-10-23T07:00")))
        }

        @Test
        fun `Sunday-only alarm seen from Monday is six days out`() {
            // ISO weeks start on Monday; Sunday must not be treated as day 0 of the same week.
            assertEquals(local("2026-10-25T09:00"), next("09:00", setOf(SUNDAY), local("2026-10-19T09:00")))
        }

        @Test
        fun `single weekday already passed today is exactly one week out`() {
            // 2026-10-18 is a Sunday.
            assertEquals(local("2026-10-25T07:00"), next("07:00", setOf(SUNDAY), local("2026-10-18T07:00:01")))
        }

        @Test
        fun `weekend alarm on Saturday night picks Sunday`() {
            assertEquals(
                local("2026-10-25T08:00"),
                next("08:00", setOf(SATURDAY, SUNDAY), local("2026-10-24T22:00")),
            )
        }

        @Test
        fun `year boundary`() {
            assertEquals(local("2027-01-01T00:15"), next("00:15", emptySet(), local("2026-12-31T23:30")))
        }

        @Test
        fun `leap day`() {
            assertEquals(local("2028-02-29T07:00"), next("07:00", emptySet(), local("2028-02-28T08:00")))
        }
    }

    @Nested
    inner class DaylightSaving {
        @Test
        fun `alarm inside the spring-forward gap fires when the gap ends`() {
            // 02:30 does not exist on 2026-03-08; clocks jump 02:00 EST -> 03:00 EDT (07:00Z).
            assertEquals(utc("2026-03-08T07:00:00Z"), next("02:30", emptySet(), local("2026-03-08T00:00")))
        }

        @Test
        fun `alarm after the gap on spring-forward day uses the new offset`() {
            assertEquals(utc("2026-03-08T11:00:00Z"), next("07:00", emptySet(), local("2026-03-08T00:00")))
        }

        @Test
        fun `weekly alarm keeps wall-clock time across spring-forward`() {
            // Adding 7x24h to 07:00 EST would give 08:00 EDT, which is the classic bug.
            val firedMondayBefore = utc("2026-03-02T12:00:00Z") // 07:00 EST
            assertEquals(utc("2026-03-09T11:00:00Z"), next("07:00", setOf(MONDAY), firedMondayBefore))
        }

        @Test
        fun `weekly alarm keeps wall-clock time across fall-back`() {
            val firedMondayBefore = utc("2026-10-26T11:00:00Z") // 07:00 EDT
            assertEquals(utc("2026-11-02T12:00:00Z"), next("07:00", setOf(MONDAY), firedMondayBefore))
        }

        @Test
        fun `alarm inside the fall-back overlap fires at the first occurrence`() {
            // 01:30 happens at 05:30Z (EDT) and again at 06:30Z (EST) on 2026-11-01.
            assertEquals(utc("2026-11-01T05:30:00Z"), next("01:30", emptySet(), local("2026-11-01T00:00")))
        }

        @Test
        fun `alarm inside the fall-back overlap does not fire twice`() {
            val firstRing = utc("2026-11-01T05:30:00Z")
            assertEquals(utc("2026-11-02T06:30:00Z"), next("01:30", emptySet(), firstRing))
        }

        @Test
        fun `computing between the two overlap occurrences skips to the next day`() {
            // e.g. phone rebooted at 01:45 EDT after the first 01:30 already rang
            assertEquals(utc("2026-11-02T06:30:00Z"), next("01:30", emptySet(), utc("2026-11-01T05:45:00Z")))
        }

        @Test
        fun `midnight gap fires at the start of the day`() {
            // America/Santiago: 2026-09-06 00:00 -04 -> 01:00 -03 (04:00Z).
            val santiago = ZoneId.of("America/Santiago")
            assertEquals(
                utc("2026-09-06T04:00:00Z"),
                next("00:30", emptySet(), local("2026-09-05T22:00", santiago), santiago),
            )
        }

        @Test
        fun `thirty-minute gap`() {
            // Australia/Lord_Howe: 2026-10-04 02:00 +10:30 -> 02:30 +11:00 (2026-10-03T15:30Z).
            val lordHowe = ZoneId.of("Australia/Lord_Howe")
            assertEquals(
                utc("2026-10-03T15:30:00Z"),
                next("02:15", emptySet(), local("2026-10-04T00:00", lordHowe), lordHowe),
            )
        }

        @Test
        fun `thirty-minute overlap fires once`() {
            // Australia/Lord_Howe: 2026-04-05 02:00 +11:00 -> 01:30 +10:30; 01:45 happens twice.
            val lordHowe = ZoneId.of("Australia/Lord_Howe")
            val first = next("01:45", emptySet(), local("2026-04-05T00:00", lordHowe), lordHowe)
            assertEquals(utc("2026-04-04T14:45:00Z"), first)
            assertEquals(utc("2026-04-05T15:15:00Z"), next("01:45", emptySet(), first, lordHowe))
        }

        @Test
        fun `a skipped calendar day is skipped rather than ringing on the following day`() {
            // Pacific/Apia skipped Friday 2011-12-30 entirely (-10:00 -> +14:00).
            val apia = ZoneId.of("Pacific/Apia")
            val thursdayMorning = local("2011-12-29T08:00", apia)
            assertEquals(utc("2011-12-30T17:00:00Z"), next("07:00", emptySet(), thursdayMorning, apia)) // Sat 31st
            assertEquals(utc("2012-01-05T17:00:00Z"), next("07:00", setOf(FRIDAY), thursdayMorning, apia))
        }
    }

    @Nested
    inner class TimezoneChanges {
        @Test
        fun `same instant in a different zone yields that zone's wall-clock time`() {
            val now = utc("2026-10-18T10:00:00Z") // 06:00 New York, 03:00 Los Angeles
            val losAngeles = ZoneId.of("America/Los_Angeles")
            assertEquals(utc("2026-10-18T11:00:00Z"), next("07:00", emptySet(), now, newYork))
            assertEquals(utc("2026-10-18T14:00:00Z"), next("07:00", emptySet(), now, losAngeles))
        }

        @Test
        fun `day of week is evaluated in the device's current zone`() {
            // 2026-10-19T05:00Z is Sunday 22:00 in Los Angeles but Monday 14:00 in Tokyo.
            val now = utc("2026-10-19T05:00:00Z")
            val losAngeles = ZoneId.of("America/Los_Angeles")
            val tokyo = ZoneId.of("Asia/Tokyo")
            assertEquals(local("2026-10-19T07:00", losAngeles), next("07:00", setOf(MONDAY), now, losAngeles))
            assertEquals(local("2026-10-26T07:00", tokyo), next("07:00", setOf(MONDAY), now, tokyo))
        }

        @Test
        fun `after flying west the next ring is the next local wall-clock time`() {
            // Rang 07:00 Monday in Tokyo (Sun 22:00Z); in Honolulu that instant is Sunday 12:00.
            val rangInTokyo = utc("2026-10-18T22:00:00Z")
            val honolulu = ZoneId.of("Pacific/Honolulu")
            assertEquals(utc("2026-10-19T17:00:00Z"), next("07:00", emptySet(), rangInTokyo, honolulu))
        }
    }
}
