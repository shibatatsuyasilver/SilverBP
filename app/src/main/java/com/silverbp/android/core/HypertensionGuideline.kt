package com.silverbp.android.core

/** Match iOS BPCore.HypertensionGuideline */
enum class HypertensionGuideline(val raw: String, val displayName: String) {
    Jnc8("jnc8", "JNC 8"),
    AccAha2017("accAha2017", "ACC/AHA 2017"),
    Esh2023("esh2023", "ESH 2023"),
    Taiwan2022("taiwan2022", "台灣 2022");

    companion object {
        fun fromRaw(s: String): HypertensionGuideline = entries.first { it.raw == s }
    }
}

/** Match iOS BPCore.BPCategory */
enum class BpCategory {
    Normal, Elevated, Stage1, Stage2, HypertensiveCrisis, Hypotension;
}

/** Match iOS BPCore.GuidelineClassifier */
class GuidelineClassifier(val guideline: HypertensionGuideline) {
    fun classify(systolic: Int, diastolic: Int): BpCategory {
        if (systolic < 90 || diastolic < 60) return BpCategory.Hypotension

        return when (guideline) {
            HypertensionGuideline.AccAha2017,
            HypertensionGuideline.Taiwan2022 -> when {
                systolic >= 180 || diastolic >= 120 -> BpCategory.HypertensiveCrisis
                systolic >= 140 || diastolic >= 90  -> BpCategory.Stage2
                systolic >= 130 || diastolic >= 80  -> BpCategory.Stage1
                systolic >= 120                      -> BpCategory.Elevated
                else                                 -> BpCategory.Normal
            }
            HypertensionGuideline.Jnc8,
            HypertensionGuideline.Esh2023 -> when {
                systolic >= 180 || diastolic >= 120 -> BpCategory.HypertensiveCrisis
                systolic >= 160 || diastolic >= 100 -> BpCategory.Stage2
                systolic >= 140 || diastolic >= 90  -> BpCategory.Stage1
                systolic >= 130 || diastolic >= 85  -> BpCategory.Elevated
                else                                 -> BpCategory.Normal
            }
        }
    }
}
