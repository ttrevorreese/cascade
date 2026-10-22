package com.cascade.alarm.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}

object AlarmGroupDefaults {
    // Placeholders until the design work lands.
    const val COLOR_HEX = "#FCA311"
    const val ICON_KEY = "default"
}
