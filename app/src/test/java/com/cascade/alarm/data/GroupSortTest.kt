package com.cascade.alarm.data

import com.cascade.alarm.data.db.AlarmGroupEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime

class GroupSortTest {
    private fun summary(id: String, order: Int, first: LocalTime?, updated: Long) = GroupSummary(
        AlarmGroupEntity(id, id, "#000000", "default", true, order, Instant.EPOCH, updatedAt = Instant.ofEpochSecond(updated)),
        alarmCount = if (first == null) 0 else 1,
        enabledCount = 0,
        nextRing = null,
        firstTime = first,
    )

    private val gym = summary("gym", 0, LocalTime.of(17, 30), updated = 300)
    private val work = summary("work", 1, LocalTime.of(6, 0), updated = 100)
    private val empty = summary("empty", 2, null, updated = 200)
    private val weekend = summary("weekend", 3, LocalTime.of(8, 15), updated = 100)
    private val all = listOf(weekend, empty, gym, work)

    private fun List<GroupSummary>.ids() = map { it.group.id }

    @Test
    fun `your order is the order set with the arrows`() {
        assertEquals(listOf("gym", "work", "empty", "weekend"), GroupSort.CUSTOM.sort(all).ids())
    }

    @Test
    fun `earliest first goes by the first alarm, with empty groups last`() {
        assertEquals(listOf("work", "weekend", "gym", "empty"), GroupSort.TIME.sort(all).ids())
    }

    @Test
    fun `recently changed puts the latest edit first and ties keep your order`() {
        assertEquals(listOf("gym", "empty", "work", "weekend"), GroupSort.MODIFIED.sort(all).ids())
    }

    @Test
    fun `an unknown saved choice reads as your order`() {
        assertEquals(GroupSort.CUSTOM, GroupSort.byKey("alphabetical"))
        assertEquals(GroupSort.MODIFIED, GroupSort.byKey("modified"))
    }
}
