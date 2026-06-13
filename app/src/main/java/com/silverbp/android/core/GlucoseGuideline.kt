package com.silverbp.android.core

/**
 * Blood-glucose category, parallel to [BpCategory]. Ordered low → high so the
 * urgent low-glucose cases ([VeryLow], [Low]) sort first — the save flow shows
 * an immediate on-screen warning card for these (low glucose is the acute case;
 * there is deliberately no background watcher, avoiding the M17 alert-storm).
 */
enum class GlucoseCategory {
    VeryLow, Low, Normal, Elevated, High;

    /** True for the two hypoglycaemia bands that trigger the on-save warning card. */
    val isHypoglycemic: Boolean get() = this == VeryLow || this == Low
}

/**
 * Classifies a glucose value (in mg/dL) by [MeasureContext]. Not a medical
 * device — UI surfaces the same disclaimer as the BP flow.
 *
 * Thresholds (mg/dL) per the **ADA Standards of Care in Diabetes** and the
 * **台灣糖尿病學會臨床照護指引 (Taiwan Diabetes Association)**:
 *  - Hypoglycaemia is context-independent: <54 = Level 2 (VeryLow, clinically
 *    significant), 54–69 = Level 1 (Low). The on-save warning card fires for both.
 *  - Fasting / before-meal: 70–99 Normal, 100–125 Elevated (prediabetes /
 *    impaired fasting glucose), ≥126 High (diabetes-range).
 *  - After-meal (≈2 h post-prandial): up to 139 Normal, 140–199 Elevated
 *    (impaired glucose tolerance), ≥200 High.
 *  - Random / bedtime: treated like the post-prandial scale (≥200 High); bedtime
 *    has no distinct diagnostic cut-point in the guidelines, so it follows the
 *    random scale per the roadmap §4-1.
 */
class GlucoseClassifier {

    fun classify(valueMgdl: Double, context: MeasureContext): GlucoseCategory {
        // Hypoglycaemia bands are context-independent and take precedence.
        if (valueMgdl < 54.0) return GlucoseCategory.VeryLow
        if (valueMgdl < 70.0) return GlucoseCategory.Low

        return when (context) {
            MeasureContext.Fasting,
            MeasureContext.BeforeMeal -> when {
                valueMgdl >= 126.0 -> GlucoseCategory.High
                valueMgdl >= 100.0 -> GlucoseCategory.Elevated
                else -> GlucoseCategory.Normal
            }
            // After-meal, random and bedtime share the post-prandial scale.
            MeasureContext.AfterMeal,
            MeasureContext.Random,
            MeasureContext.Bedtime -> when {
                valueMgdl >= 200.0 -> GlucoseCategory.High
                valueMgdl >= 140.0 -> GlucoseCategory.Elevated
                else -> GlucoseCategory.Normal
            }
        }
    }
}
