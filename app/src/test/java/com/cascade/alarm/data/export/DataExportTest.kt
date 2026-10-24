package com.cascade.alarm.data.export

import com.cascade.alarm.data.SystemTestAlarm
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmEventEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.data.db.EventType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant

class DataExportTest {
    private val at = Instant.parse("2026-10-24T08:00:00Z")
    private val group = AlarmGroupEntity("g", "Workday", "#000000", "default", true, 0, Instant.EPOCH)
    private val alarm = AlarmEntity(
        id = "a", groupId = "g", positionInGroup = 0, hour = 6, minute = 5, daysOfWeek = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY),
        label = "Pills", isEnabled = true, volumeRampSeconds = 30, timeoutMinutes = 15,
        challengeOverride = ChallengeConfig.Math(), readLabelAloud = true,
    )

    @Test
    fun `groups hold their alarms in order, with settings readable as JSON, and the test group is left out`() {
        val testGroup = group.copy(id = SystemTestAlarm.GROUP_ID, name = "Test")
        val events = listOf(AlarmEventEntity(alarmId = "a", type = EventType.DISMISSED, timestamp = at, metadata = """{"challenge":"Math"}"""))
        val root = Json.parseToJsonElement(DataExport.toJson(listOf(group, testGroup), listOf(alarm), events, at)).jsonObject

        assertEquals("Cascade", root["app"]!!.jsonPrimitive.content)
        val groups = root["groups"]!!.jsonArray
        assertEquals(1, groups.size)
        val exported = groups[0].jsonObject["alarms"]!!.jsonArray[0].jsonObject
        assertEquals("06:05", exported["time"]!!.jsonPrimitive.content)
        assertEquals(listOf("MONDAY", "FRIDAY"), exported["days"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", exported["readLabelAloud"]!!.jsonPrimitive.content)
        // Stored JSON is nested as an object, not an escaped string.
        exported["challenge"]!!.jsonObject
        val event = root["events"]!!.jsonArray[0].jsonObject
        assertEquals("DISMISSED", event["type"]!!.jsonPrimitive.content)
        assertEquals("Math", event["details"]!!.jsonObject["challenge"]!!.jsonPrimitive.content)
    }
}
