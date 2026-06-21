package com.silverbp.android.ui.components

import com.silverbp.android.R
import com.silverbp.android.core.HypertensionGuideline

/**
 * The four [HypertensionGuideline] values collapse into two distinct
 * classification standards: within each pair the thresholds — and therefore
 * every reading's category and color — are identical (see GuidelineClassifier).
 * The picker UI offers these two options instead of four so users aren't asked
 * to choose between functionally equivalent guidelines.
 *
 * The underlying enum keeps all four values for iOS parity (BPCore) and sync; a
 * standard maps to a [representative] value that's written only when the user
 * switches *into* it — an existing in-group selection (e.g. ACC/AHA 2017 within
 * the 130/80 group) is left untouched so synced/iOS choices aren't rewritten.
 *
 * Keep the thresholds in sync with GuidelineClassifier.
 */
enum class GuidelineStandard(
    val labelRes: Int,
    val elevatedThreshold: String,
    val threshold: String,
    val members: List<HypertensionGuideline>,
    val representative: HypertensionGuideline,
) {
    /** Elevated from systolic ≥120, high BP from ≥130/80 — Taiwan 2022 & ACC/AHA 2017. */
    Strict(
        labelRes = R.string.guideline_standard_130_80,
        elevatedThreshold = "120 mmHg",
        threshold = "130/80 mmHg",
        members = listOf(HypertensionGuideline.Taiwan2022, HypertensionGuideline.AccAha2017),
        representative = HypertensionGuideline.Taiwan2022,
    ),

    /** Elevated from ≥130/85, high BP from ≥140/90 — JNC 8 & ESH 2023. */
    Conventional(
        labelRes = R.string.guideline_standard_140_90,
        elevatedThreshold = "130/85 mmHg",
        threshold = "140/90 mmHg",
        members = listOf(HypertensionGuideline.Jnc8, HypertensionGuideline.Esh2023),
        representative = HypertensionGuideline.Esh2023,
    );

    companion object {
        /** The standard a given guideline belongs to (every guideline maps to exactly one). */
        fun of(g: HypertensionGuideline): GuidelineStandard = entries.first { g in it.members }
    }
}
