package com.silverbp.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the BMI maths + Taiwan-standard category thresholds and the kg↔lb
 * conversion. The owner-screenshot case (BMI 24.4 → 過重/Overweight) is the
 * anchor: it must classify as [BmiCategory.Overweight].
 */
class BmiCalculatorTest {

    @Test fun bmi_computesFromWeightAndHeight() {
        // 70 kg @ 170 cm → 70 / 1.7² = 24.221...
        assertEquals(24.22, BmiCalculator.bmi(70.0, 170), 0.01)
        // 60 kg @ 160 cm → 60 / 1.6² = 23.4375
        assertEquals(23.44, BmiCalculator.bmi(60.0, 160), 0.01)
    }

    @Test fun bmi_nonPositiveHeightIsSafe() {
        assertEquals(0.0, BmiCalculator.bmi(70.0, 0), 0.0)
        assertEquals(0.0, BmiCalculator.bmi(70.0, -10), 0.0)
    }

    @Test fun ownerScreenshot_bmi244_isOverweight() {
        // 70.5 kg @ 170 cm → 24.39 → 過重 (matches the owner screenshot's 24.4).
        val bmi = BmiCalculator.bmi(70.5, 170)
        assertEquals(24.39, bmi, 0.01)
        assertEquals(BmiCategory.Overweight, BmiCategory.classify(bmi))
        assertEquals(BmiCategory.Overweight, BmiCategory.classify(24.4))
    }

    @Test fun taiwanThresholds_classifyAtBoundaries() {
        assertEquals(BmiCategory.Underweight, BmiCategory.classify(18.4))
        assertEquals(BmiCategory.Normal, BmiCategory.classify(18.5))
        assertEquals(BmiCategory.Normal, BmiCategory.classify(23.9))
        assertEquals(BmiCategory.Overweight, BmiCategory.classify(24.0))
        assertEquals(BmiCategory.Overweight, BmiCategory.classify(26.9))
        assertEquals(BmiCategory.Obese, BmiCategory.classify(27.0))
        assertEquals(BmiCategory.Obese, BmiCategory.classify(35.0))
    }

    @Test fun category_convenienceMatchesClassify() {
        // 90 kg @ 170 cm → 31.1 → 肥胖.
        assertEquals(BmiCategory.Obese, BmiCalculator.category(90.0, 170))
    }

    @Test fun weightUnit_kgLbRoundTrip() {
        assertEquals(2.20462, WeightUnit.kgToLb(1.0), 1e-9)
        assertEquals(154.3234, WeightUnit.kgToLb(70.0), 1e-3)
        assertEquals(70.0, WeightUnit.lbToKg(WeightUnit.kgToLb(70.0)), 1e-9)
        assertEquals(1.0, WeightUnit.lbToKg(2.20462), 1e-9)
    }

    @Test fun weightUnit_fromRawFallsBackToKg() {
        assertEquals(WeightUnit.Kg, WeightUnit.fromRaw("kg"))
        assertEquals(WeightUnit.Lb, WeightUnit.fromRaw("lb"))
        assertEquals(WeightUnit.Kg, WeightUnit.fromRaw("garbage"))
    }

    @Test fun weightReading_valueInAndKgFrom() {
        val r = WeightReading(weightKg = 70.0, timestamp = java.time.Instant.EPOCH)
        assertEquals(70.0, r.valueIn(WeightUnit.Kg), 1e-9)
        assertEquals(154.3234, r.valueIn(WeightUnit.Lb), 1e-3)
        // Entering 154.3234 lb yields 70.0 kg canonical.
        assertEquals(70.0, WeightReading.kgFrom(154.3234, WeightUnit.Lb), 1e-3)
        assertEquals(70.0, WeightReading.kgFrom(70.0, WeightUnit.Kg), 1e-9)
    }
}
