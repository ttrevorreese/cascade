package com.cascade.alarm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.cascade.alarm.data.db.CascadeDatabase
import com.cascade.alarm.data.sound.SoundConfig
import com.cascade.alarm.data.sound.SoundFiles
import com.cascade.alarm.data.sound.SoundSettings
import com.cascade.alarm.engine.ring.RingSound
import com.cascade.alarm.engine.EventLog
import com.cascade.alarm.engine.TimeSource
import com.cascade.alarm.engine.schedule.AlarmScheduler
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.Difficulty
import com.cascade.alarm.data.challenge.EscalationPolicy
import com.cascade.alarm.engine.ring.RoomRingStore
import com.cascade.alarm.engine.schedule.Reconciler
import com.cascade.alarm.engine.schedule.Reconciler.FireResult
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
    private lateinit var reconciler: Reconciler

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CascadeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scheduler = FakeScheduler()
        time = FakeTime(now)
        reconciler = Reconciler(db, scheduler, time, EventLog(db.alarmEventDao(), time))
        repo = AlarmRepository(db, reconciler, time, SoundFiles(ApplicationProvider.getApplicationContext()))
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
    fun `duplicating a group copies its alarms and settings, placed after it and off`() = runBlocking {
        val (a, ids) = sequence("Workday", 6 to 0, 6 to 10)
        val (b, _) = sequence("Gym")
        repo.setGroupSound(a, SoundConfig.BuiltIn("klaxon"), 45, 15)
        db.alarmDao().upsert(repo.alarm(ids[1])!!.copy(label = "Up", snoozesUsed = 2))
        val copyId = repo.duplicateGroup(a)!!
        val copy = db.alarmGroupDao().get(copyId)!!
        assertEquals("Workday copy", copy.name)
        assertFalse("a copy never rings alongside the original by surprise", copy.isArmed)
        assertEquals(SoundConfig.BuiltIn("klaxon"), copy.sound)
        repo.observeGroupSummaries().test {
            assertEquals(listOf(a, copyId, b), awaitItem().map { it.group.id })
            cancelAndIgnoreRemainingEvents()
        }
        val copied = alarms(copyId)
        assertEquals(listOf(6 to 0, 6 to 10), copied.map { it.hour to it.minute })
        assertEquals("Up", copied[1].label)
        assertEquals(0, copied[1].snoozesUsed)
        assertTrue(copied.none { it.id in ids || it.id in scheduler.scheduled })
        assertEquals(2, alarms(a).size)
        assertEquals("Workday copy 2", db.alarmGroupDao().get(repo.duplicateGroup(copyId)!!)!!.name)
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
    fun `a new time re-sorts the group into ring order`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0, 6 to 10, 6 to 20)
        repo.saveAlarm(repo.alarm(ids[1])!!.copy(minute = 30))
        assertEquals(listOf(ids[0], ids[2], ids[1]), alarms(groupId).map { it.id })
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
    fun `onboarding's starter group is made with its alarms and armed`() = runBlocking {
        val plan = StarterPlan("Mornings", java.time.LocalTime.of(6, 0), count = 3, stepMinutes = 15, days = StarterPlan.EVERY_DAY)
        val groupId = repo.createStarterGroup(plan, NewAlarmDefaults(snoozeEnabled = false))
        val made = alarms(groupId).sortedBy { it.positionInGroup }
        assertEquals(listOf(6 to 0, 6 to 15, 6 to 30), made.map { it.hour to it.minute })
        assertEquals(listOf(0, 1, 2), made.map { it.positionInGroup })
        assertEquals(false, made.first().snoozeEnabled)
        assertEquals(Instant.parse("2026-10-19T10:00:00Z"), scheduler.scheduled[made[0].id])
    }

    @Test
    fun `switching read-aloud on an existing alarm is saved`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0)
        repo.saveAlarm(alarms(groupId).single().copy(readLabelAloud = true))
        assertEquals(true, repo.alarm(ids[0])!!.readLabelAloud)
        repo.saveAlarm(alarms(groupId).single().copy(readLabelAloud = false))
        assertEquals(false, repo.alarm(ids[0])!!.readLabelAloud)
    }

    @Test
    fun `saving an existing alarm keeps engine bookkeeping and clamps input`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0)
        val fired = Instant.parse("2026-10-18T10:00:00Z")
        db.alarmDao().upsert(alarms(groupId).single().copy(lastFiredFor = fired))
        repo.saveAlarm(alarms(groupId).single().copy(hour = 7, label = "  Up  ", timeoutMinutes = 999, positionInGroup = 9))
        val saved = repo.alarm(ids[0])!!
        assertEquals(fired, saved.lastFiredFor)
        assertEquals("Up", saved.label)
        assertEquals(60, saved.timeoutMinutes)
        assertEquals(0, saved.positionInGroup) // position comes from the time, never from input
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

    private fun times(groupId: String) = alarms(groupId).map { it.hour to it.minute }

    @Test
    fun `changing the first alarm's time moves the rest of the group by the same amount`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0, 6 to 10, 6 to 25)
        repo.setAlarmEnabled(ids[2], false)
        repo.saveAlarm(repo.alarm(ids[0])!!.copy(hour = 6, minute = 15))
        assertEquals(listOf(6 to 15, 6 to 25, 6 to 40), times(groupId))
        assertEquals(Instant.parse("2026-10-19T10:25:00Z"), scheduler.scheduled[ids[1]])
        // Disabled alarms move too, so the spacing holds when they are switched back on.
        assertFalse(repo.alarm(ids[2])!!.isEnabled)
    }

    @Test
    fun `changing a later alarm moves only that alarm`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0, 6 to 10, 6 to 25)
        repo.saveAlarm(repo.alarm(ids[1])!!.copy(minute = 5))
        assertEquals(listOf(6 to 0, 6 to 5, 6 to 25), times(groupId))
    }

    @Test
    fun `the first alarm moves alone when the group's shift setting is off`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0, 6 to 10)
        assertTrue(db.alarmGroupDao().get(groupId)!!.shiftWithFirst)
        repo.setShiftWithFirst(groupId, false)
        repo.saveAlarm(repo.alarm(ids[0])!!.copy(minute = 30))
        // Alone, and now after the other one: it becomes the second alarm.
        assertEquals(listOf(6 to 10, 6 to 30), times(groupId))
        assertEquals(1, repo.alarm(ids[0])!!.positionInGroup)
    }

    @Test
    fun `editing the first alarm without changing its time leaves the rest alone`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0, 6 to 10)
        repo.saveAlarm(repo.alarm(ids[0])!!.copy(label = "Up"))
        assertEquals(listOf(6 to 0, 6 to 10), times(groupId))
    }

    @Test
    fun `followers that cross midnight take their days with them`() = runBlocking {
        val (groupId, ids) = sequence("Late", 23 to 40, 23 to 55)
        db.alarmDao().upsert(repo.alarm(ids[1])!!.copy(daysOfWeek = setOf(DayOfWeek.MONDAY)))
        repo.saveAlarm(repo.alarm(ids[0])!!.copy(minute = 50))
        val follower = repo.alarm(ids[1])!!
        assertEquals(0 to 5, follower.hour to follower.minute)
        assertEquals(setOf(DayOfWeek.TUESDAY), follower.daysOfWeek)
    }

    @Test
    fun `shifting a group moves every alarm and reschedules`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0, 6 to 10)
        repo.shiftGroup(groupId, 5)
        assertEquals(listOf(6 to 5, 6 to 15), times(groupId))
        assertEquals(Instant.parse("2026-10-19T10:05:00Z"), scheduler.scheduled[ids[0]])
        repo.shiftGroup(groupId, -10)
        assertEquals(listOf(5 to 55, 6 to 5), times(groupId))
    }

    @Test
    fun `dismissing the last alarm schedules a wake-up check that fires as one`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0, 6 to 10)
        repo.setWakeCheck(groupId, enabled = true, delayMinutes = 2)
        val sixTen = Instant.parse("2026-10-19T10:10:00Z")
        time.now = sixTen
        reconciler.recordFire(ids[1], sixTen)
        time.now = sixTen.plusSeconds(30)

        reconciler.scheduleWakeCheck(ids[0]) // not the last alarm: nothing
        assertNull(repo.alarm(ids[0])!!.wakeCheckAt)
        reconciler.scheduleWakeCheck(ids[1])
        val check = sixTen.plusSeconds(150)
        assertEquals(check, repo.alarm(ids[1])!!.wakeCheckAt)
        assertEquals(check, scheduler.scheduled[ids[1]])

        time.now = check
        assertEquals(true, (reconciler.recordFire(ids[1], check) as FireResult.Ring).wakeCheck)
        assertNull(repo.alarm(ids[1])!!.wakeCheckAt)
        assertEquals(Instant.parse("2026-10-20T10:10:00Z"), scheduler.scheduled[ids[1]])
    }

    @Test
    fun `no wake-up check unless the group turns it on`() = runBlocking {
        val (_, ids) = sequence("Workday", 6 to 0)
        reconciler.scheduleWakeCheck(ids[0])
        assertNull(repo.alarm(ids[0])!!.wakeCheckAt)
    }

    @Test
    fun `turning the wake-up check off cancels a pending one`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0)
        repo.setWakeCheck(groupId, enabled = true, delayMinutes = 9)
        assertEquals(5, db.alarmGroupDao().get(groupId)!!.wakeCheckDelayMinutes)
        reconciler.scheduleWakeCheck(ids[0])
        assertEquals(now.plusSeconds(300), scheduler.scheduled[ids[0]])
        repo.setWakeCheck(groupId, enabled = false, delayMinutes = 5)
        assertNull(repo.alarm(ids[0])!!.wakeCheckAt)
        assertEquals(Instant.parse("2026-10-19T10:00:00Z"), scheduler.scheduled[ids[0]])
    }

    @Test
    fun `turning the alarm off cancels its pending wake-up check`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0)
        repo.setWakeCheck(groupId, enabled = true, delayMinutes = 3)
        reconciler.scheduleWakeCheck(ids[0])
        repo.setAlarmEnabled(ids[0], false)
        assertNull(repo.alarm(ids[0])!!.wakeCheckAt)
        assertFalse(ids[0] in scheduler.scheduled)
    }

    @Test
    fun `a saved ladder drops rungs that cannot work and the ring resolves from it`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0, 6 to 10, 6 to 20)
        repo.setEscalationPolicy(
            groupId,
            EscalationPolicy(listOf(ChallengeConfig.Math(Difficulty.EASY, 50), ChallengeConfig.BarcodeScan("", ""), ChallengeConfig.TypePhrase(" up "))),
        )
        val saved = db.alarmGroupDao().get(groupId)!!.escalationPolicy
        assertEquals(EscalationPolicy(listOf(ChallengeConfig.Math(Difficulty.EASY, 10), ChallengeConfig.TypePhrase("up"))), saved)

        val store = RoomRingStore(reconciler, db.alarmDao(), db.alarmGroupDao(), db.activeRingDao(), EventLog(db.alarmEventDao(), time))
        assertEquals(ChallengeConfig.Math(Difficulty.EASY, 10), store.challengeFor(repo.alarm(ids[0])!!))
        assertEquals(ChallengeConfig.TypePhrase("up"), store.challengeFor(repo.alarm(ids[2])!!)) // the last rung repeats
        // Switching off the middle alarm moves the last one down a rung.
        repo.setAlarmEnabled(ids[1], false)
        assertEquals(ChallengeConfig.TypePhrase("up"), store.challengeFor(repo.alarm(ids[2])!!))
        repo.setAlarmEnabled(ids[1], true)
        // An alarm's own None opts out of the ladder.
        repo.saveAlarm(repo.alarm(ids[0])!!.copy(challengeOverride = ChallengeConfig.None))
        assertEquals(null, store.challengeFor(repo.alarm(ids[0])!!))
    }

    @Test
    fun `blank group names get a placeholder`() = runBlocking {
        val id = repo.createGroup("   ")
        assertEquals("Untitled group", db.alarmGroupDao().get(id)!!.name)
    }

    // --- Sound (M6) ---

    private val soundDir get() = java.io.File(
        ApplicationProvider.getApplicationContext<android.content.Context>().createDeviceProtectedStorageContext().filesDir,
        SoundFiles.DIR_NAME,
    )

    private fun copiedFile(name: String) = java.io.File(soundDir.apply { mkdirs() }, name).apply { writeText("audio") }

    @Test
    fun `a group's sound is saved within range and the ring reads it`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0)
        repo.setGroupSound(groupId, SoundConfig.BuiltIn("klaxon"), rampSeconds = 500, startPercent = 0)
        val store = RoomRingStore(reconciler, db.alarmDao(), db.alarmGroupDao(), db.activeRingDao(), EventLog(db.alarmEventDao(), time))
        assertEquals(RingSound(SoundConfig.BuiltIn("klaxon"), 60, 5), store.soundFor(repo.alarm(ids[0])!!))
    }

    @Test
    fun `a new group plays Classic, reaching full volume after 45 s from 15 percent`() = runBlocking {
        val id = repo.createGroup("Workday")
        assertEquals(RingSound(SoundConfig.DEFAULT, 45, 15), RingSound.of(db.alarmGroupDao().get(id)!!))
    }

    @Test
    fun `a row written without the sound columns reads as the defaults`() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO alarm_groups (id, name, colorHex, iconKey, isArmed, sortOrder, createdAt) VALUES ('old', 'Old', '#000000', 'default', 1, 0, 0)",
        )
        val group = db.alarmGroupDao().get("old")!!
        assertEquals(RingSound(SoundConfig.DEFAULT, SoundSettings.DEFAULT_RAMP_SECONDS, SoundSettings.DEFAULT_START_PERCENT), RingSound.of(group))
    }

    @Test
    fun `replacing or deleting a group's file deletes the copy, but not one another group plays`() = runBlocking {
        val (a, _) = sequence("A", 6 to 0)
        val (b, _) = sequence("B", 7 to 0)
        copiedFile("first.mp3"); copiedFile("abandoned.mp3")
        repo.setGroupSound(a, SoundConfig.DeviceAudio("first.mp3", "First"), 45, 15)
        assertFalse("a picked file that was never saved is cleared", java.io.File(soundDir, "abandoned.mp3").exists())
        assertTrue(java.io.File(soundDir, "first.mp3").exists())
        copiedFile("shared.mp3")
        repo.setGroupSound(b, SoundConfig.DeviceAudio("shared.mp3", "Shared"), 45, 15)
        assertTrue(java.io.File(soundDir, "first.mp3").exists())

        repo.setGroupSound(a, SoundConfig.BuiltIn("dawn"), 45, 15)
        assertFalse(java.io.File(soundDir, "first.mp3").exists())
        assertTrue(java.io.File(soundDir, "shared.mp3").exists())

        repo.deleteGroup(b)
        assertFalse(java.io.File(soundDir, "shared.mp3").exists())
    }

    @Test
    fun `a Spotify group's backup file is kept when another group is deleted`() = runBlocking {
        val (a, _) = sequence("A", 6 to 0)
        val (b, _) = sequence("B", 7 to 0)
        copiedFile("backup.mp3")
        val spotify = SoundConfig.SpotifyPlaylist("spotify:playlist:x", "Playlist", SoundConfig.DeviceAudio("backup.mp3", "Backup"))
        repo.setGroupSound(a, spotify, 45, 15)
        repo.deleteGroup(b)
        assertTrue(java.io.File(soundDir, "backup.mp3").exists())
    }

    @Test
    fun `copied files are only found inside the sounds folder`() {
        val files = SoundFiles(ApplicationProvider.getApplicationContext())
        copiedFile("song.mp3")
        assertEquals("song.mp3", files.file("song.mp3")?.name)
        assertNull(files.file("missing.mp3"))
        assertNull(files.file("../databases/cascade.db"))
        assertEquals(".mp3", SoundFiles.extension("Morning Song.MP3"))
        assertEquals("", SoundFiles.extension("no extension"))
        assertEquals("", SoundFiles.extension("weird.m p3"))
    }

    @Test
    fun `a snoozed one-shot alarm rings again although it switched itself off`() = runBlocking {
        val groupId = repo.createGroup("Once")
        val draft = repo.newAlarmDraft(groupId).copy(hour = 6, minute = 0, daysOfWeek = emptySet())
        repo.saveAlarm(draft)
        val due = repo.alarm(draft.id)!!.nextTriggerAt!!
        reconciler.recordFire(draft.id, due)
        assertFalse("a one-shot switches itself off as it rings", repo.alarm(draft.id)!!.isEnabled)
        val at = due.plusSeconds(300)
        reconciler.snooze(draft.id, at)
        assertEquals(at, scheduler.scheduled[draft.id])
        assertEquals(1, repo.alarm(draft.id)!!.snoozesUsed)
        reconciler.clearSnoozes(draft.id)
        assertEquals(0, repo.alarm(draft.id)!!.snoozesUsed)
    }

    @Test
    fun `new alarms take the last alarm's snooze and saving keeps it in range`() = runBlocking {
        val (groupId, ids) = sequence("Workday", 6 to 0)
        repo.saveAlarm(repo.alarm(ids[0])!!.copy(snoozeMinutes = 90, snoozeLimit = 9, snoozeEnabled = false))
        val saved = repo.alarm(ids[0])!!
        assertEquals(30 to 5, saved.snoozeMinutes to saved.snoozeLimit)
        val next = repo.newAlarmDraft(groupId)
        assertEquals(false, next.snoozeEnabled)
        assertEquals(30, next.snoozeMinutes)
    }
}
