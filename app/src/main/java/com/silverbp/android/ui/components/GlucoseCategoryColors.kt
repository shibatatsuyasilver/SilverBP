package com.silverbp.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.silverbp.android.R
import com.silverbp.android.core.GlucoseCategory
import com.silverbp.android.ui.theme.CategoryCrisis
import com.silverbp.android.ui.theme.CategoryElevated
import com.silverbp.android.ui.theme.CategoryHypotension
import com.silverbp.android.ui.theme.CategoryNormal
import com.silverbp.android.ui.theme.CategoryStage2

/**
 * Category colours for blood glucose, the [colorFor] (BP) sibling. Reuses the
 * shared category palette so the two measurement types read consistently: the
 * two hypoglycaemia bands borrow the cool/blue + red accents (low glucose is the
 * acute case, like a crisis), Normal is green, Elevated is yellow, High is red.
 */
@Composable
@ReadOnlyComposable
fun glucoseColorFor(category: GlucoseCategory): Color = when (category) {
    GlucoseCategory.VeryLow -> CategoryCrisis        // purple — acute, same weight as a BP crisis
    GlucoseCategory.Low -> CategoryHypotension       // blue — low, mirrors hypotension
    GlucoseCategory.Normal -> CategoryNormal         // green
    GlucoseCategory.Elevated -> CategoryElevated     // yellow — prediabetes range
    GlucoseCategory.High -> CategoryStage2           // red — diabetes range
}

/** Localized label for a [GlucoseCategory] (Composable; uses the glucose string set). */
@Composable
@ReadOnlyComposable
fun glucoseCategoryLabel(category: GlucoseCategory): String = stringResource(
    when (category) {
        GlucoseCategory.VeryLow -> R.string.category_verylow
        GlucoseCategory.Low -> R.string.category_low
        GlucoseCategory.Normal -> R.string.category_normal
        GlucoseCategory.Elevated -> R.string.category_elevated
        GlucoseCategory.High -> R.string.category_high
    },
)

/** Localized label for a [com.silverbp.android.core.MeasureContext]. */
@Composable
@ReadOnlyComposable
fun measureContextLabel(context: com.silverbp.android.core.MeasureContext): String = stringResource(
    when (context) {
        com.silverbp.android.core.MeasureContext.Fasting -> R.string.context_fasting
        com.silverbp.android.core.MeasureContext.BeforeMeal -> R.string.context_before_meal
        com.silverbp.android.core.MeasureContext.AfterMeal -> R.string.context_after_meal
        com.silverbp.android.core.MeasureContext.Bedtime -> R.string.context_bedtime
        com.silverbp.android.core.MeasureContext.Random -> R.string.context_random
    },
)

/** Localized label for a [com.silverbp.android.core.GlucoseUnit]. */
@Composable
@ReadOnlyComposable
fun glucoseUnitLabel(unit: com.silverbp.android.core.GlucoseUnit): String = stringResource(
    when (unit) {
        com.silverbp.android.core.GlucoseUnit.Mgdl -> R.string.glucose_unit_mgdl
        com.silverbp.android.core.GlucoseUnit.Mmol -> R.string.glucose_unit_mmol
    },
)

/**
 * Formats a canonical mg/dL value for display in [unit]: an integer for mg/dL
 * (the Taiwanese meter convention), one decimal place for mmol/L. mg/dL
 * **rounds** rather than truncates so display matches the value the user saved
 * (a stored 199.978 from a mmol round-trip shows as 200, not 199) — consistent
 * with [com.silverbp.android.ui.confirm.GlucoseDraft.Companion.formatValue].
 */
fun formatGlucoseValue(valueMgdl: Double, unit: com.silverbp.android.core.GlucoseUnit): String =
    when (unit) {
        com.silverbp.android.core.GlucoseUnit.Mgdl -> Math.round(valueMgdl).toString()
        com.silverbp.android.core.GlucoseUnit.Mmol ->
            "%.1f".format(com.silverbp.android.core.GlucoseUnit.mgdlToMmol(valueMgdl))
    }
