package com.silverbp.android.coach

import com.silverbp.android.core.db.DietCheckEntity
import com.silverbp.android.core.db.SleepLogEntity
import com.silverbp.android.ui.coach.countDietDoneDays
import com.silverbp.android.ui.coach.countSleepDoneDays
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the Diet/Sleep weekly-ring numerators, which previously read
 * `coach_task.completedAtMillis` (never set for these modules → stuck at 0).
 * They are now derived directly from the logged rows.
 */
class LifestyleDaysProgressTest {

    private fun diet(sodium: String, day: Long = 0L) = DietCheckEntity(
        dayStart = day,
        sodiumLevelRaw = sodium,
        vegServings = 3,
        sourceRaw = "manual",
        updatedAt = 0L,
    )

    private fun sleep(min: Int, day: Long = 0L) = SleepLogEntity(
        dayStart = day,
        durationMin = min,
        sourceRaw = "manual",
        updatedAt = 0L,
    )

    @Test fun `empty diet list is zero`() {
        assertEquals(0, countDietDoneDays(emptyList()))
    }

    @Test fun `diet counts low and mid sodium but not high`() {
        val rows = listOf(
            diet("low", 1),
            diet("mid", 2),
            diet("high", 3),
            diet("low", 4),
        )
        assertEquals(3, countDietDoneDays(rows))
    }

    @Test fun `diet all-high days count as zero`() {
        assertEquals(0, countDietDoneDays(listOf(diet("high", 1), diet("high", 2))))
    }

    @Test fun `empty sleep list is zero`() {
        assertEquals(0, countSleepDoneDays(emptyList(), targetMin = 420))
    }

    @Test fun `sleep counts days at or above target`() {
        // target 7h = 420 min
        val rows = listOf(
            sleep(420, 1), // exactly target → counts
            sleep(480, 2), // above → counts
            sleep(419, 3), // one below → does not count
            sleep(360, 4), // well below → does not count
        )
        assertEquals(2, countSleepDoneDays(rows, targetMin = 420))
    }

    @Test fun `sleep target boundary is inclusive`() {
        assertEquals(1, countSleepDoneDays(listOf(sleep(420)), targetMin = 420))
        assertEquals(0, countSleepDoneDays(listOf(sleep(419)), targetMin = 420))
    }
}
