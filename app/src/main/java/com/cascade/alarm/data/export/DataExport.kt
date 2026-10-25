package com.cascade.alarm.data.export

import android.content.ContentResolver
import android.net.Uri
import com.cascade.alarm.data.SystemTestAlarm
import com.cascade.alarm.data.challenge.ChallengeConfig
import com.cascade.alarm.data.challenge.EscalationPolicy
import com.cascade.alarm.data.db.AlarmEntity
import com.cascade.alarm.data.db.AlarmEventEntity
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.data.db.CascadeDatabase
import com.cascade.alarm.data.sound.SoundConfig
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.engine.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject

/**
 * Everything Cascade knows, as one JSON file the user saves where they like (SPEC §7 Settings,
 * "data export"). Nothing is sent anywhere. Reference photos stay out: they are pictures of the
 * user's home, and the file names the photo each alarm asks for.
 */
object DataExport {
    const val FORMAT_VERSION = 1
    private val pretty = Json { prettyPrint = true }

    fun toJson(groups: List<AlarmGroupEntity>, alarms: List<AlarmEntity>, events: List<AlarmEventEntity>, exportedAt: Instant): String {
        val byGroup = alarms.groupBy { it.groupId }
        val root = buildJsonObject {
            put("app", "Cascade")
            put("format", FORMAT_VERSION)
            put("exportedAt", exportedAt.toString())
            put(
                "groups",
                JsonArray(
                    groups.filter { it.id != SystemTestAlarm.GROUP_ID }.sortedBy { it.sortOrder }.map { group ->
                        group(group, byGroup[group.id].orEmpty().sortedBy { it.positionInGroup })
                    },
                ),
            )
            put(
                "events",
                buildJsonArray {
                    events.sortedBy { it.timestamp }.forEach { event ->
                        add(
                            buildJsonObject {
                                put("at", event.timestamp.toString())
                                put("alarmId", event.alarmId)
                                put("type", event.type.name)
                                put("details", event.metadata?.let(::parsed) ?: JsonNull)
                            },
                        )
                    }
                },
            )
        }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    private fun group(group: AlarmGroupEntity, alarms: List<AlarmEntity>) = buildJsonObject {
        put("id", group.id)
        put("name", group.name)
        put("on", group.isArmed)
        put("shiftWithFirst", group.shiftWithFirst)
        put("wakeCheck", group.wakeCheckEnabled)
        put("wakeCheckDelayMinutes", group.wakeCheckDelayMinutes)
        put("escalation", parsed(EscalationPolicy.encode(group.escalationPolicy)))
        put("sound", parsed(SoundConfig.encode(group.sound)))
        put("rampSeconds", group.rampSeconds)
        put("rampStartPercent", group.rampStartPercent)
        put("theme", group.themeId)
        put("vibration", group.vibration)
        put("vibrationStrength", group.vibrationStrength)
        put("vibrationDelaySeconds", group.vibrationDelaySeconds)
        put("createdAt", group.createdAt.toString())
        put("alarms", JsonArray(alarms.map(::alarm)))
    }

    private fun alarm(alarm: AlarmEntity) = buildJsonObject {
        put("id", alarm.id)
        put("position", alarm.positionInGroup + 1)
        put("time", "%02d:%02d".format(alarm.hour, alarm.minute))
        put("days", JsonArray(alarm.daysOfWeek.sorted().map { JsonPrimitive(it.name) }))
        put("label", alarm.label)
        put("on", alarm.isEnabled)
        put("readLabelAloud", alarm.readLabelAloud)
        put("timeoutMinutes", alarm.timeoutMinutes)
        put("snooze", alarm.snoozeEnabled)
        put("snoozeMinutes", alarm.snoozeMinutes)
        put("snoozeLimit", alarm.snoozeLimit)
        put("challenge", alarm.challengeOverride?.let { parsed(ChallengeConfig.encode(it)) } ?: JsonNull)
    }

    /** Stored JSON nested as JSON; anything unreadable is kept as the original text. */
    private fun parsed(raw: String): JsonElement = try {
        Json.parseToJsonElement(raw)
    } catch (e: Exception) {
        JsonPrimitive(raw)
    }
}

/** Writes the export to a document the user picked with the system file picker. */
class DataExporter @Inject constructor(
    private val db: CascadeDatabase,
    private val time: TimeSource,
) {
    suspend fun export(resolver: ContentResolver, target: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = DataExport.toJson(db.alarmGroupDao().all(), db.alarmDao().all(), db.alarmEventDao().since(Instant.EPOCH), time.now())
            resolver.openOutputStream(target, "wt")?.use { it.write(json.toByteArray()) } != null
        } catch (e: Exception) {
            EngineLog.e("export failed", e)
            false
        }
    }
}
