package com.silverbp.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.silverbp.android.core.BpCategory
import com.silverbp.android.core.GuidelineClassifier
import com.silverbp.android.core.HypertensionGuideline
import com.silverbp.android.ui.theme.CategoryCrisis
import com.silverbp.android.ui.theme.CategoryElevated
import com.silverbp.android.ui.theme.CategoryHypotension
import com.silverbp.android.ui.theme.CategoryNormal
import com.silverbp.android.ui.theme.CategoryStage1
import com.silverbp.android.ui.theme.CategoryStage2
import java.util.Locale

@Composable
@ReadOnlyComposable
fun colorFor(category: BpCategory): Color = when (category) {
    BpCategory.Normal -> CategoryNormal
    BpCategory.Elevated -> CategoryElevated
    BpCategory.Stage1 -> CategoryStage1
    BpCategory.Stage2 -> CategoryStage2
    BpCategory.HypertensiveCrisis -> CategoryCrisis
    BpCategory.Hypotension -> CategoryHypotension
}

/**
 * Locale-aware short label for a [BpCategory]. Used both in Composables
 * (chart legends, today screen) and from non-Composable code that builds
 * LLM context, so it picks zh-TW vs en via [Locale.getDefault] rather than
 * Compose's `stringResource`. Android 13+ per-app language overrides are
 * applied to the default locale, so this stays consistent with UI strings.
 */
fun categoryLabel(category: BpCategory): String {
    val zh = Locale.getDefault().language.equals("zh", ignoreCase = true)
    return if (zh) when (category) {
        BpCategory.Normal -> "正常"
        BpCategory.Elevated -> "偏高"
        BpCategory.Stage1 -> "高血壓 1 期"
        BpCategory.Stage2 -> "高血壓 2 期"
        BpCategory.HypertensiveCrisis -> "高血壓危象"
        BpCategory.Hypotension -> "低血壓"
    } else when (category) {
        BpCategory.Normal -> "Normal"
        BpCategory.Elevated -> "Elevated"
        BpCategory.Stage1 -> "Stage 1 hypertension"
        BpCategory.Stage2 -> "Stage 2 hypertension"
        BpCategory.HypertensiveCrisis -> "Hypertensive crisis"
        BpCategory.Hypotension -> "Hypotension"
    }
}

/**
 * Compact [BpCategory] label for tight layouts (e.g. heatmap column headers),
 * where [categoryLabel]'s full "高血壓 1 期" / "Stage 1 hypertension" won't fit.
 * Same locale handling as [categoryLabel].
 */
fun categoryShortLabel(category: BpCategory): String {
    val zh = Locale.getDefault().language.equals("zh", ignoreCase = true)
    return if (zh) when (category) {
        BpCategory.Normal -> "正常"
        BpCategory.Elevated -> "偏高"
        BpCategory.Stage1 -> "1期"
        BpCategory.Stage2 -> "2期"
        BpCategory.HypertensiveCrisis -> "危象"
        BpCategory.Hypotension -> "低壓"
    } else when (category) {
        BpCategory.Normal -> "Normal"
        BpCategory.Elevated -> "Elev"
        BpCategory.Stage1 -> "S1"
        BpCategory.Stage2 -> "S2"
        BpCategory.HypertensiveCrisis -> "Crisis"
        BpCategory.Hypotension -> "Low"
    }
}

fun classify(systolic: Int, diastolic: Int, guideline: HypertensionGuideline = HypertensionGuideline.Taiwan2022): BpCategory =
    GuidelineClassifier(guideline).classify(systolic, diastolic)
