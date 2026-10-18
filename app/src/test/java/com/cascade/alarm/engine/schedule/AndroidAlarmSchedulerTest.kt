package com.cascade.alarm.engine.schedule

import android.app.AlarmManager
import android.app.Application
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.cascade.alarm.engine.receivers.AlarmFireReceiver
import com.cascade.alarm.engine.ring.AlarmRingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class AndroidAlarmSchedulerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val scheduler = AndroidAlarmScheduler(context, alarmManager)
    private val at = Instant.parse("2026-10-19T11:00:00Z")

    @Test
    fun `schedules an alarm clock that targets the fire receiver`() {
        scheduler.schedule("a", at)

        val clock = alarmManager.nextAlarmClock
        assertNotNull("setAlarmClock was used", clock)
        assertEquals(at.toEpochMilli(), clock.triggerTime)

        val scheduled = shadowOf(alarmManager).peekNextScheduledAlarm()!!
        val intent = shadowOf(scheduled.operation).savedIntent
        assertEquals(AlarmFireReceiver::class.java.name, intent.component?.className)
        assertEquals("a", intent.getStringExtra(AlarmFireReceiver.EXTRA_ALARM_ID))
        assertEquals(at.toEpochMilli(), intent.getLongExtra(AlarmFireReceiver.EXTRA_SCHEDULED_FOR, 0))
    }

    @Test
    fun `rescheduling replaces rather than adds`() {
        scheduler.schedule("a", at)
        scheduler.schedule("a", at.plusSeconds(86_400))
        assertEquals(1, shadowOf(alarmManager).scheduledAlarms.size)
        assertEquals(at.plusSeconds(86_400).toEpochMilli(), alarmManager.nextAlarmClock.triggerTime)
    }

    @Test
    fun `alarms are independent per id and cancel removes only one`() {
        scheduler.schedule("a", at)
        scheduler.schedule("b", at.plusSeconds(60))
        scheduler.cancel("a")
        val remaining = shadowOf(alarmManager).scheduledAlarms
        assertEquals(1, remaining.size)
        assertEquals("b", shadowOf(remaining.single().operation).savedIntent.getStringExtra(AlarmFireReceiver.EXTRA_ALARM_ID))
    }

    @Test
    fun `fire receiver holds a wake lock and starts the ring service in the foreground`() {
        val intent = shadowOf(AlarmFireReceiver.pendingIntent(context, "a", at)).savedIntent
        AlarmFireReceiver().onReceive(context, intent)

        val started = shadowOf(context).nextStartedService
        assertEquals(AlarmRingService::class.java.name, started.component?.className)
        assertEquals(AlarmRingService.ACTION_FIRE, started.action)
        val lock: PowerManager.WakeLock? = ShadowPowerManager.getLatestWakeLock()
        assertTrue("handoff wake lock held until the service takes over", lock?.isHeld == true)
    }

    @Test
    fun `fire receiver ignores unrelated intents`() {
        AlarmFireReceiver().onReceive(context, android.content.Intent("something.else"))
        assertNull(shadowOf(context).nextStartedService)
    }
}
