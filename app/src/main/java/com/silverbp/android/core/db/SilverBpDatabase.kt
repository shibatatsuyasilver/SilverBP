package com.silverbp.android.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BpReadingEntity::class,
        UserProfileEntity::class,
        MedicationEntity::class,
        TagEntity::class,
        ReadingTagCrossRef::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SilverBpDatabase : RoomDatabase() {
    abstract fun bpDao(): BpDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun medicationDao(): MedicationDao
    abstract fun tagDao(): TagDao
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile private var instance: SilverBpDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS chat_session (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS chat_message (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        role TEXT NOT NULL,
                        text TEXT NOT NULL,
                        imagePath TEXT,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES chat_session(id) ON DELETE CASCADE
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_message_sessionId ON chat_message(sessionId)")
            }
        }

        fun get(context: Context): SilverBpDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SilverBpDatabase::class.java,
                "silverbp.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
