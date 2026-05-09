package com.silverbp.android.sync

import com.silverbp.android.core.db.BpDao
import com.silverbp.android.core.db.BpReadingEntity
import com.silverbp.android.core.db.SyncDao
import com.silverbp.android.core.db.TombstoneEntity
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.mapping.SyncRecordMapper

/**
 * Concrete `SyncRecordMapper` for `BpReadingEntity`. Field-tag layout must
 * stay byte-identical with iOS `BPReadingMapper.swift`:
 *
 *   1: systolic        (int)
 *   2: diastolic       (int)
 *   3: pulse           (int?)
 *   4: timestampMs     (int — Long ms since epoch)
 *   5: armRaw          (string)
 *   6: postureRaw      (string)
 *   7: partOfDayRaw    (string)
 *   8: beforeMedication (bool)
 *   9: photoFilename   (string?)
 *  10: confidence      (double)
 *  11: sourceRaw       (string)
 *  12: note            (string)
 *  13: irregularHeartbeat (bool)
 *  14: medicationId    (string? — UUID lowercase hex with dashes)
 *  15: createdAtMs     (int)
 *  16: updatedAtMs     (int)
 *
 * `reading_tag` membership is emitted as separate records (Phase 2);
 * `hcRecordId` stays local (platform-specific Health Connect dedup key).
 *
 * Caller is responsible for the LWW gate (`record.hlc > local.hlcUpdatedAt`);
 * this mapper just shapes data and writes through the DAOs.
 */
class BpReadingSyncMapper(
    private val bpDao: BpDao,
    private val syncDao: SyncDao,
) : SyncRecordMapper<BpReadingEntity> {

    private object Field {
        const val SYSTOLIC = 1
        const val DIASTOLIC = 2
        const val PULSE = 3
        const val TIMESTAMP_MS = 4
        const val ARM_RAW = 5
        const val POSTURE_RAW = 6
        const val PART_OF_DAY_RAW = 7
        const val BEFORE_MEDICATION = 8
        const val PHOTO_FILENAME = 9
        const val CONFIDENCE = 10
        const val SOURCE_RAW = 11
        const val NOTE = 12
        const val IRREGULAR_HEARTBEAT = 13
        const val MEDICATION_ID = 14
        const val CREATED_AT_MS = 15
        const val UPDATED_AT_MS = 16
    }

    override fun encode(entity: BpReadingEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            Field.SYSTOLIC to SyncValue.Int64(entity.systolic.toLong()),
            Field.DIASTOLIC to SyncValue.Int64(entity.diastolic.toLong()),
            Field.PULSE to (entity.pulse?.let { SyncValue.Int64(it.toLong()) } ?: SyncValue.Null),
            Field.TIMESTAMP_MS to SyncValue.Int64(entity.timestamp),
            Field.ARM_RAW to SyncValue.Text(entity.arm),
            Field.POSTURE_RAW to SyncValue.Text(entity.posture),
            Field.PART_OF_DAY_RAW to SyncValue.Text(entity.partOfDay),
            Field.BEFORE_MEDICATION to SyncValue.Bool(entity.beforeMedication),
            Field.PHOTO_FILENAME to (entity.photoFilename?.let { SyncValue.Text(it) } ?: SyncValue.Null),
            Field.CONFIDENCE to SyncValue.Double(entity.confidence),
            Field.SOURCE_RAW to SyncValue.Text(entity.source),
            Field.NOTE to SyncValue.Text(entity.note),
            Field.IRREGULAR_HEARTBEAT to SyncValue.Bool(entity.irregularHeartbeat),
            Field.MEDICATION_ID to (entity.medicationId?.let { SyncValue.Text(it) } ?: SyncValue.Null),
            Field.CREATED_AT_MS to SyncValue.Int64(entity.createdAt),
            Field.UPDATED_AT_MS to SyncValue.Int64(entity.updatedAt),
        )
        return SyncRecord(
            type = SyncEntityType.BP_READING,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.BP_READING) {
            "BpReadingSyncMapper applied to wrong entity type: ${record.type}"
        }
        if (record.isTombstone) {
            bpDao.delete(record.pk)
            syncDao.upsertTombstone(
                TombstoneEntity(
                    entityType = SyncEntityType.BP_READING.tableName,
                    pk = record.pk,
                    hlc = record.hlc.packed,
                    deletedAt = requireNotNull(record.deletedAt),
                ),
            )
            return
        }

        val p = record.payload
        val entity = BpReadingEntity(
            id = record.pk,
            systolic = extractInt(p, Field.SYSTOLIC).toInt(),
            diastolic = extractInt(p, Field.DIASTOLIC).toInt(),
            pulse = optionalInt(p, Field.PULSE)?.toInt(),
            timestamp = extractInt(p, Field.TIMESTAMP_MS),
            arm = extractString(p, Field.ARM_RAW),
            posture = extractString(p, Field.POSTURE_RAW),
            partOfDay = extractString(p, Field.PART_OF_DAY_RAW),
            beforeMedication = extractBool(p, Field.BEFORE_MEDICATION),
            photoFilename = optionalString(p, Field.PHOTO_FILENAME),
            confidence = extractDouble(p, Field.CONFIDENCE),
            source = extractString(p, Field.SOURCE_RAW),
            note = extractString(p, Field.NOTE),
            irregularHeartbeat = extractBool(p, Field.IRREGULAR_HEARTBEAT),
            medicationId = optionalString(p, Field.MEDICATION_ID),
            createdAt = extractInt(p, Field.CREATED_AT_MS),
            updatedAt = extractInt(p, Field.UPDATED_AT_MS),
            hlcUpdatedAt = record.hlc.packed,
        )
        bpDao.insert(entity)
    }

    private fun extractInt(p: Map<Int, SyncValue>, key: Int): Long =
        (p[key] as? SyncValue.Int64)?.value ?: 0L

    private fun optionalInt(p: Map<Int, SyncValue>, key: Int): Long? =
        (p[key] as? SyncValue.Int64)?.value

    private fun extractString(p: Map<Int, SyncValue>, key: Int): String =
        (p[key] as? SyncValue.Text)?.value.orEmpty()

    private fun optionalString(p: Map<Int, SyncValue>, key: Int): String? =
        (p[key] as? SyncValue.Text)?.value

    private fun extractBool(p: Map<Int, SyncValue>, key: Int): Boolean =
        (p[key] as? SyncValue.Bool)?.value ?: false

    private fun extractDouble(p: Map<Int, SyncValue>, key: Int): Double = when (val v = p[key]) {
        is SyncValue.Double -> v.value
        is SyncValue.Int64 -> v.value.toDouble()
        else -> 0.0
    }
}
