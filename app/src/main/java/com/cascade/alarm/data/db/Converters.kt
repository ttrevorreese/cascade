package com.cascade.alarm.data.db

import androidx.room.TypeConverter
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.EscalationPolicy
import com.cascade.alarm.data.sound.SoundConfig
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.util.EnumSet

class Converters {
    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    /** Wall-clock times (a group's skip or one-off move) as ISO text, "2026-10-19T06:10:01". */
    @TypeConverter
    fun localDateTimeToText(value: LocalDateTime?): String? = value?.toString()

    /** Never throws: unreadable text means no skip, so the group rings as normal. */
    @TypeConverter
    fun textToLocalDateTime(value: String?): LocalDateTime? = value?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    /** Bit (n-1) set for ISO day n (Monday = bit 0). */
    @TypeConverter
    fun daysToMask(days: Set<DayOfWeek>): Int = days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }

    @TypeConverter
    fun challengeToJson(value: ChallengeConfig?): String? = value?.let(ChallengeConfig::encode)

    @TypeConverter
    fun jsonToChallenge(value: String?): ChallengeConfig? = value?.let(ChallengeConfig::decode)

    @TypeConverter
    fun policyToJson(value: EscalationPolicy): String = EscalationPolicy.encode(value)

    @TypeConverter
    fun jsonToPolicy(value: String): EscalationPolicy = EscalationPolicy.decode(value)

    @TypeConverter
    fun soundToJson(value: SoundConfig): String = SoundConfig.encode(value)

    @TypeConverter
    fun jsonToSound(value: String): SoundConfig = SoundConfig.decode(value)

    @TypeConverter
    fun maskToDays(mask: Int): Set<DayOfWeek> =
        DayOfWeek.entries.filterTo(EnumSet.noneOf(DayOfWeek::class.java)) { mask and (1 shl (it.value - 1)) != 0 }
}
