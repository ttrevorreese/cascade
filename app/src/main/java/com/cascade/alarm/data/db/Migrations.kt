package com.cascade.alarm.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cascade.alarm.data.sound.SoundConfig
import com.cascade.alarm.data.sound.SoundSettings

/**
 * Every schema change ships with a migration: a wiped alarm database is a set of alarms that
 * silently stop ringing. Statements mirror app/schemas/<version>.json exactly.
 */
object Migrations {
    /** Group that receives alarms created before groups existed. */
    const val MIGRATED_GROUP_ID = "migrated-default-group"

    /** v1 → v2: alarm groups. Existing alarms move into an armed "My alarms" group. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `alarm_groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`colorHex` TEXT NOT NULL, `iconKey` TEXT NOT NULL, `isArmed` INTEGER NOT NULL, " +
                    "`sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "INSERT INTO `alarm_groups` (`id`, `name`, `colorHex`, `iconKey`, `isArmed`, `sortOrder`, `createdAt`) " +
                    "SELECT ?, 'My alarms', ?, ?, 1, 0, ? WHERE EXISTS (SELECT 1 FROM `alarms`)",
                arrayOf<Any>(MIGRATED_GROUP_ID, AlarmGroupDefaults.COLOR_HEX, AlarmGroupDefaults.ICON_KEY, System.currentTimeMillis()),
            )
            // SQLite cannot add a foreign key to an existing table, so the table is rebuilt.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `alarms_new` (`id` TEXT NOT NULL, `groupId` TEXT NOT NULL, " +
                    "`positionInGroup` INTEGER NOT NULL, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, " +
                    "`daysOfWeek` INTEGER NOT NULL, `label` TEXT, `isEnabled` INTEGER NOT NULL, " +
                    "`volumeRampSeconds` INTEGER NOT NULL, `timeoutMinutes` INTEGER NOT NULL, " +
                    "`nextTriggerAt` INTEGER, `oneTimeTriggerAt` INTEGER, `lastFiredFor` INTEGER, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`groupId`) REFERENCES `alarm_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            // Window functions need SQLite 3.25 (API 30+); a correlated count works on API 26.
            db.execSQL(
                "INSERT INTO `alarms_new` (`id`, `groupId`, `positionInGroup`, `hour`, `minute`, `daysOfWeek`, " +
                    "`label`, `isEnabled`, `volumeRampSeconds`, `timeoutMinutes`, `nextTriggerAt`, " +
                    "`oneTimeTriggerAt`, `lastFiredFor`) " +
                    "SELECT a.`id`, ?, (SELECT COUNT(*) FROM `alarms` b WHERE b.`hour` * 60 + b.`minute` < " +
                    "a.`hour` * 60 + a.`minute` OR (b.`hour` = a.`hour` AND b.`minute` = a.`minute` AND b.`id` < a.`id`)), " +
                    "a.`hour`, a.`minute`, a.`daysOfWeek`, a.`label`, a.`isEnabled`, a.`volumeRampSeconds`, " +
                    "a.`timeoutMinutes`, a.`nextTriggerAt`, a.`oneTimeTriggerAt`, a.`lastFiredFor` FROM `alarms` a",
                arrayOf<Any>(MIGRATED_GROUP_ID),
            )
            db.execSQL("DROP TABLE `alarms`")
            db.execSQL("ALTER TABLE `alarms_new` RENAME TO `alarms`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_alarms_groupId` ON `alarms` (`groupId`)")
        }
    }

    /** v2 → v3: per-group "move the rest with the first alarm", on for existing groups. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `alarm_groups` ADD COLUMN `shiftWithFirst` INTEGER NOT NULL DEFAULT 1")
        }
    }

    /** v3 → v4: wake-up check, off for existing groups. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `alarm_groups` ADD COLUMN `wakeCheckEnabled` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `alarm_groups` ADD COLUMN `wakeCheckDelayMinutes` INTEGER NOT NULL DEFAULT 3")
            db.execSQL("ALTER TABLE `alarms` ADD COLUMN `wakeCheckAt` INTEGER")
            db.execSQL("ALTER TABLE `active_ring` ADD COLUMN `isWakeCheck` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v4 → v5: per-alarm challenge (JSON); existing alarms keep plain dismissal. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `alarms` ADD COLUMN `challengeOverride` TEXT")
        }
    }

    /** v5 → v6: each group's escalation ladder (JSON). Existing groups get an empty one. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `alarm_groups` ADD COLUMN `escalationPolicy` TEXT NOT NULL DEFAULT '{}'")
        }
    }

    /**
     * v6 → v7: sound and ramp move to the group. Existing groups keep the Classic tone and take
     * their first alarm's ramp, brought into the new 30–60 s range. The alarms' own ramp column
     * stays (unused): SQLite cannot drop a column before API 34.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `alarm_groups` ADD COLUMN `sound` TEXT NOT NULL DEFAULT '${SoundConfig.DEFAULT_JSON}'")
            db.execSQL("ALTER TABLE `alarm_groups` ADD COLUMN `rampSeconds` INTEGER NOT NULL DEFAULT 45")
            db.execSQL("ALTER TABLE `alarm_groups` ADD COLUMN `rampStartPercent` INTEGER NOT NULL DEFAULT 15")
            db.execSQL(
                "UPDATE `alarm_groups` SET `rampSeconds` = MAX(?, MIN(?, COALESCE((SELECT a.`volumeRampSeconds` FROM `alarms` a " +
                    "WHERE a.`groupId` = `alarm_groups`.`id` ORDER BY a.`positionInGroup` LIMIT 1), ?)))",
                arrayOf<Any>(SoundSettings.MIN_RAMP_SECONDS, SoundSettings.MAX_RAMP_SECONDS, SoundSettings.DEFAULT_RAMP_SECONDS),
            )
        }
    }

    /** v7 → v8: a theme per group (DESIGN.md §11); existing groups follow the app theme. */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `alarm_groups` ADD COLUMN `themeId` TEXT")
        }
    }

    /** v8 → v9: snooze (M7). Existing alarms get it on, 5 minutes, 3 times; never on a group's last. */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `alarms` ADD COLUMN `snoozeEnabled` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `alarms` ADD COLUMN `snoozeMinutes` INTEGER NOT NULL DEFAULT 5")
            db.execSQL("ALTER TABLE `alarms` ADD COLUMN `snoozeLimit` INTEGER NOT NULL DEFAULT 3")
            db.execSQL("ALTER TABLE `alarms` ADD COLUMN `snoozesUsed` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v9 → v10: read the label aloud (M7). Off for every existing alarm. */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `alarms` ADD COLUMN `readLabelAloud` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v10 → v11: vibration per group (Steady, as before, for existing groups) and when a group was
     * last edited, for sorting Home (owner requests, 2026-10-25). Existing groups count as edited
     * when they were created.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `alarm_groups` ADD COLUMN `vibration` TEXT NOT NULL DEFAULT 'steady'")
            db.execSQL("ALTER TABLE `alarm_groups` ADD COLUMN `vibrationStrength` INTEGER NOT NULL DEFAULT 100")
            db.execSQL("ALTER TABLE `alarm_groups` ADD COLUMN `vibrationDelaySeconds` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `alarm_groups` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `alarm_groups` SET `updatedAt` = `createdAt`")
        }
    }

    /** v11 → v12: skip, pause and "next time only" per group (2026-10-26); nothing set means as before. */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `alarm_groups` ADD COLUMN `skipUntil` TEXT")
            db.execSQL("ALTER TABLE `alarm_groups` ADD COLUMN `onceFrom` TEXT")
            db.execSQL("ALTER TABLE `alarm_groups` ADD COLUMN `onceShiftMinutes` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL = arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
    )
}

object AlarmGroupDefaults {
    // Placeholders until the design work lands.
    const val COLOR_HEX = "#FCA311"
    const val ICON_KEY = "default"
}
