package com.cascade.alarm.engine.ring

import android.content.Context
import android.os.PowerManager
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

class AndroidRingWakeLock(context: Context) : RingWakeLock {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cascade:ringing")
        .apply { setReferenceCounted(false) }

    override fun acquire(timeout: Duration) = lock.acquire(timeout.toMillis())

    override fun release() {
        if (lock.isHeld) lock.release()
    }
}
