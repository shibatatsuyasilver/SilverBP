package com.silverbp.android.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BpReadingEntity::class,
        UserProfileEntity::class,
        MedicationEntity::class,
        TagEntity::class,
        ReadingTagCrossRef::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SilverBpDatabase : RoomDatabase() {
    abstract fun bpDao(): BpDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun medicationDao(): MedicationDao
    abstract fun tagDao(): TagDao

    companion object {
        @Volatile private var instance: SilverBpDatabase? = null
        fun get(context: Context): SilverBpDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SilverBpDatabase::class.java,
                "silverbp.db",
            ).build().also { instance = it }
        }
    }
}
