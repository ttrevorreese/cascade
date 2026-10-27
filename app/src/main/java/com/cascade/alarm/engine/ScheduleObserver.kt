package com.cascade.alarm.engine

/**
 * Told after every change to the alarm schedule (the bedtime reminder and the widget follow it).
 * Must return at once and never throw: the ring path calls it.
 */
fun interface ScheduleObserver {
    fun scheduleChanged()

    companion object {
        val None = ScheduleObserver {}
    }
}
