package com.silverbp.android.core.db

import com.silverbp.android.nutrition.FoodItem
import com.silverbp.android.nutrition.FoodLog
import com.silverbp.android.nutrition.MealType
import com.silverbp.android.nutrition.NutritionInputMethod
import com.silverbp.android.nutrition.SodiumLevel
import com.silverbp.android.nutrition.SodiumSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

private val nutritionJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun encodeItems(items: List<FoodItem>): String =
    nutritionJson.encodeToString(items)

private fun decodeItems(json: String): List<FoodItem> =
    runCatching { nutritionJson.decodeFromString<List<FoodItem>>(json) }.getOrDefault(emptyList())

fun FoodLog.toEntity() = FoodLogEntity(
    id = id.toString(),
    timestamp = timestamp.toEpochMilli(),
    mealTypeRaw = mealType.raw,
    inputMethodRaw = inputMethod.raw,
    description = description,
    photoFilename = photoFilename,
    barcode = barcode,
    productName = productName,
    itemsJson = encodeItems(items),
    caloriesKcal = calories,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    sugarG = sugarG,
    fiberG = fiberG,
    sodiumMg = sodiumMg,
    sodiumMgLow = sodiumMgLow,
    sodiumMgHigh = sodiumMgHigh,
    sodiumLevelRaw = sodiumLevel.raw,
    sodiumSourceRaw = sodiumSource.raw,
    confidence = confidence,
    analysisBackendRaw = analysisBackend,
    note = note,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    hcRecordId = hcRecordId,
)

fun FoodLogEntity.toDomain() = FoodLog(
    id = UUID.fromString(id),
    timestamp = Instant.ofEpochMilli(timestamp),
    mealType = MealType.fromRaw(mealTypeRaw),
    inputMethod = NutritionInputMethod.fromRaw(inputMethodRaw),
    description = description,
    photoFilename = photoFilename,
    barcode = barcode,
    productName = productName,
    items = decodeItems(itemsJson),
    calories = caloriesKcal,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    sugarG = sugarG,
    fiberG = fiberG,
    sodiumMg = sodiumMg,
    sodiumMgLow = sodiumMgLow,
    sodiumMgHigh = sodiumMgHigh,
    sodiumLevel = SodiumLevel.fromRaw(sodiumLevelRaw),
    sodiumSource = SodiumSource.fromRaw(sodiumSourceRaw),
    confidence = confidence,
    analysisBackend = analysisBackendRaw,
    note = note,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    hcRecordId = hcRecordId,
)
