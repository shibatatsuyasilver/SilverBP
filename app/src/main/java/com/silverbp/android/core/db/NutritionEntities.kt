package com.silverbp.android.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistence row for a logged meal. Domain type [com.silverbp.android.
 * nutrition.FoodLog] — keep enum raw values + nullable fields in sync via
 * [NutritionMappers].
 *
 * No FK back into BP/exercise tables, so the Nutrition feature stays
 * independent. `hlcUpdatedAt` carries cross-device LWW; `hcRecordId` is the
 * device-local Health Connect mirror id (never synced — a fresh device
 * re-mirrors and gets its own id, mirroring BpReadingEntity).
 */
@Entity(tableName = "food_log", indices = [Index("timestamp")])
data class FoodLogEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val mealTypeRaw: String,
    val inputMethodRaw: String,
    val description: String,
    val photoFilename: String?,
    val barcode: String?,
    val productName: String?,
    /** JSON array of [com.silverbp.android.nutrition.FoodItem]; "[]" when empty. */
    val itemsJson: String,
    val caloriesKcal: Double?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    val sugarG: Double?,
    val fiberG: Double?,
    val sodiumMg: Double?,
    val sodiumMgLow: Double?,
    val sodiumMgHigh: Double?,
    /** "low" | "mid" | "high" — primary sodium surface. */
    val sodiumLevelRaw: String,
    /** "label" | "estimate" | "manual". */
    val sodiumSourceRaw: String,
    val confidence: Double,
    /** "ai_local" | "ai_cloud" | "ai_aicore" | "barcode" | "manual". */
    val analysisBackendRaw: String,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
    val hlcUpdatedAt: String = "0",
    val hcRecordId: String? = null,
)
