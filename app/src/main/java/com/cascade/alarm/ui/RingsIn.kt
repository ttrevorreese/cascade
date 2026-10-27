package com.cascade.alarm.ui

import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.engine.TimeSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Morning rings in 7 h 20 min, Tue 6:00" after a change that moves a group's next ring (owner
 * request, 2026-10-26), like the Clock app. Spelling out the day and time catches AM/PM slips.
 */
object RingsInText {
    fun message(groupName: String, next: Instant?, now: Instant, is24Hour: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        next ?: return "Nothing in $groupName will ring"
        return "$groupName rings ${until(Duration.between(now, next))}, ${TimeText.nextRing(next, is24Hour, zone)}"
    }

    /** "in 20 min", "in 7 h 20 min", "in 2 days 3 h"; rounded up, so 59 s is "in 1 min". */
    fun until(duration: Duration): String {
        val minutes = (duration.seconds + 59).coerceAtLeast(0) / 60
        if (minutes < 1) return "in less than a minute"
        val days = minutes / (24 * 60)
        val hours = minutes / 60 % 24
        val rest = minutes % 60
        return when {
            days > 0 -> "in $days ${if (days == 1L) "day" else "days"}" + if (hours > 0) " $hours h" else ""
            hours > 0 -> "in $hours h" + if (rest > 0) " $rest min" else ""
            else -> "in $rest min"
        }
    }
}

@Singleton
class RingsInNotice @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AlarmRepository,
    private val time: TimeSource,
) {
    /** Shows when [groupId] rings next, as saved. Never throws: it is only a message. */
    suspend fun show(groupId: String) {
        val text = try {
            val summary = repository.groupSummary(groupId) ?: return
            RingsInText.message(summary.group.name, summary.nextRing, time.now(), DateFormat.is24HourFormat(context), time.zone())
        } catch (e: Exception) {
            return
        }
        withContext(Dispatchers.Main) { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
