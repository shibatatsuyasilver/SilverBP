package com.silverbp.android.coach

import java.time.DayOfWeek

/**
 * 7-bit mask encoding ISO days of week. Bit (dow.value - 1) is set when the
 * day is included: Monday=bit0 … Sunday=bit6.
 *
 * Persisted as Int in [com.silverbp.android.core.db.MedicationScheduleEntity];
 * UI passes `Set<DayOfWeek>` from FilterChips, the scheduler iterates a mask
 * to find the next firing day.
 */
object DayOfWeekMask {
    const val ALL: Int = 0b111_1111

    fun fromSet(days: Set<DayOfWeek>): Int =
        days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) }

    fun toSet(mask: Int): Set<DayOfWeek> =
        DayOfWeek.values().filter { contains(mask, it) }.toSet()

    fun contains(mask: Int, dow: DayOfWeek): Boolean =
        (mask shr (dow.value - 1)) and 1 == 1

    fun isEmpty(mask: Int): Boolean = (mask and ALL) == 0

    fun toggle(mask: Int, dow: DayOfWeek): Int =
        mask xor (1 shl (dow.value - 1))
}
