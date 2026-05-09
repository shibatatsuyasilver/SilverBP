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
        ExerciseSessionEntity::class,
        RoutePointEntity::class,
        AchievementEntity::class,
        DailyStepLogEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        CoachPlanEntity::class,
        CoachTaskEntity::class,
        SleepLogEntity::class,
        DietCheckEntity::class,
        MedicationDoseEntity::class,
        MedicationScheduleEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class SilverBpDatabase : RoomDatabase() {
    abstract fun bpDao(): BpDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun medicationDao(): MedicationDao
    abstract fun medicationScheduleDao(): MedicationScheduleDao
    abstract fun tagDao(): TagDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun achievementDao(): AchievementDao
    abstract fun chatDao(): ChatDao
    abstract fun coachPlanDao(): CoachPlanDao
    abstract fun sleepDao(): SleepDao
    abstract fun dietDao(): DietDao
    abstract fun medicationDoseDao(): MedicationDoseDao

    companion object {
        @Volatile private var instance: SilverBpDatabase? = null
        fun get(context: Context): SilverBpDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SilverBpDatabase::class.java,
                "silverbp.db",
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                )
                .build()
                .also { instance = it }
        }
    }
}

/**
 * v1 → v2: introduce `exercise_session` and `route_point` for the GPS-tracked
 * walking/running feature. Pure CREATE TABLE — existing BP rows are not touched.
 *
 * SQL must match Room's generated schema byte-for-byte; see
 * `app/schemas/.../SilverBpDatabase/2.json` after building.
 */
internal val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `exercise_session` (
              `id` TEXT NOT NULL,
              `activityKind` TEXT NOT NULL,
              `startedAt` INTEGER NOT NULL,
              `endedAt` INTEGER NOT NULL,
              `distanceMeters` REAL NOT NULL,
              `stepCount` INTEGER,
              `averagePaceSecPerKm` REAL,
              `source` TEXT NOT NULL,
              `note` TEXT NOT NULL,
              `hcRecordId` TEXT,
              `createdAt` INTEGER NOT NULL,
              `updatedAt` INTEGER NOT NULL,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_exercise_session_startedAt` " +
                "ON `exercise_session` (`startedAt`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_exercise_session_activityKind` " +
                "ON `exercise_session` (`activityKind`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `route_point` (
              `id` TEXT NOT NULL,
              `sessionId` TEXT NOT NULL,
              `timestamp` INTEGER NOT NULL,
              `lat` REAL NOT NULL,
              `lon` REAL NOT NULL,
              `horizontalAccuracy` REAL NOT NULL,
              `altitude` REAL,
              `speedMps` REAL,
              PRIMARY KEY(`id`),
              FOREIGN KEY(`sessionId`) REFERENCES `exercise_session`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_route_point_sessionId` " +
                "ON `route_point` (`sessionId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_route_point_timestamp` " +
                "ON `route_point` (`timestamp`)"
        )
    }
}

/**
 * v2 → v3: introduce `achievement` (medal unlock log) and `daily_step_log`
 * (per-day step backfill). Pure CREATE TABLE — existing exercise rows are
 * not touched.
 *
 * SQL must match Room's generated schema byte-for-byte; check
 * `app/schemas/.../SilverBpDatabase/3.json` after building.
 */
internal val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `achievement` (
              `kindRaw` TEXT NOT NULL,
              `unlockedAt` INTEGER NOT NULL,
              `notifiedAt` INTEGER,
              `unlockedBackfilled` INTEGER NOT NULL,
              `valueAtUnlock` INTEGER NOT NULL,
              PRIMARY KEY(`kindRaw`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_achievement_unlockedAt` " +
                "ON `achievement` (`unlockedAt`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `daily_step_log` (
              `dayStart` INTEGER NOT NULL,
              `steps` INTEGER NOT NULL,
              `sourceRaw` TEXT NOT NULL,
              `updatedAt` INTEGER NOT NULL,
              PRIMARY KEY(`dayStart`)
            )
            """.trimIndent()
        )
    }
}

/**
 * v3 → v4: introduce `chat_session` and `chat_message` for the multimodal
 * conversation feature (text + voice + photo, with records-aware system prompt).
 * Pure CREATE TABLE — existing rows are not touched.
 *
 * SQL must match Room's generated schema byte-for-byte; see
 * `app/schemas/.../SilverBpDatabase/4.json` after building.
 */
internal val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_session` (
              `id` TEXT NOT NULL,
              `title` TEXT NOT NULL,
              `createdAt` INTEGER NOT NULL,
              `updatedAt` INTEGER NOT NULL,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chat_session_updatedAt` " +
                "ON `chat_session` (`updatedAt`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_message` (
              `id` TEXT NOT NULL,
              `sessionId` TEXT NOT NULL,
              `role` TEXT NOT NULL,
              `text` TEXT NOT NULL,
              `imagePath` TEXT,
              `createdAt` INTEGER NOT NULL,
              PRIMARY KEY(`id`),
              FOREIGN KEY(`sessionId`) REFERENCES `chat_session`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chat_message_sessionId` " +
                "ON `chat_message` (`sessionId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chat_message_createdAt` " +
                "ON `chat_message` (`createdAt`)"
        )
    }
}

/**
 * v4 → v5: introduce the Coach feature persistence — `coach_plan`, `coach_task`,
 * `sleep_log`, `diet_check`, `medication_dose`. Pure CREATE TABLE — existing
 * rows are not touched and there are no FK back into BP/exercise tables, so
 * disabling the Coach feature only requires deleting Coach rows.
 *
 * SQL must match Room's generated schema byte-for-byte; see
 * `app/schemas/.../SilverBpDatabase/5.json` after building.
 */
internal val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `coach_plan` (
              `id` TEXT NOT NULL,
              `weekStart` INTEGER NOT NULL,
              `generatedAt` INTEGER NOT NULL,
              `ruleVersion` INTEGER NOT NULL,
              `phaseRaw` TEXT NOT NULL,
              `goalsJson` TEXT NOT NULL,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_coach_plan_weekStart` " +
                "ON `coach_plan` (`weekStart`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `coach_task` (
              `id` TEXT NOT NULL,
              `planId` TEXT NOT NULL,
              `dayOffset` INTEGER NOT NULL,
              `moduleRaw` TEXT NOT NULL,
              `title` TEXT NOT NULL,
              `targetValue` REAL,
              `targetUnit` TEXT,
              `intensityRaw` TEXT NOT NULL,
              `safetyHold` INTEGER NOT NULL,
              `completedAt` INTEGER,
              PRIMARY KEY(`id`),
              FOREIGN KEY(`planId`) REFERENCES `coach_plan`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_coach_task_planId` " +
                "ON `coach_task` (`planId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_coach_task_dayOffset` " +
                "ON `coach_task` (`dayOffset`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sleep_log` (
              `dayStart` INTEGER NOT NULL,
              `durationMin` INTEGER NOT NULL,
              `sourceRaw` TEXT NOT NULL,
              `updatedAt` INTEGER NOT NULL,
              PRIMARY KEY(`dayStart`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `diet_check` (
              `dayStart` INTEGER NOT NULL,
              `sodiumLevelRaw` TEXT NOT NULL,
              `vegServings` INTEGER NOT NULL,
              `sourceRaw` TEXT NOT NULL,
              `updatedAt` INTEGER NOT NULL,
              PRIMARY KEY(`dayStart`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `medication_dose` (
              `id` TEXT NOT NULL,
              `dayStart` INTEGER NOT NULL,
              `medicationId` TEXT NOT NULL,
              `scheduledHour` INTEGER NOT NULL,
              `taken` INTEGER NOT NULL,
              `updatedAt` INTEGER NOT NULL,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_medication_dose_dayStart` " +
                "ON `medication_dose` (`dayStart`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_medication_dose_medicationId` " +
                "ON `medication_dose` (`medicationId`)"
        )
    }
}

/**
 * v5 → v6: medication / supplement management & per-time-of-day reminders.
 * Adds `kind` to `medication` (default 'medication' so existing rows preserve
 * behavior) and creates `medication_schedule` for the day-of-week × time
 * regimen rows that drive [MedicationReminderScheduler].
 *
 * SQL must match Room's generated schema byte-for-byte; see
 * `app/schemas/.../SilverBpDatabase/6.json` after building.
 */
internal val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `medication` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'medication'"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `medication_schedule` (
              `id` TEXT NOT NULL,
              `medicationId` TEXT NOT NULL,
              `daysOfWeekMask` INTEGER NOT NULL,
              `hour` INTEGER NOT NULL,
              `minute` INTEGER NOT NULL,
              `enabled` INTEGER NOT NULL,
              PRIMARY KEY(`id`),
              FOREIGN KEY(`medicationId`) REFERENCES `medication`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_medication_schedule_medicationId` " +
                "ON `medication_schedule` (`medicationId`)"
        )
    }
}
