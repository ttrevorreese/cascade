package com.cascade.alarm.engine

import android.os.SystemClock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

interface TimeSource {
    fun now(): Instant

    /** Read on every call: the device zone can change while the process is alive. */
    fun zone(): ZoneId

    /** When the phone last started; tells "the phone was off" apart from "the app was stopped". */
    fun bootTime(): Instant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
}

class SystemTimeSource @Inject constructor() : TimeSource {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}
