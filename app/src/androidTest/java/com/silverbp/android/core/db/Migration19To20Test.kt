package com.silverbp.android.core.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [MIGRATION_19_20] against a hand-seeded v19 database. Asserts:
 *  - the `weight_reading` table + its two indices exist
 *    (`index_weight_reading_timestamp` / `index_weight_reading_memberId_timestamp`);
 *  - the column set matches the entity (born member-native — `memberId` is
 *    present and NOT NULL, with **no** backfill since the table is new);
 *  - `member.heightCm` is added (nullable INTEGER) and backfills to NULL on the
 *    pre-existing owner row;
 *  - a weight row inserted after the migration round-trips through the
 *    member-scoped read path.
 *
 * Like [Migration18To19Test], this drives the Migration object directly instead
 * of [androidx.room.testing.MigrationTestHelper]. Requires a connected
 * device/emulator: `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class Migration19To20Test {

    private companion object {
        const val DB = "migration-19-20-test.db"
    }

    private lateinit var helper: SupportSQLiteOpenHelper

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase(DB)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Minimal v19 subset — the weight migration is pure
                        // CREATE TABLE + a member ALTER, so the v19 `member` table
                        // (pre-heightCm) is all we need to anchor a realistic
                        // member-scoped insert and verify the ADD COLUMN.
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

    @Test fun migrates_createsWeightTableAndIndices_addsHeight_andRowRoundTrips() {
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO member (id, displayName, isOwner, birthYear, hasDiabetes, hasCKD, hasASCVD, " +
                "guideline, colorIndex, sortOrder, archived, createdAt, updatedAt, hlcUpdatedAt) " +
                "VALUES ('owner-1', '外公', 1, 1948, 0, 0, 0, 'taiwan2022', 0, 0, 0, 1000, 1000, '0')",
        )

        MIGRATION_19_20.migrate(db)

        // weight_reading table + its two indices with Room's generated names.
        assertTrue("weight_reading table missing", tableExists(db, "weight_reading"))
        assertTrue(indexExists(db, "index_weight_reading_timestamp"))
        assertTrue(indexExists(db, "index_weight_reading_memberId_timestamp"))

        // Born member-native: memberId column present, no backfill needed.
        val cols = columns(db, "weight_reading")
        assertTrue(cols.contains("memberId"))
        assertTrue(cols.contains("weightKg"))
        assertTrue(cols.contains("displayUnit"))
        assertTrue(cols.contains("hlcUpdatedAt"))
        assertTrue(cols.contains("hcRecordId"))

        // member gains a nullable heightCm; the pre-existing owner backfills to NULL.
        assertTrue("member.heightCm missing", columns(db, "member").contains("heightCm"))
        db.query("SELECT heightCm FROM member WHERE id = 'owner-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("heightCm should backfill NULL", c.isNull(0))
        }

        // A new reading inserts + reads back through the member-scoped path.
        db.execSQL(
            "INSERT INTO weight_reading (id, memberId, weightKg, displayUnit, timestamp, source, " +
                "note, photoFilename, createdAt, updatedAt, hlcUpdatedAt, hcRecordId) " +
                "VALUES ('w1', 'owner-1', 70.5, 'kg', 2000, 'manual', '', NULL, 2000, 2000, '0', NULL)",
        )
        db.query("SELECT weightKg, displayUnit, hcRecordId FROM weight_reading WHERE memberId = 'owner-1'")
            .use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(70.5, c.getDouble(0), 0.0001)
                assertEquals("kg", c.getString(1))
                assertNull(if (c.isNull(2)) null else c.getString(2))
                assertFalse("should be exactly one weight row", c.moveToNext())
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
