package com.silverbp.android.nutrition

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/** Meal slot a food log belongs to. Raw values stable for persistence/sync. */
enum class MealType(val raw: String) {
    Breakfast("breakfast"),
    Lunch("lunch"),
    Dinner("dinner"),
    Snack("snack");

    companion object {
        fun fromRaw(s: String): MealType = entries.firstOrNull { it.raw == s } ?: Snack
    }
}

/** How a food log was captured. */
enum class NutritionInputMethod(val raw: String) {
    Photo("photo"),
    Barcode("barcode"),
    Manual("manual");

    companion object {
        fun fromRaw(s: String): NutritionInputMethod = entries.firstOrNull { it.raw == s } ?: Manual
    }
}

/**
 * Coarse sodium band — the PRIMARY way sodium is surfaced.
 *
 * Per the 2024–2026 literature, photo-derived sodium is essentially
 * uncorrelated with weighed intake (r≈0.015), so we never present a single
 * precise mg as authoritative; the level + a range communicate the
 * uncertainty honestly. Aligns with the Coach module's existing
 * `diet_check.sodiumLevelRaw` (low/mid/high).
 */
enum class SodiumLevel(val raw: String) {
    Low("low"),
    Mid("mid"),
    High("high");

    companion object {
        fun fromRaw(s: String): SodiumLevel = entries.firstOrNull { it.raw == s } ?: Mid

        /** Per-meal heuristic bands (AHA daily ceiling 2000 mg ≈ ~650 mg/meal). */
        fun forMealMg(mg: Double?): SodiumLevel = when {
            mg == null -> Mid
            mg < 500.0 -> Low
            mg <= 1000.0 -> Mid
            else -> High
        }
    }
}

/**
 * Where a sodium value came from — governs how much to trust it.
 * `Label` (packaged-food barcode lookup) is the only reliable source;
 * `Estimate` (photo AI) is low-confidence by design.
 */
enum class SodiumSource(val raw: String) {
    Label("label"),
    Estimate("estimate"),
    Manual("manual");

    companion object {
        fun fromRaw(s: String): SodiumSource = entries.firstOrNull { it.raw == s } ?: Estimate
    }
}

/**
 * One identified item within a meal. Persisted as JSON inside
 * [com.silverbp.android.core.db.FoodLogEntity.itemsJson] so the per-item
 * breakdown can evolve without a schema migration.
 */
@Serializable
data class FoodItem(
    val name: String,
    val nameEn: String? = null,
    /** Serving in grams used to compute this item's nutrition from the DB. */
    val grams: Double? = null,
    val caloriesKcal: Double? = null,
    val sodiumMg: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
)

/** Small / medium / large serving — multiplies a food's DB default grams. */
enum class Portion(val raw: String, val multiplier: Double) {
    Small("small", 0.6),
    Medium("medium", 1.0),
    Large("large", 1.5);

    fun grams(defaultGrams: Double): Double = defaultGrams * multiplier

    companion object {
        /** Map the model's portion_hint ("small|medium|large") to a [Portion]. */
        fun fromHint(hint: String?): Portion =
            entries.firstOrNull { it.raw == hint?.lowercase() } ?: Medium
    }
}

/** Meal slot inferred from the current hour (mirrors iOS inferMealType). */
fun currentMealType(): MealType = when (java.time.LocalTime.now().hour) {
    in 4..10 -> MealType.Breakfast
    in 11..14 -> MealType.Lunch
    in 15..20 -> MealType.Dinner
    else -> MealType.Snack
}

/**
 * A logged meal/snack with its (estimated or label-sourced) nutrition.
 * Mirrors the BP/exercise domain style: enum `.raw` discriminators, Instants,
 * UUID id, `hcRecordId` for the Health Connect mirror (device-local).
 */
data class FoodLog(
    val id: UUID = UUID.randomUUID(),
    val timestamp: Instant = Instant.now(),
    val mealType: MealType = MealType.Snack,
    val inputMethod: NutritionInputMethod = NutritionInputMethod.Manual,
    /** Short human label for the meal, e.g. "雞腿便當" or a summary of items. */
    val description: String = "",
    val photoFilename: String? = null,
    val barcode: String? = null,
    val productName: String? = null,
    val items: List<FoodItem> = emptyList(),
    val calories: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val sugarG: Double? = null,
    val fiberG: Double? = null,
    /** Point estimate (or label value). Display de-emphasised vs [sodiumLevel]. */
    val sodiumMg: Double? = null,
    val sodiumMgLow: Double? = null,
    val sodiumMgHigh: Double? = null,
    val sodiumLevel: SodiumLevel = SodiumLevel.Mid,
    val sodiumSource: SodiumSource = SodiumSource.Estimate,
    val confidence: Double = 1.0,
    /** "ai_local" | "ai_cloud" | "ai_aicore" | "barcode" | "manual". */
    val analysisBackend: String = "",
    val note: String = "",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    /** Health Connect record id once mirrored (null = pending). Device-local. */
    val hcRecordId: String? = null,
)
