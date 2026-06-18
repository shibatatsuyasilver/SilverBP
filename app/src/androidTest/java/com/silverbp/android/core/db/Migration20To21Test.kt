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

@RunWith(AndroidJUnit4::class)
class Migration20To21Test {
    private companion object {
        const val DB = "migration-20-21-test.db"
    }

    private lateinit var helper: SupportSQLiteOpenHelper

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase(DB)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(20) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE `medication_dose` (`id` TEXT NOT NULL, `dayStart` INTEGER NOT NULL, " +
                                "`medicationId` TEXT NOT NULL, `scheduledHour` INTEGER NOT NULL, " +
                                "`taken` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
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

    @Test fun migrates_addsDoseMinuteAndScheduleId() {
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO medication_dose (id, dayStart, medicationId, scheduledHour, taken, updatedAt, hlcUpdatedAt) " +
                "VALUES ('d1', 1000, 'm1', 10, 1, 2000, '0')",
        )

        MIGRATION_20_21.migrate(db)

        val cols = columns(db, "medication_dose")
        assertTrue(cols.contains("scheduledMinute"))
        assertTrue(cols.contains("scheduleId"))
        db.query("SELECT scheduledMinute, scheduleId FROM medication_dose WHERE id = 'd1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertTrue(c.isNull(1))
        }
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val out = mutableSetOf<String>()
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) out += c.getString(idx)
            out
        }
}
