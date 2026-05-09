package com.silverbp.android.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DayOfWeekMaskTest {

    @Test fun `empty mask is empty`() {
        assertTrue(DayOfWeekMask.isEmpty(0))
        assertFalse(DayOfWeekMask.isEmpty(1))
        assertFalse(DayOfWeekMask.isEmpty(DayOfWeekMask.ALL))
    }

    @Test fun `fromSet round-trip`() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        val mask = DayOfWeekMask.fromSet(days)
        assertEquals(days, DayOfWeekMask.toSet(mask))
    }

    @Test fun `bit positions match ISO`() {
        // Mon=bit0, Sun=bit6
        assertTrue(DayOfWeekMask.contains(0b0000001, DayOfWeek.MONDAY))
        assertTrue(DayOfWeekMask.contains(0b1000000, DayOfWeek.SUNDAY))
        assertFalse(DayOfWeekMask.contains(0b0000001, DayOfWeek.TUESDAY))
    }

    @Test fun `toggle flips one bit`() {
        val m1 = DayOfWeekMask.toggle(0, DayOfWeek.MONDAY)
        assertTrue(DayOfWeekMask.contains(m1, DayOfWeek.MONDAY))
        val m2 = DayOfWeekMask.toggle(m1, DayOfWeek.MONDAY)
        assertFalse(DayOfWeekMask.contains(m2, DayOfWeek.MONDAY))
    }

    @Test fun `ALL has all seven days`() {
        DayOfWeek.values().forEach {
            assertTrue(DayOfWeekMask.contains(DayOfWeekMask.ALL, it))
        }
    }

    @Test fun `nextFiringMillis returns null for empty mask`() {
        assertNull(MedicationReminderScheduler.nextFiringMillis(0, 8, 0))
    }

    @Test fun `nextFiringMillis picks today when time is later`() {
        val zone = ZoneId.of("UTC")
        // Monday 06:00 UTC, looking for Mon 08:00 → today.
        val now = ZonedDateTime.of(LocalDate.of(2024, 1, 1), LocalTime.of(6, 0), zone)
        val mask = DayOfWeekMask.fromSet(setOf(DayOfWeek.MONDAY))
        val expected = ZonedDateTime.of(LocalDate.of(2024, 1, 1), LocalTime.of(8, 0), zone)
            .toInstant().toEpochMilli()
        assertEquals(expected, MedicationReminderScheduler.nextFiringMillis(mask, 8, 0, now))
    }

    @Test fun `nextFiringMillis advances to next matching day when time has passed`() {
        val zone = ZoneId.of("UTC")
        // Monday 09:00 UTC, looking for Mon 08:00 → next Monday (7 days later).
        val now = ZonedDateTime.of(LocalDate.of(2024, 1, 1), LocalTime.of(9, 0), zone)
        val mask = DayOfWeekMask.fromSet(setOf(DayOfWeek.MONDAY))
        val expected = ZonedDateTime.of(LocalDate.of(2024, 1, 8), LocalTime.of(8, 0), zone)
            .toInstant().toEpochMilli()
        assertEquals(expected, MedicationReminderScheduler.nextFiringMillis(mask, 8, 0, now))
    }

    @Test fun `nextFiringMillis picks earliest of multiple days`() {
        val zone = ZoneId.of("UTC")
        // Monday 09:00 UTC. Mask = Mon/Wed/Fri at 08:00 → next is Wednesday.
        val now = ZonedDateTime.of(LocalDate.of(2024, 1, 1), LocalTime.of(9, 0), zone)
        val mask = DayOfWeekMask.fromSet(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        )
        val expected = ZonedDateTime.of(LocalDate.of(2024, 1, 3), LocalTime.of(8, 0), zone)
            .toInstant().toEpochMilli()
        assertEquals(expected, MedicationReminderScheduler.nextFiringMillis(mask, 8, 0, now))
    }
}
