package com.silverbp.android.ui.nutrition

import com.silverbp.android.nutrition.FoodLog

/**
 * In-memory hand-off of a freshly captured/analysed [FoodLog] draft from the
 * capture step to [NutritionConfirmScreen]. Mirrors the BP CaptureSessionHolder
 * pattern — avoids serialising a bitmap/draft through nav arguments.
 */
object NutritionDraftHolder {
    @Volatile private var draft: FoodLog? = null

    fun put(d: FoodLog) { draft = d }

    /** Consume the pending draft (cleared after read). */
    fun take(): FoodLog? = draft.also { draft = null }
}
