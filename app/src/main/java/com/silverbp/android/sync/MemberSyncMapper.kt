package com.silverbp.android.sync

import com.silverbp.android.core.db.MemberDao
import com.silverbp.android.core.db.MemberEntity
import com.silverbp.android.core.db.SyncDao
import com.silverbp.android.core.db.TombstoneEntity
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.mapping.SyncRecordMapper

/**
 * `SyncRecordMapper` for the v18 `member` table. id-keyed like bp_reading. The
 * field-tag layout below is FROZEN once cross-device member sync ships — never
 * renumber a tag, only append. iOS BPCoach mirrors this 1:1.
 *
 *   1: displayName  (string)   2: isOwner    (bool)   3: birthYear   (int?)
 *   4: hasDiabetes  (bool)     5: hasCKD     (bool)   6: hasASCVD    (bool)
 *   7: guideline    (string)   8: colorIndex (int)    9: sortOrder   (int)
 *  10: archived     (bool)    11: createdAtMs (int)  12: updatedAtMs (int)
 *  13: heightCm     (int?)    14: biologicalSex (string?) 15: targetWeightKg (double?)
 *
 * Tags 13–15 (profile fields backing the weight feature: heightCm, biologicalSex,
 * targetWeightKg) are APPENDED — a pre-v20 peer omits them, so [apply] decodes
 * them as null (backward-compatible). Never renumber existing tags.
 *
 * Phase 1 ships single-device only (no LAN member sync yet), so this mapper is
 * exercised by **backup export/import** today; the LAN wire support lands with
 * the pairing entrypoint. Backward compat: an inbound backup with no MEMBER
 * records is handled one layer up by [com.silverbp.android.backup.BackupManager]
 * (synthesises the owner); BP/medication records that arrive without a memberId
 * resolve to the owner in their own mappers.
 *
 * Single-owner invariant: if an inbound owner row has a different id from the
 * current local owner, import it as a non-owner. A fresh restore with no owner
 * still accepts the inbound owner row, and updates to the same owner id remain
 * owner updates.
 */
class MemberSyncMapper(
    private val memberDao: MemberDao,
    private val syncDao: SyncDao,
) : SyncRecordMapper<MemberEntity> {

    private object Field {
        const val DISPLAY_NAME = 1
        const val IS_OWNER = 2
        const val BIRTH_YEAR = 3
        const val HAS_DIABETES = 4
        const val HAS_CKD = 5
        const val HAS_ASCVD = 6
        const val GUIDELINE = 7
        const val COLOR_INDEX = 8
        const val SORT_ORDER = 9
        const val ARCHIVED = 10
        const val CREATED_AT_MS = 11
        const val UPDATED_AT_MS = 12
        const val HEIGHT_CM = 13
        const val BIOLOGICAL_SEX = 14
        const val TARGET_WEIGHT_KG = 15
    }

    override fun encode(entity: MemberEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            Field.DISPLAY_NAME to SyncValue.Text(entity.displayName),
            Field.IS_OWNER to SyncValue.Bool(entity.isOwner),
            Field.BIRTH_YEAR to (entity.birthYear?.let { SyncValue.Int64(it.toLong()) } ?: SyncValue.Null),
            Field.HAS_DIABETES to SyncValue.Bool(entity.hasDiabetes),
            Field.HAS_CKD to SyncValue.Bool(entity.hasCKD),
            Field.HAS_ASCVD to SyncValue.Bool(entity.hasASCVD),
            Field.GUIDELINE to SyncValue.Text(entity.guideline),
            Field.COLOR_INDEX to SyncValue.Int64(entity.colorIndex.toLong()),
            Field.SORT_ORDER to SyncValue.Int64(entity.sortOrder.toLong()),
            Field.ARCHIVED to SyncValue.Bool(entity.archived),
            Field.CREATED_AT_MS to SyncValue.Int64(entity.createdAt),
            Field.UPDATED_AT_MS to SyncValue.Int64(entity.updatedAt),
            // Tags 13–15: weight-feature profile fields. Nullable → Null sentinel,
            // mirroring how birthYear (tag 3) is carried.
            Field.HEIGHT_CM to (entity.heightCm?.let { SyncValue.Int64(it.toLong()) } ?: SyncValue.Null),
            Field.BIOLOGICAL_SEX to (entity.biologicalSex?.let { SyncValue.Text(it) } ?: SyncValue.Null),
            Field.TARGET_WEIGHT_KG to (entity.targetWeightKg?.let { SyncValue.Double(it) } ?: SyncValue.Null),
        )
        return SyncRecord(
            type = SyncEntityType.MEMBER,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.MEMBER) {
            "MemberSyncMapper applied to wrong entity type: ${record.type}"
        }
        if (record.isTombstone) {
            // The owner row is never tombstoned (it's the anchor for owner-only
            // data); a stray owner tombstone would orphan every owner reading,
            // so guard against it. Non-owner deletes are honoured.
            val existing = memberDao.findById(record.pk)
            if (existing == null || !existing.isOwner) {
                memberDao.deleteById(record.pk)
            }
            syncDao.upsertTombstone(
                TombstoneEntity(
                    entityType = SyncEntityType.MEMBER.tableName,
                    pk = record.pk,
                    hlc = record.hlc.packed,
                    deletedAt = requireNotNull(record.deletedAt),
                ),
            )
            return
        }

        val p = record.payload
        val entity = MemberEntity(
            id = record.pk,
            displayName = extractString(p, Field.DISPLAY_NAME),
            isOwner = extractBool(p, Field.IS_OWNER),
            birthYear = optionalInt(p, Field.BIRTH_YEAR)?.toInt(),
            hasDiabetes = extractBool(p, Field.HAS_DIABETES),
            hasCKD = extractBool(p, Field.HAS_CKD),
            hasASCVD = extractBool(p, Field.HAS_ASCVD),
            guideline = extractString(p, Field.GUIDELINE),
            colorIndex = extractInt(p, Field.COLOR_INDEX).toInt(),
            sortOrder = extractInt(p, Field.SORT_ORDER).toInt(),
            archived = extractBool(p, Field.ARCHIVED),
            createdAt = extractInt(p, Field.CREATED_AT_MS),
            updatedAt = extractInt(p, Field.UPDATED_AT_MS),
            // Tags 13–15: absent on a pre-v20 peer → null (backward-compatible).
            heightCm = optionalInt(p, Field.HEIGHT_CM)?.toInt(),
            biologicalSex = optionalString(p, Field.BIOLOGICAL_SEX),
            targetWeightKg = optionalDouble(p, Field.TARGET_WEIGHT_KG),
            hlcUpdatedAt = record.hlc.packed,
        )
        val currentOwner = if (entity.isOwner) memberDao.getOwner() else null
        val toSave = if (currentOwner != null && currentOwner.id != entity.id) {
            entity.copy(isOwner = false)
        } else {
            entity
        }
        memberDao.upsert(toSave)
    }

    private fun extractInt(p: Map<Int, SyncValue>, key: Int): Long =
        (p[key] as? SyncValue.Int64)?.value ?: 0L

    private fun optionalInt(p: Map<Int, SyncValue>, key: Int): Long? =
        (p[key] as? SyncValue.Int64)?.value

    private fun extractString(p: Map<Int, SyncValue>, key: Int): String =
        (p[key] as? SyncValue.Text)?.value.orEmpty()

    private fun optionalString(p: Map<Int, SyncValue>, key: Int): String? =
        (p[key] as? SyncValue.Text)?.value

    private fun optionalDouble(p: Map<Int, SyncValue>, key: Int): Double? = when (val v = p[key]) {
        is SyncValue.Double -> v.value
        is SyncValue.Int64 -> v.value.toDouble()
        else -> null
    }

    private fun extractBool(p: Map<Int, SyncValue>, key: Int): Boolean =
        (p[key] as? SyncValue.Bool)?.value ?: false
}
