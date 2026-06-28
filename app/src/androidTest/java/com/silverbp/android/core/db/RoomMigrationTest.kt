package com.silverbp.android.core.db

import androidx.room.migration.Migration
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
        MIGRATION_21_22,
    )

    private data class MigrationEdge(
        val from: Int,
        val to: Int,
        val migrations: Array<Migration>,
    )

    private val schemaBackedEdges = listOf(
        MigrationEdge(1, 2, arrayOf(MIGRATION_1_2)),
        MigrationEdge(2, 3, arrayOf(MIGRATION_2_3)),
        MigrationEdge(3, 4, arrayOf(MIGRATION_3_4)),
        MigrationEdge(4, 5, arrayOf(MIGRATION_4_5)),
        MigrationEdge(5, 6, arrayOf(MIGRATION_5_6)),
        MigrationEdge(6, 7, arrayOf(MIGRATION_6_7)),
        MigrationEdge(7, 8, arrayOf(MIGRATION_7_8)),
        MigrationEdge(8, 9, arrayOf(MIGRATION_8_9)),
        MigrationEdge(9, 10, arrayOf(MIGRATION_9_10)),
        MigrationEdge(10, 11, arrayOf(MIGRATION_10_11)),
        MigrationEdge(11, 12, arrayOf(MIGRATION_11_12)),
        MigrationEdge(12, 13, arrayOf(MIGRATION_12_13)),
        // TODO: app/schemas/.../SilverBpDatabase/14.json is missing from history.
        // Do not fabricate it; validate 13->15 as a chained edge until a real
        // exported v14 schema is recovered.
        MigrationEdge(13, 15, arrayOf(MIGRATION_13_14, MIGRATION_14_15)),
        MigrationEdge(15, 16, arrayOf(MIGRATION_15_16)),
        MigrationEdge(16, 17, arrayOf(MIGRATION_16_17)),
        MigrationEdge(17, 18, arrayOf(MIGRATION_17_18)),
        MigrationEdge(18, 19, arrayOf(MIGRATION_18_19)),
        MigrationEdge(19, 20, arrayOf(MIGRATION_19_20)),
        MigrationEdge(20, 21, arrayOf(MIGRATION_20_21)),
        MigrationEdge(21, 22, arrayOf(MIGRATION_21_22)),
    )

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SilverBpDatabase::class.java,
    )

    /** The whole chain applies cleanly and matches the latest (v22) schema. */
    @Test
    fun migrateAll_1_to_22() {
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(dbName, 22, true, *allMigrations).close()
    }

    /** Every schema-backed adjacent edge through v22 validates, except missing v14. */
    @Test
    fun migrate_schemaBackedEdges_through21_matchSchemas() {
        for (edge in schemaBackedEdges) {
            val name = "migration-${edge.from}-${edge.to}.db"
            helper.createDatabase(name, edge.from).close()
            helper.runMigrationsAndValidate(name, edge.to, true, *edge.migrations).close()
        }
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

    /**
     * v21 → v22 adds the medication_dose → medication FK and drops orphan doses
     * (rows whose medicationId has no parent) while preserving valid ones.
     */
    @Test
    fun migrate_21_to_22_dropsOrphanMedicationDoses_keepsValid() {
        helper.createDatabase(dbName, 21).apply {
            execSQL(
                "INSERT INTO medication (id, name, dose, kind, hlcUpdatedAt, memberId) " +
                    "VALUES ('m1', 'Amlodipine', '5mg', 'medication', '0', 'owner')",
            )
            // Valid dose: its medicationId points at the seeded medication.
            execSQL(
                "INSERT INTO medication_dose (id, dayStart, medicationId, scheduledHour, " +
                    "scheduledMinute, scheduleId, taken, updatedAt, hlcUpdatedAt) " +
                    "VALUES ('d_valid', 1000, 'm1', 8, 0, NULL, 1, 1000, '0')",
            )
            // Orphan dose: medicationId has no matching medication row.
            execSQL(
                "INSERT INTO medication_dose (id, dayStart, medicationId, scheduledHour, " +
                    "scheduledMinute, scheduleId, taken, updatedAt, hlcUpdatedAt) " +
                    "VALUES ('d_orphan', 1000, 'm_missing', 8, 0, NULL, 1, 1000, '0')",
            )
            close()
        }

        helper.runMigrationsAndValidate(dbName, 22, true, MIGRATION_21_22).use { db ->
            db.query("SELECT id FROM medication_dose WHERE id = 'd_valid'").use { c ->
                assertTrue("valid dose should survive the migration", c.moveToFirst())
                assertEquals("d_valid", c.getString(0))
            }
            db.query("SELECT COUNT(*) FROM medication_dose WHERE id = 'd_orphan'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("orphan dose should be dropped by the migration", 0, c.getInt(0))
            }
        }
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
