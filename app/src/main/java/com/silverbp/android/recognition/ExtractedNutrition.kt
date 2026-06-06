package com.silverbp.android.recognition

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Parsed nutrition estimate from a food photo — the nutrition analogue of
 * [ExtractedReading]. All fields nullable: the model omits what it can't tell.
 *
 * Sodium is deliberately modelled as a RANGE (`sodiumMgLow..sodiumMgHigh`) plus
 * a coarse [sodiumLevel], not a single precise mg — photo-derived sodium is
 * essentially uncorrelated with real intake, so we never present false
 * precision (see the deep-research findings).
 */
@Serializable
data class ExtractedNutrition(
    val description: String? = null,
    val items: List<ExtractedFoodItem> = emptyList(),
    @SerialName("calories_kcal") val caloriesKcal: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("sugar_g") val sugarG: Double? = null,
    @SerialName("fiber_g") val fiberG: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
    @SerialName("sodium_mg_low") val sodiumMgLow: Double? = null,
    @SerialName("sodium_mg_high") val sodiumMgHigh: Double? = null,
    /** "low" | "mid" | "high". */
    @SerialName("sodium_level") val sodiumLevel: String? = null,
    val confidence: Double? = null,
)

@Serializable
data class ExtractedFoodItem(
    val name: String = "",
    @SerialName("calories_kcal") val caloriesKcal: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
)
