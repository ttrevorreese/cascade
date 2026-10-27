package com.cascade.alarm.ui.group

import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.engine.schedule.GroupTiming
import com.cascade.alarm.ui.TimeText
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What a group's skip, pause or one-off move means, in words (2026-10-26). */
object NextMorningText {
    data class Status(val text: String, val changed: Boolean)

    enum class Change { NONE, SKIPPED, PAUSED, MOVED }

    /** What is in effect at [now]; one that is over counts as none. */
    fun change(group: AlarmGroupEntity, now: Instant, zone: ZoneId): Change {
        val timing = GroupTiming.of(group).cleared(now, zone)
        return when {
            timing.onceFrom != null -> Change.MOVED
            timing.skipUntil == null -> Change.NONE
            // A pause runs to the start of a day; a skip ends just after the skipped morning.
            timing.skipUntil.toLocalTime() == LocalTime.MIDNIGHT -> Change.PAUSED
            else -> Change.SKIPPED
        }
    }

    /** [next] is the group's next ring as scheduled. */
    fun status(group: AlarmGroupEntity, next: Instant?, now: Instant, is24Hour: Boolean, zone: ZoneId): Status {
        val nextText = next?.let { "Next ring ${TimeText.nextRing(it, is24Hour, zone)}." } ?: "Nothing is due."
        return when (change(group, now, zone)) {
            Change.NONE -> Status(nextText, false)
            Change.SKIPPED -> Status("Skipped. $nextText", true)
            Change.PAUSED -> {
                val day = group.skipUntil!!.format(DateTimeFormatter.ofPattern("EEE d MMM"))
                Status("Paused until $day. $nextText", true)
            }
            Change.MOVED -> {
                val from = group.onceFrom!!
                val moved = from.plusMinutes(group.onceShiftMinutes.toLong()).atZone(zone).toInstant()
                val usual = from.toLocalTime()
                Status(
                    "Next time only: starts ${TimeText.nextRing(moved, is24Hour, zone)} instead of ${TimeText.time(usual.hour, usual.minute, is24Hour)}.",
                    true,
                )
            }
        }
    }

    /** The word Home's card puts first, or null. */
    fun cardWord(group: AlarmGroupEntity, now: Instant, zone: ZoneId): String? = when (change(group, now, zone)) {
        Change.NONE -> null
        Change.SKIPPED -> "Next skipped"
        Change.PAUSED -> "Paused"
        Change.MOVED -> "Next moved"
    }
}
