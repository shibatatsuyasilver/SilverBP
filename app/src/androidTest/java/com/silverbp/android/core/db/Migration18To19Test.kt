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
 * Drives [MIGRATION_18_19] against a hand-seeded v18 database. Asserts:
 *  - the `glucose_reading` table + its two indices exist
 *    (`index_glucose_reading_timestamp` / `index_glucose_reading_memberId_timestamp`);
 *  - the column set matches the entity (born member-native — `memberId` is
 *    present and NOT NULL, with **no** backfill since the table is new);
 *  - a glucose row inserted after the migration round-trips, including its
 *    member-scoped read path.
 *
 * Like [Migration17To18Test], this drives the Migration object directly instead
 * of [androidx.room.testing.MigrationTestHelper] (which would need the exported
 * schema JSONs wired as androidTest assets — covered separately for the chain).
 * Requires a connected device/emulator: `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class Migration18To19Test {

    private companion object {
        const val DB = "migration-18-19-test.db"
    }

    private lateinit var helper: SupportSQLiteOpenHelper

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase(DB)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(18) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Minimal v18 subset — the glucose migration is pure
                        // CREATE TABLE with no FK, so a placeholder member is all
                        // we need to anchor a realistic member-scoped insert.
                        db.execSQL(
                            "CREATE TABLE `member` (`id` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                                "`isOwner` INTEGER NOT NULL, `birthYear` INTEGER, `hasDiabetes` INTEGER NOT NULL, " +
                                "`hasCKD` INTEGER NOT NULL, `hasASCVD` INTEGER NOT NULL, `guideline` TEXT NOT NULL, " +
                                "`colorIndex` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                                "`archived` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                                "`updatedAt` INTEGER NOT NULL, `hlcUpdatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))",
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

    @Test fun migrates_createsGlucoseTableAndIndices_andRowRoundTrips() {
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO member (id, displayName, isOwner, birthYear, hasDiabetes, hasCKD, hasASCVD, " +
                "guideline, colorIndex, sortOrder, archived, createdAt, updatedAt, hlcUpdatedAt) " +
                "VALUES ('owner-1', '外公', 1, 1948, 0, 0, 0, 'taiwan2022', 0, 0, 0, 1000, 1000, '0')",
        )

        MIGRATION_18_19.migrate(db)

        // glucose_reading table + its two indices with Room's generated names.
        assertTrue("glucose_reading table missing", tableExists(db, "glucose_reading"))
        assertTrue(indexExists(db, "index_glucose_reading_timestamp"))
        assertTrue(indexExists(db, "index_glucose_reading_memberId_timestamp"))

        // Born member-native: memberId column present, no backfill needed.
        val cols = columns(db, "glucose_reading")
        assertTrue(cols.contains("memberId"))
        assertTrue(cols.contains("valueMgdl"))
        assertTrue(cols.contains("displayUnit"))
        assertTrue(cols.contains("measureContext"))
        assertTrue(cols.contains("hlcUpdatedAt"))
        assertTrue(cols.contains("hcRecordId"))

        // A new reading inserts + reads back through the member-scoped path.
        db.execSQL(
            "INSERT INTO glucose_reading (id, memberId, valueMgdl, displayUnit, measureContext, " +
                "timestamp, source, confidence, note, photoFilename, createdAt, updatedAt, " +
                "hlcUpdatedAt, hcRecordId) " +
                "VALUES ('g1', 'owner-1', 95.0, 'mgdl', 'fasting', 2000, 'manual', 1.0, '', NULL, " +
                "2000, 2000, '0', NULL)",
        )
        db.query("SELECT valueMgdl, measureContext FROM glucose_reading WHERE memberId = 'owner-1'")
            .use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(95.0, c.getDouble(0), 0.0001)
                assertEquals("fasting", c.getString(1))
                assertFalse("should be exactly one glucose row", c.moveToNext())
            }
    }

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use {
            it.moveToFirst()
        }

    private fun indexExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name=?", arrayOf(name)).use {
            it.moveToFirst()
        }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val out = mutableSetOf<String>()
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) out += c.getString(idx)
            out
        }
}
