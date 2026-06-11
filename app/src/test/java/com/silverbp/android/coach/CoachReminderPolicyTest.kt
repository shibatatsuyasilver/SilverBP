package com.silverbp.android.coach

import androidx.work.ExistingPeriodicWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class CoachReminderPolicyTest {

    @Test fun `re-enqueues when nothing was scheduled before`() {
        assertEquals(
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            CoachReminderScheduler.policyFor(null, Triple(7, 0, DayOfWeekMask.ALL)),
        )
    }

    @Test fun `keeps when target is unchanged (cold-start sweep is a no-op)`() {
        val target = Triple(8, 30, DayOfWeekMask.ALL)
        assertEquals(
            ExistingPeriodicWorkPolicy.KEEP,
            CoachReminderScheduler.policyFor(target, target),
        )
    }

    @Test fun `re-enqueues when the hour changes`() {
        assertEquals(
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            CoachReminderScheduler.policyFor(Triple(7, 0, DayOfWeekMask.ALL), Triple(9, 0, DayOfWeekMask.ALL)),
        )
    }

    @Test fun `re-enqueues when the minute changes`() {
        assertEquals(
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            CoachReminderScheduler.policyFor(Triple(7, 0, DayOfWeekMask.ALL), Triple(7, 15, DayOfWeekMask.ALL)),
        )
    }

    @Test fun `re-enqueues when the day mask changes`() {
        val weekdays = DayOfWeekMask.fromSet(
            setOf(
                java.time.DayOfWeek.MONDAY,
                java.time.DayOfWeek.TUESDAY,
                java.time.DayOfWeek.WEDNESDAY,
                java.time.DayOfWeek.THURSDAY,
                java.time.DayOfWeek.FRIDAY,
            )
        )
        assertEquals(
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            CoachReminderScheduler.policyFor(Triple(7, 0, DayOfWeekMask.ALL), Triple(7, 0, weekdays)),
        )
    }
}
