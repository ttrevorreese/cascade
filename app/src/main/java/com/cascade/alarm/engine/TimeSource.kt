package com.cascade.alarm.engine

import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

interface TimeSource {
    fun now(): Instant

    /** Read on every call: the device zone can change while the process is alive. */
    fun zone(): ZoneId
}

class SystemTimeSource @Inject constructor() : TimeSource {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}
