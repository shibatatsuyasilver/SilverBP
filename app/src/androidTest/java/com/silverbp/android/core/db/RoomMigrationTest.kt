package com.silverbp.android.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates every Room migration against the exported schema JSONs (bundled as
 * androidTest assets — see app/build.gradle.kts). Previously the 12 migrations
 * had zero coverage; a version-chain upgrade (v1.0 → later) that silently lost
 * data would not have been caught.
 *
 * Requires a connected device/emulator: `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {

    private val dbName = "migration-test.db"

    private val allMigrations = arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
        MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
        MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21,
    )

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SilverBpDatabase::class.java,
    )

    /** The whole chain applies cleanly and matches the latest (v21) schema. */
    @Test
    fun migrateAll_1_to_21() {
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(dbName, 21, true, *allMigrations).close()
    }

    /** v19 → v20 adds the weight_log table and still matches the v20 schema. */
    @Test
    fun migrate_19_to_20_matchesSchema() {
        helper.createDatabase(dbName, 19).close()
        helper.runMigrationsAndValidate(dbName, 20, true, MIGRATION_19_20).close()
    }

    /** v20 → v21 adds medication_dose.scheduledMinute / scheduleId. */
    @Test
    fun migrate_20_to_21_matchesSchema() {
        helper.createDatabase(dbName, 20).close()
        helper.runMigrationsAndValidate(dbName, 21, true, MIGRATION_20_21).close()
    }

    /** v12 → v13 adds hcRecordId (defaulting NULL) and preserves existing rows. */
    @Test
    fun migrate_12_to_13_addsHcRecordId_preservesRows() {
        helper.createDatabase(dbName, 12).apply {
            execSQL(
                "INSERT INTO bp_reading (id, systolic, diastolic, pulse, timestamp, arm, " +
                    "posture, partOfDay, beforeMedication, photoFilename, confidence, source, " +
                    "note, irregularHeartbeat, medicationId, createdAt, updatedAt, hlcUpdatedAt) " +
                    "VALUES ('r1', 120, 80, 70, 1000, 'left', 'sitting', 'morning', 1, NULL, " +
                    "1.0, 'manual', '', 0, NULL, 1000, 1000, '0')",
            )
            close()
        }

        helper.runMigrationsAndValidate(dbName, 13, true, MIGRATION_12_13).use { db ->
            db.query("SELECT id, systolic, hcRecordId FROM bp_reading WHERE id = 'r1'").use { c ->
                assertTrue("seeded row should survive the migration", c.moveToFirst())
                assertEquals("r1", c.getString(0))
                assertEquals(120, c.getInt(1))
                assertTrue("hcRecordId should default to NULL", c.isNull(2))
            }
        }
    }
}
