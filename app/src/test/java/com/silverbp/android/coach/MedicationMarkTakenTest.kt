package com.silverbp.android.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MedicationActionReceiver.markTakenDose] is the shared extras→entity
 * conversion behind both the notification action button and the notification
 * body tap. A null return means "invalid/missing extras" and callers fall
 * back to navigation-only, so the rejection cases mirror the Intent getter
 * defaults (-1L / -1) used by both call sites.
 */
class MedicationMarkTakenTest {

    private val dayStart = 1_735_689_600_000L
    private val nowMs = 1_735_718_400_123L

    private fun build(
        medicationId: String? = "med-1",
        scheduleId: String? = "sched-1",
        dayStart: Long = this.dayStart,
        scheduledHour: Int = 8,
        scheduledMinute: Int = 30,
        nowMs: Long = this.nowMs,
    ) = MedicationActionReceiver.markTakenDose(
        medicationId = medicationId,
        scheduleId = scheduleId,
        dayStart = dayStart,
        scheduledHour = scheduledHour,
        scheduledMinute = scheduledMinute,
        nowMs = nowMs,
    )

    @Test
    fun valid_extras_build_taken_dose() {
        val dose = build()
        assertNotNull(dose)
        dose!!
        assertEquals(MedicationActionReceiver.doseId(dayStart, "sched-1"), dose.id)
        assertEquals("med-$dayStart-sched-1", dose.id)
        assertEquals(dayStart, dose.dayStart)
        assertEquals("med-1", dose.medicationId)
        assertEquals(8, dose.scheduledHour)
        assertEquals(30, dose.scheduledMinute)
        assertEquals("sched-1", dose.scheduleId)
        assertTrue(dose.taken)
        assertEquals(nowMs, dose.updatedAt)
    }

    @Test
    fun missing_medication_id_returns_null() {
        assertNull(build(medicationId = null))
        assertNull(build(medicationId = ""))
        assertNull(build(medicationId = "   "))
    }

    @Test
    fun missing_schedule_id_returns_null() {
        assertNull(build(scheduleId = null))
        assertNull(build(scheduleId = ""))
        assertNull(build(scheduleId = "   "))
    }

    @Test
    fun negative_day_start_returns_null() {
        // -1L is the getLongExtra default when the extra is absent.
        assertNull(build(dayStart = -1L))
    }

    @Test
    fun negative_hour_returns_null() {
        // -1 is the getIntExtra default when the extra is absent.
        assertNull(build(scheduledHour = -1))
    }

    @Test
    fun minute_zero_default_is_valid() {
        val dose = build(scheduledMinute = 0)
        assertNotNull(dose)
        assertEquals(0, dose!!.scheduledMinute)
    }

    @Test
    fun hour_zero_midnight_is_valid() {
        val dose = build(scheduledHour = 0)
        assertNotNull(dose)
        assertEquals(0, dose!!.scheduledHour)
    }
}
