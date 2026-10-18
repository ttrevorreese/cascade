package com.cascade.alarm.engine.schedule

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.CascadeDatabase
import com.cascade.alarm.data.db.EventType
import com.cascade.alarm.engine.EventLog
import com.cascade.alarm.engine.TimeSource
import com.cascade.alarm.engine.schedule.Reconciler.FireResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class ReconcilerTest {
    private class FakeTime(var now: Instant, var zone: ZoneId) : TimeSource {
        override fun now() = now
        override fun zone() = zone
    }

    private class FakeScheduler : AlarmScheduler {
        val scheduled = mutableMapOf<String, Instant>()
        var scheduleCalls = 0
        override fun schedule(alarmId: String, at: Instant) {
            scheduled[alarmId] = at
            scheduleCalls++
        }
        override fun cancel(alarmId: String) {
            scheduled.remove(alarmId)
        }
    }

    private val sevenAm = Instant.parse("2026-10-19T11:00:00Z") // Monday 07:00 EDT
    private val sevenAmTomorrow = Instant.parse("2026-10-20T11:00:00Z")

    private lateinit var db: CascadeDatabase
    private lateinit var time: FakeTime
    private lateinit var scheduler: FakeScheduler
    private lateinit var reconciler: Reconciler

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CascadeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        time = FakeTime(sevenAm.minus(Duration.ofHours(8)), ZoneId.of("America/New_York"))
        scheduler = FakeScheduler()
        reconciler = Reconciler(db, scheduler, time, EventLog(db.alarmEventDao(), time))
    }

    @After
    fun tearDown() = db.close()

    private fun insert(days: Set<DayOfWeek> = DayOfWeek.entries.toSet(), enabled: Boolean = true) = runBlocking {
        db.alarmDao().upsert(
            AlarmEntity(
                id = "a", hour = 7, minute = 0, daysOfWeek = days, label = null, isEnabled = enabled,
                volumeRampSeconds = 30, timeoutMinutes = 15,
            ),
        )
    }

    private fun alarm() = runBlocking { db.alarmDao().get("a")!! }
    private fun events() = runBlocking { db.alarmEventDao().forAlarm("a").map { it.type } }

    @Test
    fun `reconcile schedules enabled alarms and records SCHEDULED once`() = runBlocking {
        insert()
        reconciler.reconcileAll("test")
        reconciler.reconcileAll("test")
        assertEquals(sevenAm, scheduler.scheduled["a"])
        assertEquals(sevenAm, alarm().nextTriggerAt)
        assertEquals(2, scheduler.scheduleCalls) // AlarmManager is re-armed every pass...
        assertEquals(listOf(EventType.SCHEDULED), events()) // ...but the log isn't spammed
    }

    @Test
    fun `reboot shortly after a missed trigger rings it immediately`() = runBlocking {
        insert()
        reconciler.reconcileAll("app_start")
        time.now = sevenAm.plus(Duration.ofMinutes(12)) // phone was off from 06:55 to 07:12
        reconciler.reconcileAll("LOCKED_BOOT_COMPLETED")
        assertEquals(sevenAm, scheduler.scheduled["a"])
        assertFalse(events().contains(EventType.MISSED))
    }

    @Test
    fun `reboot long after a missed trigger logs MISSED and moves on`() = runBlocking {
        insert()
        reconciler.reconcileAll("app_start")
        time.now = sevenAm.plus(Duration.ofMinutes(40))
        reconciler.reconcileAll("LOCKED_BOOT_COMPLETED")
        reconciler.reconcileAll("BOOT_COMPLETED")
        assertEquals(sevenAmTomorrow, scheduler.scheduled["a"])
        assertEquals(1, events().count { it == EventType.MISSED })
    }

    @Test
    fun `fire records FIRED, schedules the next trigger and ignores a duplicate`() = runBlocking {
        insert()
        reconciler.reconcileAll("app_start")
        time.now = sevenAm.plusMillis(300)
        val first = reconciler.recordFire("a", sevenAm)
        val second = reconciler.recordFire("a", sevenAm)
        assertTrue(first is FireResult.Ring && first.alarm?.id == "a")
        assertEquals(FireResult.Duplicate, second)
        assertEquals(sevenAmTomorrow, scheduler.scheduled["a"])
        assertEquals(1, events().count { it == EventType.FIRED })
        // A reconcile right after firing must not re-ring the trigger that just fired.
        reconciler.reconcileAll("app_start")
        assertEquals(sevenAmTomorrow, scheduler.scheduled["a"])
    }

    @Test
    fun `fire for an unknown alarm still rings`() = runBlocking {
        assertEquals(FireResult.Ring(null), reconciler.recordFire("ghost", sevenAm))
    }

    @Test
    fun `one-shot alarm disables itself after firing`() = runBlocking {
        insert(days = emptySet())
        reconciler.reconcileAll("app_start")
        time.now = sevenAm
        reconciler.recordFire("a", sevenAm)
        assertFalse(alarm().isEnabled)
        assertFalse(scheduler.scheduled.containsKey("a"))
    }

    @Test
    fun `timezone change moves the trigger to the new local time`() = runBlocking {
        insert()
        reconciler.reconcileAll("app_start")
        time.zone = ZoneId.of("America/Los_Angeles")
        reconciler.reconcileAll("TIMEZONE_CHANGED")
        assertEquals(Instant.parse("2026-10-19T14:00:00Z"), scheduler.scheduled["a"])
    }

    @Test
    fun `test ring in 60 seconds works while the regular alarm is off`() = runBlocking {
        insert(enabled = false)
        val inOneMinute = time.now.plusSeconds(60)
        reconciler.edit("a", "test_ring") { it.copy(oneTimeTriggerAt = inOneMinute) }
        assertEquals(inOneMinute, scheduler.scheduled["a"])
        time.now = inOneMinute
        reconciler.recordFire("a", inOneMinute)
        assertFalse(scheduler.scheduled.containsKey("a"))
    }
}
