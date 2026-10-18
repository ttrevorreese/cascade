package com.cascade.alarm

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cascade.alarm.data.SystemTestAlarm
import com.cascade.alarm.data.db.CascadeDatabase
import com.cascade.alarm.engine.EngineEntryPoint
import com.cascade.alarm.engine.receivers.AlarmFireReceiver
import com.cascade.alarm.engine.ring.AlarmRingService
import com.cascade.alarm.engine.schedule.AndroidAlarmScheduler
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * Runs on a real device: `./gradlew connectedDebugAndroidTest`. The fire-path test plays the
 * alarm sound for a moment.
 */
@RunWith(AndroidJUnit4::class)
class EngineInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine = EntryPointAccessors.fromApplication(context, EngineEntryPoint::class.java)

    @Test
    fun databaseLivesInDeviceProtectedStorage() {
        val db = CascadeDatabase.create(context)
        try {
            val path = File(db.openHelper.writableDatabase.path!!).canonicalPath
            val deviceProtectedDir = context.createDeviceProtectedStorageContext().dataDir.canonicalPath
            assertTrue("$path should be under $deviceProtectedDir", path.startsWith(deviceProtectedDir))
        } finally {
            db.close()
        }
    }

    @Test
    fun setAlarmClockIsRegisteredWithTheSystem() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val scheduler = AndroidAlarmScheduler(context, alarmManager)
        // Soon enough to be the device's next alarm clock, far enough not to fire during the test.
        val at = Instant.now().plusSeconds(90)
        try {
            scheduler.schedule("instrumented-test", at)
            assertEquals(at.toEpochMilli(), alarmManager.nextAlarmClock?.triggerTime)
        } finally {
            scheduler.cancel("instrumented-test")
        }
    }

    @Test
    fun firePathRingsAndDismissStopsIt() = runBlocking {
        val ringState = engine.ringState()
        engine.alarmRepository().editTestAlarm("instrumented_test") { it } // ensures the row exists
        val fire: Intent = Intent(context, AlarmFireReceiver::class.java)
            .setAction(AlarmFireReceiver.ACTION_FIRE)
            .putExtra(AlarmFireReceiver.EXTRA_ALARM_ID, SystemTestAlarm.ALARM_ID)
            .putExtra(AlarmFireReceiver.EXTRA_SCHEDULED_FOR, System.currentTimeMillis())
        context.sendBroadcast(fire)

        val ringing = withTimeout(10_000) { ringState.current.first { it != null } }
        assertEquals(SystemTestAlarm.ALARM_ID, ringing?.alarmId)
        assertTrue(engine.activeRingDao().get() != null)

        context.startService(AlarmRingService.dismissIntent(context))
        withTimeout(10_000) { ringState.current.first { it == null } }
        assertEquals(null, engine.activeRingDao().get())
    }
}
