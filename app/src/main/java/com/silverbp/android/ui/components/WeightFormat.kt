package com.silverbp.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.silverbp.android.R
import com.silverbp.android.core.BmiCategory
import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightUnit
import com.silverbp.android.ui.theme.CategoryElevated
import com.silverbp.android.ui.theme.CategoryHypotension
import com.silverbp.android.ui.theme.CategoryNormal
import com.silverbp.android.ui.theme.CategoryStage1
import com.silverbp.android.ui.theme.CategoryStage2

/**
 * Weight + BMI display helpers, the [glucoseColorFor] / [formatGlucoseValue]
 * siblings for the v20 body-weight feature. Kept here next to the other category
 * colour/label/format helpers so the Today card, combined history, and weight
 * insights all render weight consistently without touching the shared BP charts.
 */

/**
 * Category colours for a Taiwan-standard [BmiCategory]. Reuses the shared category
 * palette so weight reads consistently with BP / glucose: Underweight is the cool
 * blue (a deficit, like hypotension), Normal green, Overweight orange (the first
 * warning band, like Stage 1), Obese red (the high band).
 */
@Composable
@ReadOnlyComposable
fun bmiColorFor(category: BmiCategory): Color = when (category) {
    BmiCategory.Underweight -> CategoryHypotension  // blue — a deficit
    BmiCategory.Normal -> CategoryNormal            // green
    BmiCategory.Overweight -> CategoryElevated       // yellow — first warning band
    BmiCategory.Obese -> CategoryStage2             // red — high band
}

/** Localized label for a [BmiCategory] (uses the weight string set). */
@Composable
@ReadOnlyComposable
fun bmiCategoryLabel(category: BmiCategory): String = stringResource(
    when (category) {
        BmiCategory.Underweight -> R.string.bmi_underweight
        BmiCategory.Normal -> R.string.bmi_normal
        BmiCategory.Overweight -> R.string.bmi_overweight
        BmiCategory.Obese -> R.string.bmi_obese
    },
)

/** Localized unit label for a [WeightUnit]. */
@Composable
@ReadOnlyComposable
fun weightUnitLabel(unit: WeightUnit): String = stringResource(
    when (unit) {
        WeightUnit.Kg -> R.string.weight_unit_kg
        WeightUnit.Lb -> R.string.weight_unit_lb
    },
)

/**
 * Formats a canonical kg value for display in [unit], one decimal place (the scale
 * convention for both kg and lb, e.g. "65.4"). Pure — keep in sync with the
 * confirm/editor formatting when that track lands.
 */
fun formatWeightValue(weightKg: Double, unit: WeightUnit): String =
    "%.1f".format(WeightReading(weightKg = weightKg, timestamp = java.time.Instant.EPOCH).valueIn(unit))

/** Formats a BMI value to one decimal place (e.g. 24.4). */
fun formatBmi(bmi: Double): String = "%.1f".format(bmi)
