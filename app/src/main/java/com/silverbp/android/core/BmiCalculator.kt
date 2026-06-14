package com.silverbp.android.core

/**
 * Body Mass Index from a body weight + a member's height, with Taiwan-standard
 * weight-status categories.
 *
 * BMI = weight(kg) / height(m)². Height is stored in cm on the member profile
 * ([com.silverbp.android.core.db.MemberEntity.heightCm]); divide by 100 to metres.
 *
 * Category thresholds follow the **Taiwan Ministry of Health and Welfare /
 * Health Promotion Administration (衛福部國健署)** adult standard, which is
 * stricter than the WHO international cut-offs to better fit Asian body
 * composition / cardiometabolic risk:
 *   - < 18.5      過輕 Underweight
 *   - 18.5 – 23.9 正常 Normal
 *   - 24.0 – 26.9 過重 Overweight
 *   - ≥ 27.0      肥胖 Obese
 *
 * Worked example (owner screenshot): BMI 24.4 → [BmiCategory.Overweight] (過重).
 *
 * Height is required for a BMI; callers must check the member has a height set
 * before calling [bmi] / [category] (the UI shows weight-only with a "set height"
 * hint when it's null). [bmi] guards against a non-positive height defensively
 * (returns 0.0) but the contract is "caller passes a real height".
 */
object BmiCalculator {

    /**
     * BMI = weight(kg) / (height(cm)/100)². Returns 0.0 for a non-positive height
     * (defensive — callers should only pass a member's actual height).
     */
    fun bmi(weightKg: Double, heightCm: Int): Double {
        if (heightCm <= 0) return 0.0
        val heightM = heightCm / 100.0
        return weightKg / (heightM * heightM)
    }

    /** Convenience: the Taiwan-standard category for a weight + height. */
    fun category(weightKg: Double, heightCm: Int): BmiCategory =
        BmiCategory.classify(bmi(weightKg, heightCm))
}

/**
 * Taiwan-standard adult BMI category (衛福部國健署). See [BmiCalculator] for the
 * threshold source and the BMI 24.4 → [Overweight] worked example.
 */
enum class BmiCategory {
    Underweight,
    Normal,
    Overweight,
    Obese;

    companion object {
        /**
         * Classify a BMI value with the Taiwan thresholds:
         * < 18.5 → [Underweight]; 18.5–23.9 → [Normal]; 24.0–26.9 → [Overweight];
         * ≥ 27.0 → [Obese]. BMI 24.4 → [Overweight] (matches the owner screenshot).
         */
        fun classify(bmi: Double): BmiCategory = when {
            bmi < 18.5 -> Underweight
            bmi < 24.0 -> Normal
            bmi < 27.0 -> Overweight
            else -> Obese
        }
    }
}
