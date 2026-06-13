package com.silverbp.android.core.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [MIGRATION_17_18] against a hand-seeded v17 database. Asserts:
 *  - the `member` table + its two indices exist;
 *  - the owner row is backfilled from `user_profile` (id / displayName / flags /
 *    guideline reused, isOwner = 1);
 *  - `bp_reading` / `medication` gain `memberId` stamped with the owner id and
 *    the member-scoped indices are created with Room's generated names;
 *  - the empty-`user_profile` branch synthesizes a single owner and still
 *    backfills existing readings to it.
 *
 * Like [Migration13To14Test], this drives the Migration object directly instead
 * of [androidx.room.testing.MigrationTestHelper] (which would need the exported
 * schema JSONs wired as androidTest assets — this module doesn't configure that).
 * Requires a connected device/emulator: `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class Migration17To18Test {

    private companion object {
        const val DB = "migration-17-18-test.db"
    }

    private lateinit var helper: SupportSQLiteOpenHelper

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase(DB)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(17) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Minimal v17 subset the migration touches.
                        db.execSQL(
                            "CREATE TABLE `user_profile` (`id` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                                "`birthYear` INTEGER NOT NULL, `hasDiabetes` INTEGER NOT NULL, " +
                                "`hasCKD` INTEGER NOT NULL, `hasASCVD` INTEGER NOT NULL, " +
                                "`guideline` TEXT NOT NULL, PRIMARY KEY(`id`))",
                        )
                        db.execSQL(
                            "CREATE TABLE `bp_reading` (`id` TEXT NOT NULL, `systolic` INTEGER NOT NULL, " +
                                "`diastolic` INTEGER NOT NULL, `pulse` INTEGER, `timestamp` INTEGER NOT NULL, " +
                                "`arm` TEXT NOT NULL, `posture` TEXT NOT NULL, `partOfDay` TEXT NOT NULL, " +
                                "`beforeMedication` INTEGER NOT NULL, `photoFilename` TEXT, " +
                                "`confidence` REAL NOT NULL, `source` TEXT NOT NULL, `note` TEXT NOT NULL, " +
                                "`irregularHeartbeat` INTEGER NOT NULL, `medicationId` TEXT, " +
                                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                                "`hlcUpdatedAt` TEXT NOT NULL, `hcRecordId` TEXT, PRIMARY KEY(`id`))",
                        )
                        db.execSQL(
                            "CREATE TABLE `medication` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                                "`dose` TEXT NOT NULL, `kind` TEXT NOT NULL, `hlcUpdatedAt` TEXT NOT NULL, " +
                                "PRIMARY KEY(`id`))",
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build(),
        )
    }

    @After fun tearDown() {
        helper.close()
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(DB)
    }

    @Test fun migrates_backfillsOwnerFromUserProfile_andStampsRows() {
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO user_profile (id, displayName, birthYear, hasDiabetes, hasCKD, hasASCVD, guideline) " +
                "VALUES ('owner-1', '外公', 1948, 1, 0, 1, 'accAha2017')",
        )
        db.execSQL(
            "INSERT INTO bp_reading (id, systolic, diastolic, pulse, timestamp, arm, posture, partOfDay, " +
                "beforeMedication, photoFilename, confidence, source, note, irregularHeartbeat, medicationId, " +
                "createdAt, updatedAt, hlcUpdatedAt, hcRecordId) " +
                "VALUES ('r1', 130, 85, 70, 1000, 'left', 'sitting', 'morning', 1, NULL, 1.0, 'manual', '', 0, " +
                "NULL, 1000, 1000, '0', NULL)",
        )
        db.execSQL(
            "INSERT INTO medication (id, name, dose, kind, hlcUpdatedAt) " +
                "VALUES ('m1', 'Amlodipine', '5mg', 'medication', '0')",
        )

        MIGRATION_17_18.migrate(db)

        // member table + its two indices.
        assertTrue("member table missing", tableExists(db, "member"))
        assertTrue(indexExists(db, "index_member_isOwner"))
        assertTrue(indexExists(db, "index_member_sortOrder"))

        // Owner row backfilled from user_profile, isOwner = 1, exactly one owner.
        db.query("SELECT id, displayName, birthYear, hasDiabetes, hasCKD, hasASCVD, guideline, isOwner FROM member")
            .use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("owner-1", c.getString(0))
                assertEquals("外公", c.getString(1))
                assertEquals(1948, c.getInt(2))
                assertEquals(1, c.getInt(3)) // hasDiabetes
                assertEquals(0, c.getInt(4)) // hasCKD
                assertEquals(1, c.getInt(5)) // hasASCVD
                assertEquals("accAha2017", c.getString(6))
                assertEquals(1, c.getInt(7)) // isOwner
                assertFalse("should be exactly one member row", c.moveToNext())
            }
        assertEquals(1, ownerCount(db))

        // bp_reading / medication gain memberId stamped with the owner id + indices.
        assertTrue(columns(db, "bp_reading").contains("memberId"))
        assertTrue(columns(db, "medication").contains("memberId"))
        assertTrue(indexExists(db, "index_bp_reading_memberId_timestamp"))
        assertTrue(indexExists(db, "index_medication_memberId"))
        assertEquals("owner-1", scalar(db, "SELECT memberId FROM bp_reading WHERE id = 'r1'"))
        assertEquals("owner-1", scalar(db, "SELECT memberId FROM medication WHERE id = 'm1'"))
    }

    @Test fun migrates_emptyUserProfile_synthesizesSingleOwner() {
        val db = helper.writableDatabase
        // No user_profile row. Seed an orphaned reading so we can confirm it
        // resolves to the synthesized owner.
        db.execSQL(
            "INSERT INTO bp_reading (id, systolic, diastolic, pulse, timestamp, arm, posture, partOfDay, " +
                "beforeMedication, photoFilename, confidence, source, note, irregularHeartbeat, medicationId, " +
                "createdAt, updatedAt, hlcUpdatedAt, hcRecordId) " +
                "VALUES ('r1', 120, 80, NULL, 1000, 'left', 'sitting', 'morning', 1, NULL, 1.0, 'manual', '', 0, " +
                "NULL, 1000, 1000, '0', NULL)",
        )

        MIGRATION_17_18.migrate(db)

        // Exactly one owner synthesized, with a non-empty id.
        assertEquals(1, ownerCount(db))
        val ownerId = scalar(db, "SELECT id FROM member WHERE isOwner = 1")
        assertTrue("synthesized owner id should be non-empty", !ownerId.isNullOrEmpty())
        // The orphaned reading resolves to that owner.
        assertEquals(ownerId, scalar(db, "SELECT memberId FROM bp_reading WHERE id = 'r1'"))
    }

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use {
            it.moveToFirst()
        }

    private fun indexExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name=?", arrayOf(name)).use {
            it.moveToFirst()
        }

    private fun ownerCount(db: SupportSQLiteDatabase): Int =
        db.query("SELECT COUNT(*) FROM member WHERE isOwner = 1").use { c ->
            c.moveToFirst(); c.getInt(0)
        }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): String? =
        db.query(sql).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val out = mutableSetOf<String>()
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) out += c.getString(idx)
            out
        }
}
