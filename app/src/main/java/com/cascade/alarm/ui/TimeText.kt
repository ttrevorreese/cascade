package com.cascade.alarm.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Time strings that follow the device's 12/24-hour setting. */
object TimeText {
    private fun pattern(is24Hour: Boolean) = if (is24Hour) "HH:mm" else "h:mm a"

    fun time(hour: Int, minute: Int, is24Hour: Boolean): String =
        LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern(is24Hour)))

    fun nextRing(instant: Instant, is24Hour: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
        instant.atZone(zone).format(DateTimeFormatter.ofPattern("EEE " + pattern(is24Hour)))

    /** "Today", "Tomorrow", "Yesterday", or the weekday. */
    fun day(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String =
        when (val date = instant.atZone(zone).toLocalDate()) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(DateTimeFormatter.ofPattern("EEEE"))
        }

    /** The day as it reads mid-sentence: "yesterday", "on Tuesday". */
    fun dayInSentence(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        day(instant, zone).let { if (it in setOf("Today", "Tomorrow", "Yesterday")) it.lowercase() else "on $it" }
}

@Composable
fun is24HourClock(): Boolean = DateFormat.is24HourFormat(LocalContext.current)

/** The UI locale, read so that a language change recomposes. */
@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]
