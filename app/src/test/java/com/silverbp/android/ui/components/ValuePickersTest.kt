package com.silverbp.android.ui.components

import com.silverbp.android.core.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the pure index/value maths behind the weight wheel ([WeightWheel]). The
 * wheel shows display-unit values at 0.1 resolution while storage is canonical
 * kg, so the round-trips (display index ↔ value, kg ↔ index) are where a bug
 * would silently shift a recorded weight. kg round-trips are exact; lb round-trips
 * carry at most one 0.1 lb step (~0.045 kg) of jitter.
 */
class ValuePickersTest {

    @Test fun count_kgRangeIs20to300InTenthSteps() {
        // (300 - 20) / 0.1 + 1 = 2801 positions inclusive of both ends.
        assertEquals(2801, WeightWheel.count(WeightUnit.Kg))
    }

    @Test fun valueAt_endsAreTheKgBounds() {
        assertEquals(20.0, WeightWheel.valueAt(0, WeightUnit.Kg), 1e-6)
        assertEquals(
            300.0,
            WeightWheel.valueAt(WeightWheel.count(WeightUnit.Kg) - 1, WeightUnit.Kg),
            1e-6,
        )
    }

    @Test fun valueAt_indexOf_roundTripsInKg() {
        for (i in listOf(0, 1, 250, 500, 1234, 2800)) {
            val v = WeightWheel.valueAt(i, WeightUnit.Kg)
            assertEquals(i, WeightWheel.indexOf(v, WeightUnit.Kg))
        }
    }

    @Test fun indexOfKg_kgAt_roundTripsExactlyInKg() {
        for (kg in listOf(20.0, 55.5, 70.0, 99.9, 123.4, 300.0)) {
            val idx = WeightWheel.indexOfKg(kg, WeightUnit.Kg)
            assertEquals(kg, WeightWheel.kgAt(idx, WeightUnit.Kg), 1e-6)
        }
    }

    @Test fun indexOfKg_kgAt_roundTripsWithinAStepInLb() {
        // lb wheel steps 0.1 lb ≈ 0.045 kg, so a kg→lb-index→kg round-trip lands
        // within half a step of the original.
        for (kg in listOf(20.0, 50.0, 70.0, 88.8, 150.0, 300.0)) {
            val idx = WeightWheel.indexOfKg(kg, WeightUnit.Lb)
            assertEquals(kg, WeightWheel.kgAt(idx, WeightUnit.Lb), 0.06)
        }
    }

    @Test fun indexOf_coercesOutOfRangeToBounds() {
        assertEquals(0, WeightWheel.indexOf(-999.0, WeightUnit.Kg))
        assertEquals(0, WeightWheel.indexOf(5.0, WeightUnit.Kg)) // below 20 kg floor
        val last = WeightWheel.count(WeightUnit.Kg) - 1
        assertEquals(last, WeightWheel.indexOf(9999.0, WeightUnit.Kg))
    }

    @Test fun indexOfKg_belowFloorClampsToMinKg() {
        // A 5 kg toddler can't be selected; the wheel pins to its 20 kg floor.
        val idx = WeightWheel.indexOfKg(5.0, WeightUnit.Kg)
        assertEquals(WeightWheel.KG_MIN, WeightWheel.kgAt(idx, WeightUnit.Kg), 1e-6)
    }

    @Test fun lbRange_isWiderInCountThanKg_andPositive() {
        // lb numbers are larger, so at the same 0.1 step the lb wheel has more rows.
        assertTrue(WeightWheel.count(WeightUnit.Lb) > WeightWheel.count(WeightUnit.Kg))
        assertEquals(WeightUnit.kgToLb(WeightWheel.KG_MIN), WeightWheel.displayMin(WeightUnit.Lb), 1e-6)
        assertEquals(WeightUnit.kgToLb(WeightWheel.KG_MAX), WeightWheel.displayMax(WeightUnit.Lb), 1e-6)
    }

    @Test fun formatDisplay_alwaysOneDecimal() {
        assertEquals("70.0", WeightWheel.formatDisplay(70.0))
        assertEquals("70.5", WeightWheel.formatDisplay(70.5))
        assertEquals("99.9", WeightWheel.formatDisplay(99.9))
    }
}
