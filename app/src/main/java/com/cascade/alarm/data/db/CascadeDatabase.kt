package com.cascade.alarm.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [AlarmGroupEntity::class, AlarmEntity::class, ActiveRingEntity::class, AlarmEventEntity::class],
    // Every version bump needs an entry in Migrations.ALL and a MigrationTest case.
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class CascadeDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun alarmGroupDao(): AlarmGroupDao
    abstract fun activeRingDao(): ActiveRingDao
    abstract fun alarmEventDao(): AlarmEventDao

    companion object {
        const val NAME = "cascade.db"

        /**
         * The database lives in device-protected storage so the engine can read it before the
         * user unlocks the phone for the first time after a reboot (Direct Boot). Alarm data is
         * not sensitive; anything that is (e.g. reference photos) must stay in credential storage.
         *
         * No fallbackToDestructiveMigration: a missing migration must crash in testing, never wipe
         * a user's alarms in the field.
         */
        fun create(context: Context): CascadeDatabase {
            val deviceContext = context.createDeviceProtectedStorageContext()
            return Room.databaseBuilder(deviceContext, CascadeDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .build()
        }
    }
}
