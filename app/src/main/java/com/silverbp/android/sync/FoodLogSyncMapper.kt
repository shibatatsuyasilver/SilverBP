package com.silverbp.android.sync

import com.silverbp.android.core.db.FoodLogDao
import com.silverbp.android.core.db.FoodLogEntity
import com.silverbp.android.core.db.SyncDao
import com.silverbp.android.core.db.TombstoneEntity
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.mapping.SyncRecordMapper

/**
 * `SyncRecordMapper` for `food_log` (Nutrition feature). id-keyed like
 * bp_reading. The field-tag layout below is FROZEN once cross-device sync
 * ships — never renumber a tag, only append. `hcRecordId` stays device-local
 * (not synced), mirroring [BpReadingSyncMapper].
 *
 *   1: timestampMs       2: mealTypeRaw      3: inputMethodRaw   4: description
 *   5: photoFilename     6: barcode          7: productName      8: itemsJson
 *   9: caloriesKcal     10: proteinG        11: carbsG          12: fatG
 *  13: sugarG           14: fiberG          15: sodiumMg        16: sodiumMgLow
 *  17: sodiumMgHigh     18: sodiumLevelRaw  19: sodiumSourceRaw 20: confidence
 *  21: analysisBackendRaw 22: note          23: createdAtMs     24: updatedAtMs
 */
class FoodLogSyncMapper(
    private val foodLogDao: FoodLogDao,
    private val syncDao: SyncDao,
) : SyncRecordMapper<FoodLogEntity> {

    private object Field {
        const val TIMESTAMP_MS = 1
        const val MEAL_TYPE_RAW = 2
        const val INPUT_METHOD_RAW = 3
        const val DESCRIPTION = 4
        const val PHOTO_FILENAME = 5
        const val BARCODE = 6
        const val PRODUCT_NAME = 7
        const val ITEMS_JSON = 8
        const val CALORIES = 9
        const val PROTEIN = 10
        const val CARBS = 11
        const val FAT = 12
        const val SUGAR = 13
        const val FIBER = 14
        const val SODIUM_MG = 15
        const val SODIUM_MG_LOW = 16
        const val SODIUM_MG_HIGH = 17
        const val SODIUM_LEVEL_RAW = 18
        const val SODIUM_SOURCE_RAW = 19
        const val CONFIDENCE = 20
        const val ANALYSIS_BACKEND_RAW = 21
        const val NOTE = 22
        const val CREATED_AT_MS = 23
        const val UPDATED_AT_MS = 24
    }

    override fun encode(entity: FoodLogEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            Field.TIMESTAMP_MS to SyncValue.Int64(entity.timestamp),
            Field.MEAL_TYPE_RAW to SyncValue.Text(entity.mealTypeRaw),
            Field.INPUT_METHOD_RAW to SyncValue.Text(entity.inputMethodRaw),
            Field.DESCRIPTION to SyncValue.Text(entity.description),
            Field.PHOTO_FILENAME to (entity.photoFilename?.let { SyncValue.Text(it) } ?: SyncValue.Null),
            Field.BARCODE to (entity.barcode?.let { SyncValue.Text(it) } ?: SyncValue.Null),
            Field.PRODUCT_NAME to (entity.productName?.let { SyncValue.Text(it) } ?: SyncValue.Null),
            Field.ITEMS_JSON to SyncValue.Text(entity.itemsJson),
            Field.CALORIES to (entity.caloriesKcal?.let { SyncValue.Double(it) } ?: SyncValue.Null),
            Field.PROTEIN to (entity.proteinG?.let { SyncValue.Double(it) } ?: SyncValue.Null),
            Field.CARBS to (entity.carbsG?.let { SyncValue.Double(it) } ?: SyncValue.Null),
            Field.FAT to (entity.fatG?.let { SyncValue.Double(it) } ?: SyncValue.Null),
            Field.SUGAR to (entity.sugarG?.let { SyncValue.Double(it) } ?: SyncValue.Null),
            Field.FIBER to (entity.fiberG?.let { SyncValue.Double(it) } ?: SyncValue.Null),
            Field.SODIUM_MG to (entity.sodiumMg?.let { SyncValue.Double(it) } ?: SyncValue.Null),
            Field.SODIUM_MG_LOW to (entity.sodiumMgLow?.let { SyncValue.Double(it) } ?: SyncValue.Null),
            Field.SODIUM_MG_HIGH to (entity.sodiumMgHigh?.let { SyncValue.Double(it) } ?: SyncValue.Null),
            Field.SODIUM_LEVEL_RAW to SyncValue.Text(entity.sodiumLevelRaw),
            Field.SODIUM_SOURCE_RAW to SyncValue.Text(entity.sodiumSourceRaw),
            Field.CONFIDENCE to SyncValue.Double(entity.confidence),
            Field.ANALYSIS_BACKEND_RAW to SyncValue.Text(entity.analysisBackendRaw),
            Field.NOTE to SyncValue.Text(entity.note),
            Field.CREATED_AT_MS to SyncValue.Int64(entity.createdAt),
            Field.UPDATED_AT_MS to SyncValue.Int64(entity.updatedAt),
        )
        return SyncRecord(
            type = SyncEntityType.FOOD_LOG,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.FOOD_LOG) {
            "FoodLogSyncMapper applied to wrong entity type: ${record.type}"
        }
        if (record.isTombstone) {
            foodLogDao.delete(record.pk)
            syncDao.upsertTombstone(
                TombstoneEntity(
                    entityType = SyncEntityType.FOOD_LOG.tableName,
                    pk = record.pk,
                    hlc = record.hlc.packed,
                    deletedAt = requireNotNull(record.deletedAt),
                ),
            )
            return
        }

        val p = record.payload
        val entity = FoodLogEntity(
            id = record.pk,
            timestamp = extractInt(p, Field.TIMESTAMP_MS),
            mealTypeRaw = extractString(p, Field.MEAL_TYPE_RAW),
            inputMethodRaw = extractString(p, Field.INPUT_METHOD_RAW),
            description = extractString(p, Field.DESCRIPTION),
            photoFilename = optionalString(p, Field.PHOTO_FILENAME),
            barcode = optionalString(p, Field.BARCODE),
            productName = optionalString(p, Field.PRODUCT_NAME),
            itemsJson = extractString(p, Field.ITEMS_JSON).ifEmpty { "[]" },
            caloriesKcal = optionalDouble(p, Field.CALORIES),
            proteinG = optionalDouble(p, Field.PROTEIN),
            carbsG = optionalDouble(p, Field.CARBS),
            fatG = optionalDouble(p, Field.FAT),
            sugarG = optionalDouble(p, Field.SUGAR),
            fiberG = optionalDouble(p, Field.FIBER),
            sodiumMg = optionalDouble(p, Field.SODIUM_MG),
            sodiumMgLow = optionalDouble(p, Field.SODIUM_MG_LOW),
            sodiumMgHigh = optionalDouble(p, Field.SODIUM_MG_HIGH),
            sodiumLevelRaw = extractString(p, Field.SODIUM_LEVEL_RAW).ifEmpty { "mid" },
            sodiumSourceRaw = extractString(p, Field.SODIUM_SOURCE_RAW).ifEmpty { "estimate" },
            confidence = extractDouble(p, Field.CONFIDENCE),
            analysisBackendRaw = extractString(p, Field.ANALYSIS_BACKEND_RAW),
            note = extractString(p, Field.NOTE),
            createdAt = extractInt(p, Field.CREATED_AT_MS),
            updatedAt = extractInt(p, Field.UPDATED_AT_MS),
            hlcUpdatedAt = record.hlc.packed,
            hcRecordId = null,
        )
        foodLogDao.insert(entity)
    }

    private fun extractInt(p: Map<Int, SyncValue>, key: Int): Long =
        (p[key] as? SyncValue.Int64)?.value ?: 0L

    private fun extractString(p: Map<Int, SyncValue>, key: Int): String =
        (p[key] as? SyncValue.Text)?.value.orEmpty()

    private fun optionalString(p: Map<Int, SyncValue>, key: Int): String? =
        (p[key] as? SyncValue.Text)?.value

    private fun extractDouble(p: Map<Int, SyncValue>, key: Int): Double = when (val v = p[key]) {
        is SyncValue.Double -> v.value
        is SyncValue.Int64 -> v.value.toDouble()
        else -> 0.0
    }

    private fun optionalDouble(p: Map<Int, SyncValue>, key: Int): Double? = when (val v = p[key]) {
        is SyncValue.Double -> v.value
        is SyncValue.Int64 -> v.value.toDouble()
        else -> null
    }
}
