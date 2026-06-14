package com.silverbp.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.silverbp.android.R
import com.silverbp.android.core.WeightCategory
import com.silverbp.android.ui.theme.CategoryElevated
import com.silverbp.android.ui.theme.CategoryHypotension
import com.silverbp.android.ui.theme.CategoryNormal
import com.silverbp.android.ui.theme.CategoryStage2

/**
 * Category colours for a BMI band, the [colorFor] (BP) / [glucoseColorFor]
 * sibling. Reuses the shared category palette so the measurement types read
 * consistently: 過輕 borrows the cool/blue "below normal" accent, 正常 is green,
 * 過重 is yellow, 肥胖 is red.
 */
@Composable
@ReadOnlyComposable
fun weightColorFor(category: WeightCategory): Color = when (category) {
    WeightCategory.Underweight -> CategoryHypotension // blue — below normal
    WeightCategory.Normal -> CategoryNormal           // green
    WeightCategory.Overweight -> CategoryElevated     // yellow
    WeightCategory.Obese -> CategoryStage2            // red
}

/** Localized label for a [WeightCategory] (Composable; uses the weight string set). */
@Composable
@ReadOnlyComposable
fun weightCategoryLabel(category: WeightCategory): String = stringResource(
    when (category) {
        WeightCategory.Underweight -> R.string.weight_category_underweight
        WeightCategory.Normal -> R.string.weight_category_normal
        WeightCategory.Overweight -> R.string.weight_category_overweight
        WeightCategory.Obese -> R.string.weight_category_obese
    },
)
