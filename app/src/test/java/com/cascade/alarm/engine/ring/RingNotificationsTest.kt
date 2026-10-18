package com.cascade.alarm.engine.ring

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class RingNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ringing = RingingAlarm("a", "Wake", Instant.EPOCH, Instant.EPOCH.plusSeconds(900))

    @Test
    fun `ringing notification can launch the full-screen alarm`() {
        val n = RingNotifications(context).build(ringing)
        assertNotNull(n.fullScreenIntent)
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        // Found on the Android 16 emulator: a silent notification is non-alerting, and the system
        // never launches the full-screen intent of a non-alerting notification.
        assertEquals("must not be FLAG_SILENT", 0, n.flags and FLAG_SILENT)
        assertEquals(Notification.GROUP_ALERT_ALL, n.groupAlertBehavior)
        assertNull("must not be grouped (group alert rules can suppress it)", n.group)
    }

    @Test
    fun `channel is high importance but makes no sound of its own`() {
        RingNotifications(context).build(ringing)
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(RingNotifications.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull("the service plays the alarm itself", channel.sound)
        assertTrue(!channel.shouldVibrate())
    }

    private companion object {
        const val FLAG_SILENT = 0x00008000 // Notification.FLAG_SILENT (hidden API)
    }
}
