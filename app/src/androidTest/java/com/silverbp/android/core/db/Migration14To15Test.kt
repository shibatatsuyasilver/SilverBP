package com.silverbp.android.core.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [MIGRATION_14_15] against a hand-seeded v14 database. Asserts the new
 * `bp_workout_association` table + its two indices exist, `coach_task` gained
 * the `skipped` / `movedDayOffset` columns, and a pre-existing coach_task row
 * survives with the correct migrated defaults (skipped=0, movedDayOffset=NULL).
 *
 * Doesn't use [androidx.room.testing.MigrationTestHelper] — that needs the
 * exported schema JSONs wired as androidTest assets, which this module doesn't
 * configure. Driving the Migration object directly validates the same SQL.
 */
@RunWith(AndroidJUnit4::class)
class Migration14To15Test {

    private companion object {
        const val DB = "migration-14-15-test.db"
    }

    private lateinit var helper: SupportSQLiteOpenHelper

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase(DB)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(14) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Minimal v14 subset the migration touches.
                        db.execSQL(
                            "CREATE TABLE `coach_plan` (`id` TEXT NOT NULL, `weekStart` INTEGER NOT NULL, " +
                                "`generatedAt` INTEGER NOT NULL, `ruleVersion` INTEGER NOT NULL, " +
                                "`phaseRaw` TEXT NOT NULL, `goalsJson` TEXT NOT NULL, " +
                                "`hlcUpdatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))",
                        )
                        db.execSQL(
                            "CREATE TABLE `coach_task` (`id` TEXT NOT NULL, `planId` TEXT NOT NULL, " +
                                "`dayOffset` INTEGER NOT NULL, `moduleRaw` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                                "`targetValue` REAL, `targetUnit` TEXT, `intensityRaw` TEXT NOT NULL, " +
                                "`safetyHold` INTEGER NOT NULL, `completedAt` INTEGER, " +
                                "`hlcUpdatedAt` TEXT NOT NULL, PRIMARY KEY(`id`))",
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

    @Test fun migrates_and_preserves_old_rows() {
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO coach_plan (id, weekStart, generatedAt, ruleVersion, phaseRaw, goalsJson, hlcUpdatedAt) " +
                "VALUES ('plan-1', 0, 0, 1, 'baseline', '[]', '0')",
        )
        db.execSQL(
            "INSERT INTO coach_task (id, planId, dayOffset, moduleRaw, title, targetValue, targetUnit, " +
                "intensityRaw, safetyHold, completedAt, hlcUpdatedAt) " +
                "VALUES ('task-1', 'plan-1', 2, 'ex', '快走', 30.0, 'min', 'moderate', 0, NULL, '0')",
        )

        MIGRATION_14_15.migrate(db)

        // New table exists.
        assertTrue("bp_workout_association table missing", tableExists(db, "bp_workout_association"))
        // Its two indices exist.
        assertTrue(indexExists(db, "index_bp_workout_association_sessionId"))
        assertTrue(indexExists(db, "index_bp_workout_association_bpReadingId"))
        // New columns on coach_task.
        val cols = columns(db, "coach_task")
        assertTrue("skipped column missing", cols.contains("skipped"))
        assertTrue("movedDayOffset column missing", cols.contains("movedDayOffset"))

        // Old row survives with migrated defaults.
        db.query("SELECT title, skipped, movedDayOffset FROM coach_task WHERE id = 'task-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("快走", c.getString(0))
            assertEquals(0, c.getInt(1))
            assertTrue("movedDayOffset should default NULL", c.isNull(2))
        }
        // New table is writable post-migration.
        db.execSQL(
            "INSERT INTO bp_workout_association (id, bpReadingId, sessionId, sessionType, contextType, createdAt, hlcUpdatedAt) " +
                "VALUES ('a-1', 'bp-1', 'sess-1', 'cardio', 'pre', 123, '0')",
        )
        db.query("SELECT contextType FROM bp_workout_association WHERE id = 'a-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("pre", c.getString(0))
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
