package com.silverbp.android.recognition

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Parsed output of the food-IDENTIFICATION model — the foods on the plate and a
 * rough portion hint, with NO nutrition numbers (those come from
 * [com.silverbp.android.nutrition.NutritionDatabase]). Ported from iOS
 * `FoodRecognitionResult`.
 */
@Serializable
data class ExtractedNutrition(
    val items: List<ExtractedFoodItem> = emptyList(),
    val confidence: Double? = null,
)

@Serializable
data class ExtractedFoodItem(
    /** Most-specific common name; Traditional Chinese for TW/CN dishes. */
    val name: String = "",
    /** Romanised / English name, used for database matching. */
    @SerialName("name_en") val nameEn: String? = null,
    /** Rough visual serving size — a hint, NOT a measurement. */
    @SerialName("portion_hint") val portionHint: String? = null,
    val confidence: Double? = null,
)
