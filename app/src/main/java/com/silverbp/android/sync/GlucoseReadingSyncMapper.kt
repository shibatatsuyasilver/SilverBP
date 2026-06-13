package com.silverbp.android.sync

import com.silverbp.android.core.db.GlucoseDao
import com.silverbp.android.core.db.GlucoseReadingEntity
import com.silverbp.android.core.db.SyncDao
import com.silverbp.android.core.db.TombstoneEntity
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.mapping.SyncRecordMapper

/**
 * Concrete `SyncRecordMapper` for the v19 `glucose_reading` table. id-keyed like
 * bp_reading; born member-native (the table never existed pre-v18 so memberId is
 * always present from a same-version peer). The field-tag layout below is FROZEN
 * once cross-device glucose sync ships — never renumber a tag, only append. iOS
 * BPCoach mirrors this 1:1 when it adopts glucose.
 *
 *   1: valueMgdl       (double)
 *   2: displayUnitRaw  (string — "mgdl" | "mmol")
 *   3: measureCtxRaw   (string — fasting | before_meal | after_meal | bedtime | random)
 *   4: timestampMs     (int — Long ms since epoch)
 *   5: sourceRaw       (string — manual | camera)
 *   6: confidence      (double)
 *   7: note            (string)
 *   8: photoFilename   (string?)
 *   9: createdAtMs     (int)
 *  10: updatedAtMs     (int)
 *  11: memberId        (string — v19 owning member)
 *
 * `hcRecordId` stays local (platform-specific Health Connect dedup key), mirroring
 * [BpReadingSyncMapper] / [FoodLogSyncMapper].
 *
 * The LWW gate lives in [com.silverbp.android.sync.CombinedRoomSyncSink]; this
 * mapper just shapes data and writes through the DAOs.
 *
 * Backward compat (tag 11): the glucose table is born member-native so a
 * same-version peer always carries memberId; an inbound record without it (a
 * malformed/legacy frame) resolves via [ownerIdProvider] so it isn't orphaned on
 * an empty memberId that no member-scoped query would surface — same precedent as
 * [BpReadingSyncMapper].
 */
class GlucoseReadingSyncMapper(
    private val glucoseDao: GlucoseDao,
    private val syncDao: SyncDao,
    /**
     * Resolves the owner member id for inbound records that arrive without a
     * memberId. Defaults to "" so unit tests and any caller that predates the
     * wiring compile unchanged; production wires
     * [com.silverbp.android.core.member.MemberRepository.ownerId].
     */
    private val ownerIdProvider: suspend () -> String = { "" },
) : SyncRecordMapper<GlucoseReadingEntity> {

    private object Field {
        const val VALUE_MGDL = 1
        const val DISPLAY_UNIT_RAW = 2
        const val MEASURE_CTX_RAW = 3
        const val TIMESTAMP_MS = 4
        const val SOURCE_RAW = 5
        const val CONFIDENCE = 6
        const val NOTE = 7
        const val PHOTO_FILENAME = 8
        const val CREATED_AT_MS = 9
        const val UPDATED_AT_MS = 10
        const val MEMBER_ID = 11
    }

    override fun encode(entity: GlucoseReadingEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            Field.VALUE_MGDL to SyncValue.Double(entity.valueMgdl),
            Field.DISPLAY_UNIT_RAW to SyncValue.Text(entity.displayUnit),
            Field.MEASURE_CTX_RAW to SyncValue.Text(entity.measureContext),
            Field.TIMESTAMP_MS to SyncValue.Int64(entity.timestamp),
            Field.SOURCE_RAW to SyncValue.Text(entity.source),
            Field.CONFIDENCE to SyncValue.Double(entity.confidence),
            Field.NOTE to SyncValue.Text(entity.note),
            Field.PHOTO_FILENAME to (entity.photoFilename?.let { SyncValue.Text(it) } ?: SyncValue.Null),
            Field.CREATED_AT_MS to SyncValue.Int64(entity.createdAt),
            Field.UPDATED_AT_MS to SyncValue.Int64(entity.updatedAt),
            Field.MEMBER_ID to SyncValue.Text(entity.memberId),
        )
        return SyncRecord(
            type = SyncEntityType.GLUCOSE_READING,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.GLUCOSE_READING) {
            "GlucoseReadingSyncMapper applied to wrong entity type: ${record.type}"
        }
        if (record.isTombstone) {
            glucoseDao.delete(record.pk)
            syncDao.upsertTombstone(
                TombstoneEntity(
                    entityType = SyncEntityType.GLUCOSE_READING.tableName,
                    pk = record.pk,
                    hlc = record.hlc.packed,
                    deletedAt = requireNotNull(record.deletedAt),
                ),
            )
            return
        }

        val p = record.payload
        val entity = GlucoseReadingEntity(
            id = record.pk,
            // Tag 11. Absent / blank on a malformed frame → resolve to the owner
            // so the reading isn't orphaned outside every member-scoped query.
            memberId = optionalString(p, Field.MEMBER_ID)
                ?.takeIf { it.isNotBlank() }
                ?: ownerIdProvider(),
            valueMgdl = extractDouble(p, Field.VALUE_MGDL),
            displayUnit = extractString(p, Field.DISPLAY_UNIT_RAW).ifEmpty { "mgdl" },
            measureContext = extractString(p, Field.MEASURE_CTX_RAW).ifEmpty { "random" },
            timestamp = extractInt(p, Field.TIMESTAMP_MS),
            source = extractString(p, Field.SOURCE_RAW).ifEmpty { "manual" },
            confidence = extractDouble(p, Field.CONFIDENCE),
            note = extractString(p, Field.NOTE),
            photoFilename = optionalString(p, Field.PHOTO_FILENAME),
            createdAt = extractInt(p, Field.CREATED_AT_MS),
            updatedAt = extractInt(p, Field.UPDATED_AT_MS),
            hlcUpdatedAt = record.hlc.packed,
            // hcRecordId stays local — a fresh device re-mirrors and gets its own id.
            hcRecordId = null,
        )
        glucoseDao.upsert(entity)
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
}
