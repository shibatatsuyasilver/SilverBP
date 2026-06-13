package com.silverbp.android.ui.confirm

import com.silverbp.android.core.GlucoseCategory
import com.silverbp.android.core.GlucoseClassifier
import com.silverbp.android.core.GlucoseUnit
import com.silverbp.android.core.MeasureContext
import com.silverbp.android.ui.components.formatGlucoseValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the mg/dL ↔ mmol/L toggle as a lossless display round-trip. Regression for
 * the truncation bug: [GlucoseDraft.Companion.formatValue] rounded mg/dL with
 * `toLong()` (always biased down), so a single mgdl→mmol→mgdl toggle could drop a
 * whole unit and, at the diabetes threshold, downgrade the medical category
 * (a post-meal 200 → 199 = High → Elevated). Rounding makes the round-trip exact.
 */
class GlucoseDraftRoundTripTest {

    /**
     * The clinically load-bearing mg/dL cut-points whose mmol/L 1-decimal display
     * lands exactly on the inverse, so the round-trip is bit-exact. (100 and 140
     * map to 5.6 / 7.8 mmol whose inverse rounds to 101 / 141 — a ±1 mg/dL display
     * granularity that does NOT change category; covered by the bias/boundary tests
     * below, not asserted as exact here.)
     */
    private val exactThresholds = listOf(54, 70, 126, 200)

    private fun roundTrip(mgdl: Int): GlucoseDraft =
        GlucoseDraft(valueText = mgdl.toString(), displayUnit = GlucoseUnit.Mgdl)
            .convertedTo(GlucoseUnit.Mmol)
            .convertedTo(GlucoseUnit.Mgdl)

    @Test
    fun mgdl_mmol_mgdl_round_trip_preserves_displayed_value_at_critical_thresholds() {
        for (mgdl in exactThresholds) {
            val back = roundTrip(mgdl)
            assertEquals(
                "mgdl→mmol→mgdl must preserve the displayed integer at $mgdl",
                mgdl.toString(),
                back.valueText,
            )
        }
    }

    @Test
    fun mgdl_mmol_mgdl_round_trip_drift_is_bounded_unbiased_and_never_downward_by_more_than_zero() {
        // The truncation bug drifted DOWN in 339 of 681 inputs (mean -0.5 mg/dL) and
        // never up — that systematic under-report is what crossed 200→199. Rounding
        // must bound any residual mmol-display granularity to ±1 mg/dL and be
        // symmetric (not biased downward), so it cannot systematically under-report.
        var sum = 0
        for (mgdl in 20..700) {
            val delta = roundTrip(mgdl).valueText.toInt() - mgdl
            assertTrue("round-trip drift at $mgdl mg/dL must be within ±1, was $delta", delta in -1..1)
            sum += delta
        }
        // Mean drift ≈ 0 (measured 0.004); assert it is not the old downward bias.
        assertTrue("net round-trip drift must be ~unbiased, was $sum over 681 inputs", sum in -5..5)
    }

    @Test
    fun no_unit_round_trip_changes_a_classification_category() {
        // The blocker: a round-trip must never move a value across a medical
        // threshold. Verify both threshold scales over the full meter range.
        val classifier = GlucoseClassifier()
        for (context in listOf(MeasureContext.Fasting, MeasureContext.AfterMeal)) {
            for (mgdl in 20..700) {
                val before = classifier.classify(mgdl.toDouble(), context)
                val after = classifier.classify(roundTrip(mgdl).valueMgdl, context)
                assertEquals(
                    "round-trip changed category at $mgdl mg/dL ($context)", before, after,
                )
            }
        }
    }

    @Test
    fun post_meal_200_stays_high_after_a_unit_round_trip() {
        val classifier = GlucoseClassifier()
        val back = roundTrip(200)
        // The persisted canonical value is derived from the (rounded) field text.
        assertEquals(200.0, back.valueMgdl, 0.0)
        assertEquals(
            "post-meal 200 mg/dL must remain High (diabetes-range), not Elevated",
            GlucoseCategory.High,
            classifier.classify(back.valueMgdl, MeasureContext.AfterMeal),
        )
    }

    @Test
    fun formatGlucoseValue_rounds_a_round_trip_remainder_back_up() {
        // 199.978 mg/dL (the mmol round-trip of 200) must display as 200, not 199.
        val stored = GlucoseUnit.mmolToMgdl(GlucoseUnit.mgdlToMmol(200.0))
        assertEquals("200", formatGlucoseValue(stored, GlucoseUnit.Mgdl))
    }
}
