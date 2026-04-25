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

fun chineseLabel(category: BpCategory): String = when (category) {
    BpCategory.Normal -> "正常"
    BpCategory.Elevated -> "偏高"
    BpCategory.Stage1 -> "高血壓 1 期"
    BpCategory.Stage2 -> "高血壓 2 期"
    BpCategory.HypertensiveCrisis -> "高血壓危象"
    BpCategory.Hypotension -> "低血壓"
}

fun classify(systolic: Int, diastolic: Int, guideline: HypertensionGuideline = HypertensionGuideline.Taiwan2022): BpCategory =
    GuidelineClassifier(guideline).classify(systolic, diastolic)
