package com.silverbp.android.sync

import com.silverbp.android.core.db.SyncDao
import com.silverbp.android.core.db.TombstoneEntity
import com.silverbp.android.core.db.WeightDao
import com.silverbp.android.core.db.WeightReadingEntity
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.mapping.SyncRecordMapper

/**
 * Concrete `SyncRecordMapper` for the v20 `weight_reading` table. id-keyed like
 * `glucose_reading`; born member-native (the table never existed pre-v20 so
 * memberId is always present from a same-version peer). The field-tag layout
 * below is FROZEN once cross-device weight sync ships — never renumber a tag,
 * only append. iOS BPCoach mirrors this 1:1 when it adopts weight.
 *
 *   1: weightKg        (double — canonical kg)
 *   2: displayUnitRaw  (string — "kg" | "lb")
 *   3: timestampMs     (int — Long ms since epoch)
 *   4: sourceRaw       (string — manual)
 *   5: note            (string)
 *   6: photoFilename   (string?)
 *   7: createdAtMs     (int)
 *   8: updatedAtMs     (int)
 *   9: memberId        (string — v20 owning member)
 *
 * Unlike [GlucoseReadingSyncMapper] there is no `confidence` (no OCR) and no
 * `measureContext` (weight has no timing context) — BMI is derived on read from
 * the member's height, never stored or synced.
 *
 * `hcRecordId` stays local (platform-specific Health Connect dedup key), mirroring
 * [GlucoseReadingSyncMapper] / [BpReadingSyncMapper].
 *
 * The LWW gate lives in [com.silverbp.android.sync.CombinedRoomSyncSink]; this
 * mapper just shapes data and writes through the DAOs.
 *
 * Backward compat (tag 9): the weight table is born member-native so a
 * same-version peer always carries memberId; an inbound record without it (a
 * malformed/legacy frame) resolves via [ownerIdProvider] so it isn't orphaned on
 * an empty memberId that no member-scoped query would surface — same precedent as
 * [GlucoseReadingSyncMapper].
 */
class WeightReadingSyncMapper(
    private val weightDao: WeightDao,
    private val syncDao: SyncDao,
    /**
     * Resolves the owner member id for inbound records that arrive without a
     * memberId. Defaults to "" so unit tests and any caller that predates the
     * wiring compile unchanged; production wires
     * [com.silverbp.android.core.member.MemberRepository.ownerId].
     */
    private val ownerIdProvider: suspend () -> String = { "" },
) : SyncRecordMapper<WeightReadingEntity> {

    private object Field {
        const val WEIGHT_KG = 1
        const val DISPLAY_UNIT_RAW = 2
        const val TIMESTAMP_MS = 3
        const val SOURCE_RAW = 4
        const val NOTE = 5
        const val PHOTO_FILENAME = 6
        const val CREATED_AT_MS = 7
        const val UPDATED_AT_MS = 8
        const val MEMBER_ID = 9
    }

    override fun encode(entity: WeightReadingEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            Field.WEIGHT_KG to SyncValue.Double(entity.weightKg),
            Field.DISPLAY_UNIT_RAW to SyncValue.Text(entity.displayUnit),
            Field.TIMESTAMP_MS to SyncValue.Int64(entity.timestamp),
            Field.SOURCE_RAW to SyncValue.Text(entity.source),
            Field.NOTE to SyncValue.Text(entity.note),
            Field.PHOTO_FILENAME to (entity.photoFilename?.let { SyncValue.Text(it) } ?: SyncValue.Null),
            Field.CREATED_AT_MS to SyncValue.Int64(entity.createdAt),
            Field.UPDATED_AT_MS to SyncValue.Int64(entity.updatedAt),
            Field.MEMBER_ID to SyncValue.Text(entity.memberId),
        )
        return SyncRecord(
            type = SyncEntityType.WEIGHT_READING,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.WEIGHT_READING) {
            "WeightReadingSyncMapper applied to wrong entity type: ${record.type}"
        }
        if (record.isTombstone) {
            weightDao.delete(record.pk)
            syncDao.upsertTombstone(
                TombstoneEntity(
                    entityType = SyncEntityType.WEIGHT_READING.tableName,
                    pk = record.pk,
                    hlc = record.hlc.packed,
                    deletedAt = requireNotNull(record.deletedAt),
                ),
            )
            return
        }

        val p = record.payload
        val entity = WeightReadingEntity(
            id = record.pk,
            // Tag 9. Absent / blank on a malformed frame → resolve to the owner
            // so the reading isn't orphaned outside every member-scoped query.
            memberId = optionalString(p, Field.MEMBER_ID)
                ?.takeIf { it.isNotBlank() }
                ?: ownerIdProvider(),
            weightKg = extractDouble(p, Field.WEIGHT_KG),
            displayUnit = extractString(p, Field.DISPLAY_UNIT_RAW).ifEmpty { "kg" },
            timestamp = extractInt(p, Field.TIMESTAMP_MS),
            source = extractString(p, Field.SOURCE_RAW).ifEmpty { "manual" },
            note = extractString(p, Field.NOTE),
            photoFilename = optionalString(p, Field.PHOTO_FILENAME),
            createdAt = extractInt(p, Field.CREATED_AT_MS),
            updatedAt = extractInt(p, Field.UPDATED_AT_MS),
            hlcUpdatedAt = record.hlc.packed,
            // hcRecordId stays local — a fresh device re-mirrors and gets its own id.
            hcRecordId = null,
        )
        weightDao.upsert(entity)
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
