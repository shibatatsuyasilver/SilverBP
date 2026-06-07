package com.silverbp.android.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.silverbp.android.security.DbKeyStore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

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
        TombstoneEntity::class,
        SyncDeviceEntity::class,
        SyncOutboxEntity::class,
        ExerciseCatalogItemEntity::class,
        StrengthWorkoutSessionEntity::class,
        SetLogEntity::class,
        BpWorkoutAssociationEntity::class,
        FoodLogEntity::class,
    ],
    version = 17,
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
    abstract fun syncDao(): SyncDao
    abstract fun exerciseLibraryDao(): ExerciseLibraryDao
    abstract fun strengthWorkoutDao(): StrengthWorkoutDao
    abstract fun bpWorkoutAssociationDao(): BpWorkoutAssociationDao
    abstract fun foodLogDao(): FoodLogDao

    companion object {
        const val DB_NAME = "silverbp.db"

        @Volatile private var instance: SilverBpDatabase? = null

        fun get(context: Context): SilverBpDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        /**
         * Drop the cached handle so the next [get] reopens the file. Used by
         * the encrypt/decrypt migration which must close Room, swap the file,
         * then reopen with (or without) the SQLCipher passphrase.
         */
        fun resetForMigration() = synchronized(this) {
            instance?.close()
            instance = null
        }

        private fun build(appContext: Context): SilverBpDatabase {
            val builder = Room.databaseBuilder(
                appContext,
                SilverBpDatabase::class.java,
                DB_NAME,
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
            )

            // At-rest encryption is opt-in. The marker lives in the Keystore-
            // wrapped DbKeyStore and is read synchronously here, before any DAO
            // touches the file. When absent (default / never opted in) the
            // builder is left untouched → plain SQLite, zero behaviour change.
            // The Room MIGRATION_* chain still runs, just inside the cipher DB.
            val keyStore = DbKeyStore.create(appContext)
            if (keyStore.isDbEncrypted()) {
                System.loadLibrary("sqlcipher")
                builder.openHelperFactory(
                    SupportOpenHelperFactory(
                        keyStore.getOrCreatePassphrase().toByteArray(Charsets.US_ASCII),
                    ),
                )
            }

            return builder.build()
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

/**
 * v6 → v7: introduce cross-device sync (Phase 1). Adds [hlcUpdatedAt] to
 * `bp_reading` for LWW conflict resolution and creates the three system tables
 * required for the Noise XK over mDNS sync engine: `tombstone` (soft-delete
 * record for any synced entity), `sync_device` (paired peers with their
 * X25519 long-term pubkeys + HLC watermarks), and `sync_outbox` (CBOR-encoded
 * SyncRecord payloads queued while peers are offline).
 *
 * Subsequent tables (`user_profile`, `medication`, etc.) gain `hlcUpdatedAt`
 * in Phase 2 once the BP-only MVP has proven the wire path.
 *
 * SQL must match Room's generated schema byte-for-byte; see
 * `app/schemas/.../SilverBpDatabase/7.json` after building.
 */
internal val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `bp_reading` ADD COLUMN `hlcUpdatedAt` TEXT NOT NULL DEFAULT '0'"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tombstone` (
              `entityType` TEXT NOT NULL,
              `pk` TEXT NOT NULL,
              `hlc` TEXT NOT NULL,
              `deletedAt` INTEGER NOT NULL,
              PRIMARY KEY(`entityType`, `pk`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tombstone_hlc` ON `tombstone` (`hlc`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sync_device` (
              `deviceId` TEXT NOT NULL,
              `name` TEXT NOT NULL,
              `pubKey` BLOB NOT NULL,
              `lastSeenAt` INTEGER NOT NULL,
              `lastHlcSeen` TEXT NOT NULL,
              PRIMARY KEY(`deviceId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sync_outbox` (
              `seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `payload` BLOB NOT NULL,
              `createdAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sync_outbox_createdAt` ON `sync_outbox` (`createdAt`)"
        )
    }
}

/**
 * v7 → v8: extend cross-device sync (Phase 2). Adds [hlcUpdatedAt] columns
 * to the entities the user explicitly asked us to sync next: exercise
 * (`exercise_session`, `route_point`) and medication (`medication`,
 * `medication_schedule`, `medication_dose`).
 *
 * SQL must match Room's generated schema byte-for-byte; see
 * `app/schemas/.../SilverBpDatabase/8.json` after building.
 */
internal val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `exercise_session` ADD COLUMN `hlcUpdatedAt` TEXT NOT NULL DEFAULT '0'")
        db.execSQL("ALTER TABLE `route_point` ADD COLUMN `hlcUpdatedAt` TEXT NOT NULL DEFAULT '0'")
        db.execSQL("ALTER TABLE `medication` ADD COLUMN `hlcUpdatedAt` TEXT NOT NULL DEFAULT '0'")
        db.execSQL("ALTER TABLE `medication_schedule` ADD COLUMN `hlcUpdatedAt` TEXT NOT NULL DEFAULT '0'")
        db.execSQL("ALTER TABLE `medication_dose` ADD COLUMN `hlcUpdatedAt` TEXT NOT NULL DEFAULT '0'")
    }
}

/**
 * v8 → v9: extend Phase 2 sync to `daily_step_log` (Insights 步數). Adds
 * [hlcUpdatedAt] for LWW. SQL must match Room's generated schema byte-for-byte;
 * see `app/schemas/.../SilverBpDatabase/9.json` after building.
 */
internal val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `daily_step_log` ADD COLUMN `hlcUpdatedAt` TEXT NOT NULL DEFAULT '0'")
    }
}

/**
 * v9 → v10: extend Phase 2 sync to `achievement` (獎章). Adds [hlcUpdatedAt]
 * for LWW. Achievement rows are append-only in practice (PK is the medal id),
 * so we never expect conflicting writes — HLC is here for parity with the
 * other synced tables and to give the engine a wire-side timestamp.
 */
internal val MIGRATION_9_10: Migration = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `achievement` ADD COLUMN `hlcUpdatedAt` TEXT NOT NULL DEFAULT '0'")
    }
}

/**
 * v10 → v11: extend Phase 2 sync to the Coach module — `coach_plan`,
 * `coach_task`, `sleep_log`, `diet_check`. Adds [hlcUpdatedAt] for LWW so
 * the weekly plan + per-day tasks + sleep + diet records all sync across
 * paired devices.
 */
internal val MIGRATION_10_11: Migration = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `coach_plan` ADD COLUMN `hlcUpdatedAt` TEXT NOT NULL DEFAULT '0'")
        db.execSQL("ALTER TABLE `coach_task` ADD COLUMN `hlcUpdatedAt` TEXT NOT NULL DEFAULT '0'")
        db.execSQL("ALTER TABLE `sleep_log` ADD COLUMN `hlcUpdatedAt` TEXT NOT NULL DEFAULT '0'")
        db.execSQL("ALTER TABLE `diet_check` ADD COLUMN `hlcUpdatedAt` TEXT NOT NULL DEFAULT '0'")
    }
}

/**
 * v11 → v12: introduce `activeDurationMillis` on `exercise_session` so the
 * persisted record carries the running-only duration (excluding Paused /
 * AutoPaused time). Previously the UI computed duration as `endedAt -
 * startedAt`, which over-counted any time the user sat at a red light or
 * forgot to stop the session — bug fix.
 *
 * Backfill: legacy rows get `endedAt - startedAt`, identical to what the UI
 * was already showing pre-fix, so existing history doesn't visually shift.
 * New sessions persist the true value from [com.silverbp.android.exercise.
 * ExerciseSessionLiveStore.SessionLive.activeDurationMillis].
 *
 * Sync wire format is NOT bumped — see [com.silverbp.android.sync.Phase2Mappers]
 * which keeps the iOS-byte-identical tag layout and falls back to
 * `endedAt - startedAt` on incoming records that lack the new field.
 *
 * SQL must match Room's generated schema byte-for-byte; see
 * `app/schemas/.../SilverBpDatabase/12.json` after building.
 */
internal val MIGRATION_11_12: Migration = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `exercise_session` ADD COLUMN `activeDurationMillis` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "UPDATE `exercise_session` SET `activeDurationMillis` = `endedAt` - `startedAt`",
        )
    }
}

/**
 * v12 → v13: add `hcRecordId` (nullable TEXT) to `bp_reading`. Tracks whether a
 * reading has been mirrored to Health Connect so [com.silverbp.android.health.
 * BpSyncWorker] can retry the ones that haven't (null = pending). Existing rows
 * migrate to NULL → they get mirrored on the next sync pass.
 *
 * Device-local: NOT carried in sync/backup (see [com.silverbp.android.sync.
 * BpReadingSyncMapper]), so a restored reading on a fresh device re-mirrors and
 * acquires that device's own record id.
 *
 * SQL must match Room's generated schema; see `app/schemas/.../13.json`.
 */
internal val MIGRATION_12_13: Migration = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bp_reading` ADD COLUMN `hcRecordId` TEXT")
    }
}

/**
 * v13 → v14: introduce the strength-training data layer — `exercise_catalog_item`
 * (the seeded move library + favorites), `strength_workout_session`, and
 * `set_log` (per-set log, cascade-deleted with its session). Pure CREATE TABLE —
 * existing rows are not touched.
 *
 * Column types/order, NOT NULL, PK, FK and the set_log index must match Room's
 * generated schema byte-for-byte; see
 * `app/schemas/.../SilverBpDatabase/14.json` after building. Kotlin-level
 * defaults (isFavorite = false, skipped = false, hlcUpdatedAt = "0") do NOT
 * emit a SQL DEFAULT — Room renders them as plain NOT NULL — so none are added
 * here.
 */
internal val MIGRATION_13_14: Migration = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `exercise_catalog_item` (
              `id` TEXT NOT NULL,
              `name` TEXT NOT NULL,
              `bodyPart` TEXT NOT NULL,
              `muscleGroupsJson` TEXT NOT NULL,
              `description` TEXT NOT NULL,
              `isFavorite` INTEGER NOT NULL,
              `createdAt` INTEGER NOT NULL,
              `updatedAt` INTEGER NOT NULL,
              `hlcUpdatedAt` TEXT NOT NULL,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `strength_workout_session` (
              `id` TEXT NOT NULL,
              `startedAt` INTEGER NOT NULL,
              `endedAt` INTEGER NOT NULL,
              `note` TEXT NOT NULL,
              `difficultyRaw` TEXT,
              `createdAt` INTEGER NOT NULL,
              `updatedAt` INTEGER NOT NULL,
              `hlcUpdatedAt` TEXT NOT NULL,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `set_log` (
              `id` TEXT NOT NULL,
              `workoutSessionId` TEXT NOT NULL,
              `exerciseId` TEXT NOT NULL,
              `setNumber` INTEGER NOT NULL,
              `reps` INTEGER NOT NULL,
              `weightKg` REAL,
              `isCompleted` INTEGER NOT NULL,
              `skipped` INTEGER NOT NULL,
              `notes` TEXT NOT NULL,
              `createdAt` INTEGER NOT NULL,
              `hlcUpdatedAt` TEXT NOT NULL,
              PRIMARY KEY(`id`),
              FOREIGN KEY(`workoutSessionId`) REFERENCES `strength_workout_session`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_set_log_workoutSessionId` " +
                "ON `set_log` (`workoutSessionId`)"
        )
    }
}

/**
 * v14 → v15: BP↔workout deep-linking (Phase 6). Creates `bp_workout_association`
 * (a pre/post BP reading linked to a cardio or strength session, no FK so the
 * link survives un-synced peers) and adds `skipped` / `movedDayOffset` to
 * `coach_task` for the skip/move task actions.
 *
 * ADD COLUMN on a NOT NULL column needs a SQL DEFAULT, so `skipped` is added
 * with `DEFAULT 0`; the nullable `movedDayOffset` needs none. The new table's
 * `hlcUpdatedAt` is NOT NULL with no SQL DEFAULT to match Room's render of the
 * Kotlin-level `= "0"` default (mirrors set_log in MIGRATION_13_14).
 *
 * SQL must match Room's generated schema byte-for-byte; see
 * `app/schemas/.../SilverBpDatabase/15.json` after building.
 */
internal val MIGRATION_14_15: Migration = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `bp_workout_association` (
              `id` TEXT NOT NULL,
              `bpReadingId` TEXT NOT NULL,
              `sessionId` TEXT NOT NULL,
              `sessionType` TEXT NOT NULL,
              `contextType` TEXT NOT NULL,
              `createdAt` INTEGER NOT NULL,
              `hlcUpdatedAt` TEXT NOT NULL,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_bp_workout_association_sessionId` " +
                "ON `bp_workout_association` (`sessionId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_bp_workout_association_bpReadingId` " +
                "ON `bp_workout_association` (`bpReadingId`)"
        )
        db.execSQL(
            "ALTER TABLE `coach_task` ADD COLUMN `skipped` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `coach_task` ADD COLUMN `movedDayOffset` INTEGER"
        )
    }
}

/**
 * v15 → v16: introduce `food_log` for the Nutrition (飲食) feature — a logged
 * meal with (estimated or barcode-label-sourced) nutrition. Pure CREATE TABLE,
 * no FK into other tables, so the feature is self-contained.
 *
 * `hlcUpdatedAt` is NOT NULL with no SQL DEFAULT to match Room's render of the
 * Kotlin-level `= "0"` default (mirrors set_log / bp_workout_association). The
 * nullable columns (photo, barcode, macros, sodium fields, hcRecordId) need none.
 *
 * SQL must match Room's generated schema byte-for-byte; see
 * `app/schemas/.../SilverBpDatabase/16.json` after building.
 */
internal val MIGRATION_15_16: Migration = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `food_log` (
              `id` TEXT NOT NULL,
              `timestamp` INTEGER NOT NULL,
              `mealTypeRaw` TEXT NOT NULL,
              `inputMethodRaw` TEXT NOT NULL,
              `description` TEXT NOT NULL,
              `photoFilename` TEXT,
              `barcode` TEXT,
              `productName` TEXT,
              `itemsJson` TEXT NOT NULL,
              `caloriesKcal` REAL,
              `proteinG` REAL,
              `carbsG` REAL,
              `fatG` REAL,
              `sugarG` REAL,
              `fiberG` REAL,
              `sodiumMg` REAL,
              `sodiumMgLow` REAL,
              `sodiumMgHigh` REAL,
              `sodiumLevelRaw` TEXT NOT NULL,
              `sodiumSourceRaw` TEXT NOT NULL,
              `confidence` REAL NOT NULL,
              `analysisBackendRaw` TEXT NOT NULL,
              `note` TEXT NOT NULL,
              `createdAt` INTEGER NOT NULL,
              `updatedAt` INTEGER NOT NULL,
              `hlcUpdatedAt` TEXT NOT NULL,
              `hcRecordId` TEXT,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_food_log_timestamp` ON `food_log` (`timestamp`)"
        )
    }
}

/**
 * v16 → v17: add gym-machine OCR columns to `exercise_session` for the cardio
 * console-display photo feature (treadmill / indoor bike / elliptical / rower /
 * stair climber). Calories + heart rate are captured from the console but kept
 * flagged as estimates; distance unit + floors handle non-km machines (rower =
 * metres, stair = floors/steps). `rawMetricsJson` keeps every OCR'd field.
 *
 * The two NOT NULL flags need a SQL DEFAULT for the existing rows (SQLite ADD
 * COLUMN requirement); the matching Kotlin-level entity defaults emit no schema
 * default, so Room ignores the difference (mirrors `coach_task.skipped` in
 * MIGRATION_14_15). The nullable columns need none.
 *
 * SQL must match Room's generated schema byte-for-byte; see
 * `app/schemas/.../SilverBpDatabase/17.json` after building.
 */
internal val MIGRATION_16_17: Migration = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `exercise_session` ADD COLUMN `caloriesKcal` REAL")
        db.execSQL("ALTER TABLE `exercise_session` ADD COLUMN `heartRateBpm` INTEGER")
        db.execSQL(
            "ALTER TABLE `exercise_session` ADD COLUMN `caloriesIsEstimate` INTEGER NOT NULL DEFAULT 1"
        )
        db.execSQL(
            "ALTER TABLE `exercise_session` ADD COLUMN `heartRateIsEstimate` INTEGER NOT NULL DEFAULT 1"
        )
        db.execSQL("ALTER TABLE `exercise_session` ADD COLUMN `distanceUnitRaw` TEXT")
        db.execSQL("ALTER TABLE `exercise_session` ADD COLUMN `floors` INTEGER")
        db.execSQL("ALTER TABLE `exercise_session` ADD COLUMN `rawMetricsJson` TEXT")
    }
}
