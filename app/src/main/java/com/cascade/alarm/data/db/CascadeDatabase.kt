package com.cascade.alarm.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [AlarmEventEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class CascadeDatabase : RoomDatabase() {
    abstract fun alarmEventDao(): AlarmEventDao

    companion object {
        const val NAME = "cascade.db"

        /**
         * The database lives in device-protected storage so the engine can read it before the
         * user unlocks the phone for the first time after a reboot (Direct Boot). Alarm data is
         * not sensitive; anything that is (e.g. reference photos) must stay in credential storage.
         */
        fun create(context: Context): CascadeDatabase {
            val deviceContext = context.createDeviceProtectedStorageContext()
            return Room.databaseBuilder(deviceContext, CascadeDatabase::class.java, NAME).build()
        }
    }
}
