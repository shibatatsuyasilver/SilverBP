package com.silverbp.android.ui.nutrition

import com.silverbp.android.nutrition.FoodLog
import com.silverbp.android.recognition.ExtractedFoodItem

/**
 * A freshly recognised meal from the photo flow: the identified foods (no
 * numbers — those come from NutritionDatabase at the portion step) plus the
 * saved photo. Handed to [NutritionConfirmScreen] which lets the user pick
 * portions and computes nutrition.
 */
data class RecognizedMeal(
    val photoFilename: String?,
    val items: List<ExtractedFoodItem>,
    val overallConfidence: Double?,
    val backendTag: String,
)

/**
 * In-memory hand-off from the capture step to [NutritionConfirmScreen]. Two
 * channels: a [RecognizedMeal] (photo → portion + DB lookup) or a flat
 * [FoodLog] draft (barcode / manual). Mirrors the BP CaptureSessionHolder
 * pattern — avoids serialising a draft through nav arguments.
 */
object NutritionDraftHolder {
    @Volatile private var draft: FoodLog? = null
    @Volatile private var meal: RecognizedMeal? = null

    fun put(d: FoodLog) { draft = d; meal = null }
    fun putMeal(m: RecognizedMeal) { meal = m; draft = null }

    /** Consume the pending flat draft (cleared after read). */
    fun take(): FoodLog? = draft.also { draft = null }

    /** Consume the pending recognised meal (cleared after read). */
    fun takeMeal(): RecognizedMeal? = meal.also { meal = null }
}
