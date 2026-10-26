package com.cascade.alarm.engine.ring

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import java.time.Duration

/**
 * AlarmManager holds a wake lock only while [android.content.BroadcastReceiver.onReceive] runs.
 * The fire receiver takes this one before starting the service and the service releases it once
 * its own lock is held, so the CPU cannot sleep in between.
 */
object WakeLocks {
    private val HANDOFF_TIMEOUT: Duration = Duration.ofSeconds(60)
    private var handoff: PowerManager.WakeLock? = null

    @Synchronized
    fun acquireHandoff(context: Context) {
        val lock = handoff ?: context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cascade:fire-handoff")
            .apply { setReferenceCounted(false) }
        lock.acquire(HANDOFF_TIMEOUT.toMillis())
        handoff = lock
    }

    @Synchronized
    fun releaseHandoff() {
        handoff?.takeIf { it.isHeld }?.release()
    }
}

/**
 * Turns the display on when a ring starts. On Android 16 the alarm activity's turnScreenOn flag
 * alone did not wake the device (verified on an API 36 emulator); Google's Clock wakes it with an
 * ACQUIRE_CAUSES_WAKEUP wake lock, which the system allows for alarm apps. Held briefly: once
 * AlarmActivity is showing, its FLAG_KEEP_SCREEN_ON keeps the display on.
 */
class ScreenWaker(context: Context) {
    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK has no non-deprecated equivalent for this.
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "cascade:screen-on",
        )
        .apply { setReferenceCounted(false) }

    fun wake() = lock.acquire(SCREEN_ON_HOLD.toMillis())

    fun release() {
        if (lock.isHeld) lock.release()
    }

    private companion object {
        val SCREEN_ON_HOLD: Duration = Duration.ofSeconds(30)
    }
}

/**
 * The ringing wake lock. Acquiring only ever extends it: a fire that arrives mid-ring (a duplicate,
 * a skipped wake-up check) takes the short initial hold, which must not cut a 60-minute ring's
 * lock down to that (fixed 2026-10-25).
 */
class AndroidRingWakeLock(context: Context) : RingWakeLock {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cascade:ringing")
        .apply { setReferenceCounted(false) }
    private var heldUntil = 0L

    @Synchronized
    override fun acquire(timeout: Duration) {
        val until = SystemClock.elapsedRealtime() + timeout.toMillis()
        if (lock.isHeld && until <= heldUntil) return
        lock.acquire(timeout.toMillis())
        heldUntil = until
    }

    @Synchronized
    override fun release() {
        heldUntil = 0L
        if (lock.isHeld) lock.release()
    }
}
