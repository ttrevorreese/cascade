package com.cascade.alarm.data.db

import androidx.room.TypeConverter
import java.time.DayOfWeek
import java.time.Instant
import java.util.EnumSet

class Converters {
    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    /** Bit (n-1) set for ISO day n (Monday = bit 0). */
    @TypeConverter
    fun daysToMask(days: Set<DayOfWeek>): Int = days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }

    @TypeConverter
    fun maskToDays(mask: Int): Set<DayOfWeek> =
        DayOfWeek.entries.filterTo(EnumSet.noneOf(DayOfWeek::class.java)) { mask and (1 shl (it.value - 1)) != 0 }
}
