package com.cascade.alarm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.cascade.alarm.data.db.CascadeDatabase
import com.cascade.alarm.engine.EventLog
import com.cascade.alarm.engine.TimeSource
import com.cascade.alarm.engine.schedule.AlarmScheduler
import com.cascade.alarm.engine.schedule.Reconciler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class AlarmRepositoryTest {
    private class FakeTime(var now: Instant) : TimeSource {
        override fun now() = now
        override fun zone(): ZoneId = ZoneId.of("America/New_York")
    }

    private class FakeScheduler : AlarmScheduler {
        val scheduled = mutableMapOf<String, Instant>()
        override fun schedule(alarmId: String, at: Instant) {
            scheduled[alarmId] = at
        }
        override fun cancel(alarmId: String) {
            scheduled.remove(alarmId)
        }
    }

    private val now = Instant.parse("2026-10-19T09:00:00Z") // 05:00 EDT, a Monday
    private lateinit var db: CascadeDatabase
    private lateinit var scheduler: FakeScheduler
    private lateinit var time: FakeTime
    private lateinit var repo: AlarmRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CascadeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scheduler = FakeScheduler()
        time = FakeTime(now)
        repo = AlarmRepository(db, Reconciler(db, scheduler, time, EventLog(db.alarmEventDao(), time)), time)
    }

    @After
    fun tearDown() = db.close()

    /** Creates a group with alarms at the given times (06:00, 06:10, ...). Returns (groupId, alarmIds). */
    private fun sequence(name: String, vararg times: Pair<Int, Int>) = runBlocking {
        val groupId = repo.createGroup(name)
        val ids = times.map { (h, m) ->
            val draft = repo.newAlarmDraft(groupId).copy(hour = h, minute = m, daysOfWeek = DayOfWeek.entries.toSet())
            repo.saveAlarm(draft)
            draft.id
        }
        groupId to ids
    }

    private fun alarms(groupId: String) = runBlocking { db.alarmDao().inGroup(groupId) }

    @Test
    fun `saving alarms in an armed group schedules them`() {
        val (_, ids) = sequence("Workday", 6 to 0, 6 to 10)
        assertEquals(Instant.parse("2026-10-19T10:00:00Z"), scheduler.scheduled[ids[0]])
        assertEquals(Instant.parse("2026-10-19T10:10:00Z"), scheduler.scheduled[ids[1]])
    }

    @Test
    fun `disarming a group cancels its alarms without changing their own switches`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0, 6 to 10)
        repo.setGroupArmed(groupId, false)
        assertTrue(ids.none { it in scheduler.scheduled })
        assertTrue(alarms(groupId).all { it.isEnabled })
    }

    @Test
    fun `re-arming restores each alarm's own state`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0, 6 to 10)
        repo.setAlarmEnabled(ids[1], false)
        repo.setGroupArmed(groupId, false)
        repo.setGroupArmed(groupId, true)
        assertTrue(ids[0] in scheduler.scheduled)
        assertFalse("individually disabled alarm stays off", ids[1] in scheduler.scheduled)
    }

    @Test
    fun `groups are independent`() = runBlocking {
        val (workday, workIds) = sequence("Workday", 6 to 0)
        val (_, gymIds) = sequence("Gym", 5 to 0)
        repo.setGroupArmed(workday, false)
        assertFalse(workIds[0] in scheduler.scheduled)
        assertTrue(gymIds[0] in scheduler.scheduled)
    }

    @Test
    fun `deleting an alarm cancels it and closes the gap in positions`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0, 6 to 10, 6 to 20)
        repo.deleteAlarm(ids[1])
        assertFalse(ids[1] in scheduler.scheduled)
        assertEquals(listOf(ids[0] to 0, ids[2] to 1), alarms(groupId).map { it.id to it.positionInGroup })
    }

    @Test
    fun `deleting a group deletes and cancels all its alarms`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0, 6 to 10)
        repo.deleteGroup(groupId)
        assertTrue(ids.none { it in scheduler.scheduled })
        assertTrue(alarms(groupId).isEmpty())
        assertNull(db.alarmGroupDao().get(groupId))
    }

    @Test
    fun `moving an alarm reorders positions`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0, 6 to 10, 6 to 20)
        repo.moveAlarm(ids[2], -2)
        assertEquals(listOf(ids[2], ids[0], ids[1]), alarms(groupId).map { it.id })
        assertEquals(listOf(0, 1, 2), alarms(groupId).map { it.positionInGroup })
    }

    @Test
    fun `moving a group reorders the home list`() = runBlocking {
        val (a, _) = sequence("A")
        val (b, _) = sequence("B")
        repo.moveGroup(b, -1)
        repo.observeGroupSummaries().test {
            assertEquals(listOf(b, a), awaitItem().map { it.group.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `new alarms continue the sequence`() = runBlocking {
        val (groupId, _) = sequence("Workday", 6 to 0, 6 to 15)
        val draft = repo.newAlarmDraft(groupId)
        assertEquals(6 to 25, draft.hour to draft.minute)
        assertEquals(2, draft.positionInGroup)
    }

    @Test
    fun `saving an existing alarm keeps engine bookkeeping and clamps input`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0)
        val fired = Instant.parse("2026-10-18T10:00:00Z")
        db.alarmDao().upsert(alarms(groupId).single().copy(lastFiredFor = fired))
        repo.saveAlarm(alarms(groupId).single().copy(hour = 7, label = "  Up  ", volumeRampSeconds = 999, positionInGroup = 9))
        val saved = repo.alarm(ids[0])!!
        assertEquals(fired, saved.lastFiredFor)
        assertEquals("Up", saved.label)
        assertEquals(120, saved.volumeRampSeconds)
        assertEquals(0, saved.positionInGroup) // position only changes via moveAlarm
        assertEquals(Instant.parse("2026-10-19T11:00:00Z"), scheduler.scheduled[ids[0]])
    }

    @Test
    fun `turning an alarm off cancels a pending one-time ring`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0)
        db.alarmDao().upsert(alarms(groupId).single().copy(oneTimeTriggerAt = now.plusSeconds(60)))
        repo.setAlarmEnabled(ids[0], false)
        assertNull(repo.alarm(ids[0])!!.oneTimeTriggerAt)
        assertFalse(ids[0] in scheduler.scheduled)
    }

    @Test
    fun `summaries show next ring and counts and hide the test alarm`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 10, 6 to 0)
        repo.setAlarmEnabled(ids[0], false)
        repo.ringTestAlarmIn(60)
        repo.observeGroupSummaries().test {
            val summary = awaitItem().single()
            assertEquals(groupId, summary.group.id)
            assertEquals(2, summary.alarmCount)
            assertEquals(1, summary.enabledCount)
            assertEquals(Instant.parse("2026-10-19T10:00:00Z"), summary.nextRing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `test ring schedules the hidden alarm even though its regular schedule is off`() = runBlocking {
        repo.ringTestAlarmIn(60)
        assertEquals(now.plusSeconds(60), scheduler.scheduled[SystemTestAlarm.ALARM_ID])
        assertFalse(repo.alarm(SystemTestAlarm.ALARM_ID)!!.isEnabled)
    }

    @Test
    fun `blank group names get a placeholder`() = runBlocking {
        val id = repo.createGroup("   ")
        assertEquals("Untitled group", db.alarmGroupDao().get(id)!!.name)
    }
}
