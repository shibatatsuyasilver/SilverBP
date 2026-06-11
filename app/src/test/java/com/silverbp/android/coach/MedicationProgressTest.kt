package com.silverbp.android.coach

import com.silverbp.android.core.db.MedicationScheduleEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class MedicationProgressTest {

    private fun schedule(
        mask: Int,
        hour: Int = 8,
        enabled: Boolean = true,
        id: String = "s-$hour-$mask-$enabled",
    ) = MedicationScheduleEntity(
        id = id,
        medicationId = "med-1",
        daysOfWeekMask = mask,
        hour = hour,
        minute = 0,
        enabled = enabled,
    )

    private val mwf = DayOfWeekMask.fromSet(
        setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
    )
    private val weekend = DayOfWeekMask.fromSet(
        setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    )

    private val monday = LocalDate.of(2024, 1, 1)

    @Test fun `MWF schedule counts as 3 across whole week`() {
        val schedules = listOf(schedule(mwf))
        val result = CoachRepository.countScheduledInWeek(schedules, monday)
        assertEquals(3, result)
    }

    @Test fun `two MWF schedules same days count as 6`() {
        val schedules = listOf(
            schedule(mwf, hour = 8, id = "morning"),
            schedule(mwf, hour = 20, id = "evening"),
        )
        val result = CoachRepository.countScheduledInWeek(schedules, monday)
        assertEquals(6, result)
    }

    @Test fun `disabled schedules are not counted`() {
        val schedules = listOf(
            schedule(mwf, enabled = true, id = "on"),
            schedule(mwf, enabled = false, id = "off"),
        )
        val result = CoachRepository.countScheduledInWeek(schedules, monday)
        assertEquals(3, result)
    }

    @Test fun `daily schedule counts as 7`() {
        val schedules = listOf(schedule(DayOfWeekMask.ALL))
        val result = CoachRepository.countScheduledInWeek(schedules, monday)
        assertEquals(7, result)
    }

    @Test fun `Monday-only counts as 1`() {
        val mondayOnly = DayOfWeekMask.fromSet(setOf(DayOfWeek.MONDAY))
        val schedules = listOf(schedule(mondayOnly))
        val result = CoachRepository.countScheduledInWeek(schedules, monday)
        assertEquals(1, result)
    }

    @Test fun `weekend-only counts as 2`() {
        val schedules = listOf(schedule(weekend))
        val result = CoachRepository.countScheduledInWeek(schedules, monday)
        assertEquals(2, result)
    }

    @Test fun `count is independent of weekStart day-of-week`() {
        // weekStart could (in theory) be a Wednesday — count should still
        // walk 7 consecutive days and visit Mon/Wed/Fri once each.
        val schedules = listOf(schedule(mwf))
        val wednesdayStart = LocalDate.of(2024, 1, 3)
        assertEquals(3, CoachRepository.countScheduledInWeek(schedules, wednesdayStart))
    }

    // --- medicationAdherenceRatio: taken / schedule-derived weekly target ---
    // Regression: the old denominator was COUNT(*) of medication_dose rows,
    // which only exist once the user interacts — 3 taken of a daily (7×)
    // schedule reported 100% because the 4 untouched days had no rows.

    @Test fun `three taken of a daily schedule is three sevenths, not 100 percent`() {
        val scheduled = CoachRepository.countScheduledInWeek(
            listOf(schedule(DayOfWeekMask.ALL)), monday,
        )
        assertEquals(3f / 7f, CoachRepository.medicationAdherenceRatio(3, scheduled), 1e-4f)
    }

    @Test fun `all doses taken is exactly 1`() {
        assertEquals(1f, CoachRepository.medicationAdherenceRatio(7, 7), 0f)
    }

    @Test fun `extra taken rows clamp to 1`() {
        assertEquals(1f, CoachRepository.medicationAdherenceRatio(9, 7), 0f)
    }

    @Test fun `nothing scheduled guards the division and yields 0`() {
        assertEquals(0f, CoachRepository.medicationAdherenceRatio(0, 0), 0f)
    }
}
