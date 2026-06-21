package com.silverbp.android.ui.today

import com.silverbp.android.coach.DayOfWeekMask
import com.silverbp.android.coach.MedicationActionReceiver
import com.silverbp.android.core.db.MedicationDoseEntity
import com.silverbp.android.core.db.MedicationEntity
import com.silverbp.android.core.db.MedicationKind
import com.silverbp.android.core.db.MedicationScheduleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** Unit tests for [deriveTodayDoses] — the Today medication card's dose flattening. */
class TodayMedicationsTest {

    private val monday = LocalDate.of(2024, 1, 1) // verified Monday
    private val dayStart = 1_704_067_200_000L

    private fun med(
        id: String,
        name: String,
        dose: String = "",
        kind: String = MedicationKind.MEDICATION,
    ) = MedicationEntity(id = id, name = name, dose = dose, kind = kind, memberId = "m1")

    private fun sched(
        id: String,
        medId: String,
        hour: Int,
        minute: Int = 0,
        mask: Int = DayOfWeekMask.ALL,
        enabled: Boolean = true,
    ) = MedicationScheduleEntity(
        id = id,
        medicationId = medId,
        daysOfWeekMask = mask,
        hour = hour,
        minute = minute,
        enabled = enabled,
    )

    @Test fun `sorts by time then medication name`() {
        val meds = listOf(med("a", "Zinc"), med("b", "Amlodipine"))
        val schedules = listOf(
            sched("s1", "a", 20),
            sched("s2", "b", 8),
            sched("s3", "a", 8), // ties with s2 on time → name tiebreak
        )
        val result = deriveTodayDoses(meds, schedules, emptyList(), monday, dayStart)
        assertEquals(listOf("Amlodipine", "Zinc", "Zinc"), result.map { it.name })
        assertEquals(listOf(8, 8, 20), result.map { it.hour })
    }

    @Test fun `excludes disabled schedules and other weekdays`() {
        val meds = listOf(med("a", "A"))
        val mwf = DayOfWeekMask.fromSet(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
        )
        val tueOnly = DayOfWeekMask.fromSet(setOf(DayOfWeek.TUESDAY))
        val schedules = listOf(
            sched("s-on", "a", 8, mask = mwf),
            sched("s-off", "a", 9, mask = mwf, enabled = false),
            sched("s-tue", "a", 10, mask = tueOnly),
        )
        val result = deriveTodayDoses(meds, schedules, emptyList(), monday, dayStart)
        assertEquals(listOf("s-on"), result.map { it.scheduleId })
    }

    @Test fun `taken flag resolves from recorded dose by deterministic id`() {
        val meds = listOf(med("a", "A"))
        val schedules = listOf(sched("s1", "a", 8))
        val doseId = MedicationActionReceiver.doseId(dayStart, "s1")
        val doses = listOf(
            MedicationDoseEntity(
                id = doseId,
                dayStart = dayStart,
                medicationId = "a",
                scheduledHour = 8,
                scheduleId = "s1",
                taken = true,
                updatedAt = 0L,
            ),
        )
        val result = deriveTodayDoses(meds, schedules, doses, monday, dayStart)
        assertEquals(1, result.size)
        assertTrue(result.first().taken)
        assertEquals(doseId, result.first().id)
    }

    @Test fun `untracked dose defaults to not taken`() {
        val meds = listOf(med("a", "A"))
        val schedules = listOf(sched("s1", "a", 8))
        val result = deriveTodayDoses(meds, schedules, emptyList(), monday, dayStart)
        assertFalse(result.first().taken)
    }

    @Test fun `schedule whose medication is missing is dropped`() {
        val schedules = listOf(sched("s1", "ghost", 8))
        val result = deriveTodayDoses(emptyList(), schedules, emptyList(), monday, dayStart)
        assertTrue(result.isEmpty())
    }
}
