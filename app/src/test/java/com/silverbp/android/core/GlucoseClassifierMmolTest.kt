package com.silverbp.android.core

import com.silverbp.android.ui.confirm.GlucoseDraft
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the **direct mmol/L entry** classification path, which the round-trip test
 * (mgdl→mmol→mgdl from integer mg/dL) never exercised.
 *
 * Regression for the round-2 finding: 11.1 mmol/L (the exact ADA/WHO 2-hour OGTT
 * diabetes cut-point) builds a canonical 199.978 mg/dL, which displays as "200"
 * but slipped just under the literal `>= 200.0` gate and classified Elevated
 * (prediabetes) instead of High (diabetes-range) — a persisted display/category
 * contradiction. [GlucoseClassifier.BOUNDARY_EPSILON] snaps the ≥ cut-points back
 * onto the displayed integer. These tests assert the canonical value derived
 * exactly as the save flow does ([GlucoseReading.mgdlFrom]) lands in the right
 * band, and that the hypoglycaemia `<` boundaries are unchanged.
 */
class GlucoseClassifierMmolTest {

    private val classifier = GlucoseClassifier()

    /** Canonical mg/dL from a mmol/L entry, exactly as the save flow computes it. */
    private fun mgdl(mmol: Double) = GlucoseReading.mgdlFrom(mmol, GlucoseUnit.Mmol)

    private fun classifyMmol(mmol: Double, context: MeasureContext) =
        classifier.classify(mgdl(mmol), context)

    @Test
    fun post_meal_11_1_mmol_is_high_not_elevated() {
        // The reported bug: 11.1 mmol = 199.978 mg/dL, displayed "200".
        for (context in listOf(MeasureContext.AfterMeal, MeasureContext.Random, MeasureContext.Bedtime)) {
            assertEquals(
                "11.1 mmol/L ($context) must be High (diabetes-range), not Elevated",
                GlucoseCategory.High,
                classifyMmol(11.1, context),
            )
        }
    }

    @Test
    fun displayed_value_and_category_agree_at_11_1_mmol() {
        // The contradiction was "200 mg/dL" shown with an Elevated badge.
        val canonical = mgdl(11.1)
        assertEquals(
            "11.1 mmol/L must display as 200 mg/dL",
            "200",
            GlucoseDraft.formatValue(canonical, GlucoseUnit.Mgdl),
        )
        assertEquals(
            "displayed 200 must read High",
            GlucoseCategory.High,
            classifier.classify(canonical, MeasureContext.AfterMeal),
        )
    }

    @Test
    fun fasting_mmol_diagnostic_cut_points() {
        // 7.0 mmol = 126.11 mg/dL (diabetes); 5.6 mmol = 100.89 mg/dL (prediabetes).
        assertEquals(GlucoseCategory.High, classifyMmol(7.0, MeasureContext.Fasting))
        assertEquals(GlucoseCategory.Elevated, classifyMmol(5.6, MeasureContext.Fasting))
        // Just below each cut-point must NOT upgrade.
        assertEquals(GlucoseCategory.Elevated, classifyMmol(6.9, MeasureContext.Fasting))
        assertEquals(GlucoseCategory.Normal, classifyMmol(5.5, MeasureContext.Fasting))
    }

    @Test
    fun post_meal_mmol_elevated_cut_point() {
        // 7.8 mmol = 140.5 mg/dL (impaired glucose tolerance / prediabetes).
        assertEquals(GlucoseCategory.Elevated, classifyMmol(7.8, MeasureContext.AfterMeal))
        // Just below 11.1 must stay Elevated, not falsely upgrade to High.
        assertEquals(GlucoseCategory.Elevated, classifyMmol(11.0, MeasureContext.AfterMeal))
    }

    @Test
    fun hypoglycaemia_mmol_boundaries_are_unchanged() {
        // The < boundaries are exact per roadmap §4-1 and the epsilon must not touch them.
        // 3.0 mmol = 54.05 mg/dL -> Low band (54–69), NOT VeryLow.
        assertEquals(GlucoseCategory.Low, classifyMmol(3.0, MeasureContext.AfterMeal))
        // 2.9 mmol = 52.2 mg/dL -> VeryLow.
        assertEquals(GlucoseCategory.VeryLow, classifyMmol(2.9, MeasureContext.AfterMeal))
        // 3.9 mmol = 70.26 mg/dL -> Normal (spec defines Low as < 70 mg/dL).
        assertEquals(GlucoseCategory.Normal, classifyMmol(3.9, MeasureContext.AfterMeal))
    }

    @Test
    fun direct_mgdl_cut_points_are_not_shifted_by_the_epsilon() {
        // A meter reading exactly on the cut classifies as the original spec intends;
        // one below stays in the lower band. (Guards against the epsilon over-reaching.)
        assertEquals(GlucoseCategory.High, classifier.classify(200.0, MeasureContext.AfterMeal))
        assertEquals(GlucoseCategory.Elevated, classifier.classify(199.0, MeasureContext.AfterMeal))
        assertEquals(GlucoseCategory.High, classifier.classify(126.0, MeasureContext.Fasting))
        assertEquals(GlucoseCategory.Elevated, classifier.classify(125.0, MeasureContext.Fasting))
    }
}
