package com.silverbp.android.core.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * [CoachPlanDao.currentPlan] / [CoachPlanDao.observeCurrentPlan] window
 * boundaries. Regression: the query originally had no upper bound
 * (`weekStart <= now` only), so the most recent plan matched forever and a
 * weeks-old plan kept rendering its Sunday task as "today" instead of reading
 * as null and triggering regeneration (CoachViewModel / DailyReminderWorker).
 */
@RunWith(AndroidJUnit4::class)
class CoachPlanDaoTest {

    // Fixed zone (no DST) so the Sunday-23:59 / Monday-00:01 instants are deterministic.
    private val zone = ZoneId.of("Asia/Taipei")
    private val monday = LocalDate.of(2024, 1, 1) // a Monday
    private val weekStartMillis = monday.atStartOfDay(zone).toInstant().toEpochMilli()

    private lateinit var db: SilverBpDatabase
    private lateinit var dao: CoachPlanDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SilverBpDatabase::class.java).build()
        dao = db.coachPlanDao()
    }

    @After fun tearDown() = db.close()

    private fun plan(id: String, weekStart: Long) = CoachPlanEntity(
        id = id,
        weekStart = weekStart,
        generatedAt = weekStart,
        ruleVersion = 1,
        phaseRaw = "hold",
        goalsJson = "[]",
    )

    private fun millisAt(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun plan_is_current_through_its_own_sunday_night() = runBlocking {
        dao.insertPlan(plan("p1", weekStartMillis))
        val sunday2359 = millisAt(monday.plusDays(6), 23, 59)
        assertEquals("p1", dao.currentPlan(sunday2359)?.id)
    }

    @Test fun plan_expires_the_following_monday() = runBlocking {
        dao.insertPlan(plan("p1", weekStartMillis))
        val nextMonday0001 = millisAt(monday.plusDays(7), 0, 1)
        assertNull(dao.currentPlan(nextMonday0001))
    }

    @Test fun plan_is_not_current_before_its_week_starts() = runBlocking {
        dao.insertPlan(plan("p1", weekStartMillis))
        assertNull(dao.currentPlan(weekStartMillis - 1))
    }

    @Test fun current_week_plan_wins_over_last_weeks() = runBlocking {
        val nextWeekStart = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        dao.insertPlan(plan("stale", weekStartMillis))
        dao.insertPlan(plan("fresh", nextWeekStart))
        val tuesdayOfNextWeek = millisAt(monday.plusDays(8), 12, 0)
        assertEquals("fresh", dao.currentPlan(tuesdayOfNextWeek)?.id)
    }
}
