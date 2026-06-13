package com.silverbp.android.core

/**
 * Body-mass-index classification. Uses the **Asia-Pacific / Taiwan (HPA)**
 * cut-points, which are lower than the WHO international bands:
 *   過輕 < 18.5 ≤ 正常 < 24 ≤ 過重 < 27 ≤ 肥胖.
 * BMI = weight(kg) / height(m)². Height comes from the per-member profile
 * ([com.silverbp.android.core.Member.heightCm]); weight from the latest
 * [WeightReading]. Pure functions — no Android deps — so they're unit-testable.
 */
enum class WeightCategory(val raw: String) {
    Underweight("underweight"),
    Normal("normal"),
    Overweight("overweight"),
    Obese("obese"),
}

object WeightGuideline {
    /** BMI from canonical kg + height in cm; null when height is missing/invalid. */
    fun bmi(weightKg: Double, heightCm: Int?): Double? {
        val h = heightCm ?: return null
        if (h <= 0) return null
        val m = h / 100.0
        return weightKg / (m * m)
    }

    /** Asia-Pacific (Taiwan HPA) classification. */
    fun classify(bmi: Double): WeightCategory = when {
        bmi < 18.5 -> WeightCategory.Underweight
        bmi < 24.0 -> WeightCategory.Normal
        bmi < 27.0 -> WeightCategory.Overweight
        else -> WeightCategory.Obese
    }
}
