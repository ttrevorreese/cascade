package com.cascade.alarm.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Runs on a device: the real SQLite matters (no window functions before API 30, etc.). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CascadeDatabase::class.java)

    private val db = "migration-test"

    @Test
    fun v1AlarmsMoveIntoAnArmedGroupOrderedByTimeAndKeepTheirState() {
        helper.createDatabase(db, 1).use { v1 ->
            // Inserted out of time order on purpose.
            v1.execSQL(
                "INSERT INTO alarms (id, hour, minute, daysOfWeek, label, isEnabled, volumeRampSeconds, " +
                    "timeoutMinutes, nextTriggerAt, oneTimeTriggerAt, lastFiredFor) VALUES " +
                    "('late', 7, 30, 31, 'Second', 1, 45, 10, 1000, NULL, 900), " +
                    "('early', 7, 0, 0, NULL, 0, 30, 15, NULL, 2000, NULL)",
            )
        }

        helper.runMigrationsAndValidate(db, 2, true, Migrations.MIGRATION_1_2).use { v2 ->
            v2.query("SELECT id, name, isArmed FROM alarm_groups").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals(Migrations.MIGRATED_GROUP_ID, c.getString(0))
                assertEquals("My alarms", c.getString(1))
                assertEquals(1, c.getInt(2))
            }
            v2.query(
                "SELECT id, groupId, positionInGroup, daysOfWeek, label, isEnabled, volumeRampSeconds, " +
                    "timeoutMinutes, nextTriggerAt, oneTimeTriggerAt, lastFiredFor FROM alarms ORDER BY positionInGroup",
            ).use { c ->
                assertEquals(2, c.count)
                c.moveToFirst()
                assertEquals("early", c.getString(0))
                assertEquals(Migrations.MIGRATED_GROUP_ID, c.getString(1))
                assertEquals(0, c.getInt(2))
                assertEquals(0, c.getInt(5))
                assertEquals(2000L, c.getLong(9))
                c.moveToNext()
                assertEquals("late", c.getString(0))
                assertEquals(1, c.getInt(2))
                assertEquals(31, c.getInt(3))
                assertEquals("Second", c.getString(4))
                assertEquals(1, c.getInt(5))
                assertEquals(45, c.getInt(6))
                assertEquals(10, c.getInt(7))
                assertEquals(1000L, c.getLong(8))
                assertEquals(900L, c.getLong(10))
            }
        }
    }

    @Test
    fun emptyV1DatabaseMigratesWithoutCreatingAGroup() {
        helper.createDatabase(db, 1).close()
        helper.runMigrationsAndValidate(db, 2, true, Migrations.MIGRATION_1_2).use { v2 ->
            v2.query("SELECT COUNT(*) FROM alarm_groups").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun v2GroupsGetShiftWithFirstTurnedOn() {
        helper.createDatabase(db, 2).use { v2 ->
            v2.execSQL(
                "INSERT INTO alarm_groups (id, name, colorHex, iconKey, isArmed, sortOrder, createdAt) " +
                    "VALUES ('g', 'Workday', '#000000', 'default', 1, 0, 0)",
            )
        }
        helper.runMigrationsAndValidate(db, 3, true, Migrations.MIGRATION_2_3).use { v3 ->
            v3.query("SELECT shiftWithFirst FROM alarm_groups WHERE id = 'g'").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
        }
    }

    @Test
    fun v3GetsWakeCheckColumnsWithTheCheckOff() {
        helper.createDatabase(db, 3).use { v3 ->
            v3.execSQL(
                "INSERT INTO alarm_groups (id, name, colorHex, iconKey, isArmed, sortOrder, createdAt, shiftWithFirst) " +
                    "VALUES ('g', 'Workday', '#000000', 'default', 1, 0, 0, 1)",
            )
            v3.execSQL("INSERT INTO active_ring (slot, alarmId, scheduledFor, startedAt) VALUES (0, 'a', 1000, 1000)")
        }
        helper.runMigrationsAndValidate(db, 4, true, Migrations.MIGRATION_3_4).use { v4 ->
            v4.query("SELECT wakeCheckEnabled, wakeCheckDelayMinutes FROM alarm_groups WHERE id = 'g'").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
                assertEquals(3, c.getInt(1))
            }
            v4.query("SELECT isWakeCheck FROM active_ring").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun v4AlarmsKeepPlainDismissal() {
        helper.createDatabase(db, 4).use { v4 ->
            v4.execSQL(
                "INSERT INTO alarm_groups (id, name, colorHex, iconKey, isArmed, sortOrder, createdAt, shiftWithFirst, " +
                    "wakeCheckEnabled, wakeCheckDelayMinutes) VALUES ('g', 'Workday', '#000000', 'default', 1, 0, 0, 1, 0, 3)",
            )
            v4.execSQL(
                "INSERT INTO alarms (id, groupId, positionInGroup, hour, minute, daysOfWeek, label, isEnabled, " +
                    "volumeRampSeconds, timeoutMinutes) VALUES ('a', 'g', 0, 6, 0, 31, NULL, 1, 30, 15)",
            )
        }
        helper.runMigrationsAndValidate(db, 5, true, Migrations.MIGRATION_4_5).use { v5 ->
            v5.query("SELECT challengeOverride FROM alarms WHERE id = 'a'").use { c ->
                c.moveToFirst()
                assertEquals(true, c.isNull(0))
            }
        }
    }

    @Test
    fun v5GroupsGetAnEmptyLadder() {
        helper.createDatabase(db, 5).use { v5 ->
            v5.execSQL(
                "INSERT INTO alarm_groups (id, name, colorHex, iconKey, isArmed, sortOrder, createdAt, shiftWithFirst, " +
                    "wakeCheckEnabled, wakeCheckDelayMinutes) VALUES ('g', 'Workday', '#000000', 'default', 1, 0, 0, 1, 0, 3)",
            )
        }
        helper.runMigrationsAndValidate(db, 6, true, Migrations.MIGRATION_5_6).use { v6 ->
            v6.query("SELECT escalationPolicy FROM alarm_groups WHERE id = 'g'").use { c ->
                c.moveToFirst()
                assertEquals("{}", c.getString(0))
            }
        }
    }

    @Test
    fun v6GroupsGetClassicAndTheirFirstAlarmsRampInTheNewRange() {
        helper.createDatabase(db, 6).use { v6 ->
            val group = "INSERT INTO alarm_groups (id, name, colorHex, iconKey, isArmed, sortOrder, createdAt, shiftWithFirst, " +
                "wakeCheckEnabled, wakeCheckDelayMinutes, escalationPolicy) VALUES "
            v6.execSQL("$group ('quick', 'Quick', '#000000', 'default', 1, 0, 0, 1, 0, 3, '{}')")
            v6.execSQL("$group ('slow', 'Slow', '#000000', 'default', 1, 1, 0, 1, 0, 3, '{}')")
            v6.execSQL("$group ('mid', 'Mid', '#000000', 'default', 1, 2, 0, 1, 0, 3, '{}')")
            v6.execSQL("$group ('empty', 'Empty', '#000000', 'default', 1, 3, 0, 1, 0, 3, '{}')")
            val alarm = "INSERT INTO alarms (id, groupId, positionInGroup, hour, minute, daysOfWeek, label, isEnabled, " +
                "volumeRampSeconds, timeoutMinutes) VALUES "
            // The first alarm by position decides, whatever the insertion order.
            v6.execSQL("$alarm ('q1', 'quick', 1, 6, 10, 31, NULL, 1, 90, 15), ('q0', 'quick', 0, 6, 0, 31, NULL, 1, 0, 15)")
            v6.execSQL("$alarm ('s0', 'slow', 0, 6, 0, 31, NULL, 1, 120, 15)")
            v6.execSQL("$alarm ('m0', 'mid', 0, 6, 0, 31, NULL, 1, 40, 15)")
        }
        helper.runMigrationsAndValidate(db, 7, true, Migrations.MIGRATION_6_7).use { v7 ->
            v7.query("SELECT id, sound, rampSeconds, rampStartPercent FROM alarm_groups ORDER BY sortOrder").use { c ->
                val rows = buildList {
                    while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3)))
                }
                val classic = """{"type":"builtin","toneKey":"classic"}"""
                assertEquals(
                    listOf(
                        listOf("quick", classic, 30, 15),
                        listOf("slow", classic, 60, 15),
                        listOf("mid", classic, 40, 15),
                        listOf("empty", classic, 45, 15),
                    ),
                    rows,
                )
            }
        }
    }

    @Test
    fun v7GroupsFollowTheAppTheme() {
        helper.createDatabase(db, 7).use { v7 ->
            v7.execSQL(
                "INSERT INTO alarm_groups (id, name, colorHex, iconKey, isArmed, sortOrder, createdAt) " +
                    "VALUES ('g', 'Workday', '#000000', 'default', 1, 0, 0)",
            )
        }
        helper.runMigrationsAndValidate(db, 8, true, Migrations.MIGRATION_7_8).use { v8 ->
            v8.query("SELECT themeId, sound, rampSeconds FROM alarm_groups WHERE id = 'g'").use { c ->
                c.moveToFirst()
                assertEquals(true, c.isNull(0))
                assertEquals(45, c.getInt(2))
            }
        }
    }

    @Test
    fun v8AlarmsGetSnoozeOnWithNoneUsed() {
        helper.createDatabase(db, 8).use { v8 ->
            v8.execSQL(
                "INSERT INTO alarm_groups (id, name, colorHex, iconKey, isArmed, sortOrder, createdAt) " +
                    "VALUES ('g', 'Workday', '#000000', 'default', 1, 0, 0)",
            )
            v8.execSQL(
                "INSERT INTO alarms (id, groupId, positionInGroup, hour, minute, daysOfWeek, label, isEnabled, " +
                    "volumeRampSeconds, timeoutMinutes) VALUES ('a', 'g', 0, 6, 0, 31, NULL, 1, 30, 15)",
            )
        }
        helper.runMigrationsAndValidate(db, 9, true, Migrations.MIGRATION_8_9).use { v9 ->
            v9.query("SELECT snoozeEnabled, snoozeMinutes, snoozeLimit, snoozesUsed FROM alarms WHERE id = 'a'").use { c ->
                c.moveToFirst()
                assertEquals(listOf(1, 5, 3, 0), (0..3).map(c::getInt))
            }
        }
    }

    @Test
    fun v9AlarmsDoNotReadAloud() {
        helper.createDatabase(db, 9).use { v9 ->
            v9.execSQL(
                "INSERT INTO alarm_groups (id, name, colorHex, iconKey, isArmed, sortOrder, createdAt) " +
                    "VALUES ('g', 'Workday', '#000000', 'default', 1, 0, 0)",
            )
            v9.execSQL(
                "INSERT INTO alarms (id, groupId, positionInGroup, hour, minute, daysOfWeek, label, isEnabled, " +
                    "volumeRampSeconds, timeoutMinutes) VALUES ('a', 'g', 0, 6, 0, 31, 'Pills', 1, 30, 15)",
            )
        }
        helper.runMigrationsAndValidate(db, 10, true, Migrations.MIGRATION_9_10).use { v10 ->
            v10.query("SELECT readLabelAloud, label FROM alarms WHERE id = 'a'").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
                assertEquals("Pills", c.getString(1))
            }
        }
    }

    @Test
    fun v10GroupsKeepTheSteadyVibrationAndCountAsChangedWhenCreated() {
        helper.createDatabase(db, 10).use { v10 ->
            v10.execSQL(
                "INSERT INTO alarm_groups (id, name, colorHex, iconKey, isArmed, sortOrder, createdAt) " +
                    "VALUES ('g', 'Workday', '#000000', 'default', 1, 0, 1234)",
            )
        }
        helper.runMigrationsAndValidate(db, 11, true, Migrations.MIGRATION_10_11).use { v11 ->
            v11.query("SELECT vibration, vibrationStrength, vibrationDelaySeconds, updatedAt FROM alarm_groups WHERE id = 'g'").use { c ->
                c.moveToFirst()
                assertEquals("steady", c.getString(0))
                assertEquals(100, c.getInt(1))
                assertEquals(0, c.getInt(2))
                assertEquals(1234L, c.getLong(3))
            }
        }
    }
}
