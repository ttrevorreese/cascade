package com.cascade.alarm.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class AlarmEventDaoTest {
    private lateinit var db: CascadeDatabase
    private lateinit var dao: AlarmEventDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CascadeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.alarmEventDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun recentEventsAreNewestFirstAndRoundTripInstants() = runTest {
        val t0 = Instant.parse("2026-10-18T07:00:00.123Z")
        dao.insert(AlarmEventEntity(alarmId = "a", type = EventType.FIRED, timestamp = t0))
        dao.insert(AlarmEventEntity(alarmId = "a", type = EventType.DISMISSED, timestamp = t0.plusSeconds(30)))

        dao.observeRecent(10).test {
            val events = awaitItem()
            assertEquals(listOf(EventType.DISMISSED, EventType.FIRED), events.map { it.type })
            assertEquals(t0, events.last().timestamp)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
