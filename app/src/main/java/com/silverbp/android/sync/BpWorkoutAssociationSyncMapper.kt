package com.silverbp.android.sync

import com.silverbp.android.core.db.BpWorkoutAssociationDao
import com.silverbp.android.core.db.BpWorkoutAssociationEntity
import com.silverbp.android.core.db.SyncDao
import com.silverbp.android.core.db.TombstoneEntity
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.mapping.SyncRecordMapper

/**
 * BP↔workout association mapper (v14). Field-tag layout MUST stay byte-identical
 * with the iOS association mapper; mirror the strength mapper conventions
 * (LWW via `hlcUpdatedAt`, integer-keyed CBOR payload).
 *
 * ### bp_workout_association payload (CBOR int→value)
 *   1: bpReadingId           (string UUID)
 *   2: sessionId             (string UUID)
 *   3: sessionType           (string — "cardio" | "strength")
 *   4: contextType           (string — "pre" | "post")
 *   5: createdAtMs           (int)
 */
class BpWorkoutAssociationSyncMapper(
    private val dao: BpWorkoutAssociationDao,
    private val syncDao: SyncDao,
) : SyncRecordMapper<BpWorkoutAssociationEntity> {

    override fun encode(entity: BpWorkoutAssociationEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            1 to SyncValue.Text(entity.bpReadingId),
            2 to SyncValue.Text(entity.sessionId),
            3 to SyncValue.Text(entity.sessionType),
            4 to SyncValue.Text(entity.contextType),
            5 to SyncValue.Int64(entity.createdAt),
        )
        return SyncRecord(
            type = SyncEntityType.BP_WORKOUT_ASSOCIATION,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun localHlc(pk: String): String? = dao.findById(pk)?.hlcUpdatedAt

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.BP_WORKOUT_ASSOCIATION)
        if (record.isTombstone) {
            // No native delete-by-id (associations are append-only); skip the
            // row but still record the tombstone for forward-compat.
            syncDao.upsertTombstone(
                TombstoneEntity(
                    entityType = SyncEntityType.BP_WORKOUT_ASSOCIATION.tableName,
                    pk = record.pk,
                    hlc = record.hlc.packed,
                    deletedAt = requireNotNull(record.deletedAt),
                ),
            )
            return
        }
        val p = record.payload
        val entity = BpWorkoutAssociationEntity(
            id = record.pk,
            bpReadingId = (p[1] as? SyncValue.Text)?.value ?: "",
            sessionId = (p[2] as? SyncValue.Text)?.value ?: "",
            sessionType = (p[3] as? SyncValue.Text)?.value ?: "cardio",
            contextType = (p[4] as? SyncValue.Text)?.value ?: "post",
            createdAt = (p[5] as? SyncValue.Int64)?.value ?: 0L,
            hlcUpdatedAt = record.hlc.packed,
        )
        dao.upsert(entity)
    }
}
