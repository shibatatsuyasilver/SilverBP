package com.silverbp.android.sync

import com.silverbp.android.core.db.ExerciseCatalogItemEntity
import com.silverbp.android.core.db.ExerciseLibraryDao
import com.silverbp.android.core.db.SetLogEntity
import com.silverbp.android.core.db.StrengthWorkoutDao
import com.silverbp.android.core.db.StrengthWorkoutSessionEntity
import com.silverbp.android.core.db.SyncDao
import com.silverbp.android.core.db.TombstoneEntity
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.mapping.SyncRecordMapper

/**
 * Strength-training mappers (v13). Field-tag layout MUST stay byte-identical
 * with the iOS strength mappers; mirror the cardio mapper conventions
 * (LWW via `hlcUpdatedAt`, integer-keyed CBOR payload, optionals → SyncValue.Null).
 *
 * ### exercise_catalog_item payload (CBOR int→value)
 *   1: name                  (string)
 *   2: bodyPart              (string)
 *   3: muscleGroupsJson      (string)
 *   4: description           (string)
 *   5: isFavorite            (bool)
 *   6: createdAtMs           (int)
 *   7: updatedAtMs           (int)
 *
 * ### strength_workout_session payload
 *   1: startedAtMs           (int)
 *   2: endedAtMs             (int)
 *   3: note                  (string)
 *   4: difficultyRaw         (string?, null = unreported)
 *   5: createdAtMs           (int)
 *   6: updatedAtMs           (int)
 *
 * ### set_log payload
 *   1: workoutSessionId      (string UUID)
 *   2: exerciseId            (string UUID)
 *   3: setNumber             (int)
 *   4: reps                  (int)
 *   5: weightKg              (double?)
 *   6: isCompleted           (bool)
 *   7: skipped               (bool)
 *   8: notes                 (string)
 *   9: createdAtMs           (int)
 */

class ExerciseCatalogItemSyncMapper(
    private val dao: ExerciseLibraryDao,
    private val syncDao: SyncDao,
) : SyncRecordMapper<ExerciseCatalogItemEntity> {

    override fun encode(entity: ExerciseCatalogItemEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            1 to SyncValue.Text(entity.name),
            2 to SyncValue.Text(entity.bodyPart),
            3 to SyncValue.Text(entity.muscleGroupsJson),
            4 to SyncValue.Text(entity.description),
            5 to SyncValue.Bool(entity.isFavorite),
            6 to SyncValue.Int64(entity.createdAt),
            7 to SyncValue.Int64(entity.updatedAt),
        )
        return SyncRecord(
            type = SyncEntityType.EXERCISE_CATALOG_ITEM,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.EXERCISE_CATALOG_ITEM)
        if (record.isTombstone) {
            // exercise_catalog_item has no native delete-by-id (seeded library
            // is append/replace-only); skip tombstones but still record them.
            syncDao.upsertTombstone(
                TombstoneEntity(
                    entityType = SyncEntityType.EXERCISE_CATALOG_ITEM.tableName,
                    pk = record.pk,
                    hlc = record.hlc.packed,
                    deletedAt = requireNotNull(record.deletedAt),
                ),
            )
            return
        }
        val p = record.payload
        val entity = ExerciseCatalogItemEntity(
            id = record.pk,
            name = (p[1] as? SyncValue.Text)?.value ?: "",
            bodyPart = (p[2] as? SyncValue.Text)?.value ?: "",
            muscleGroupsJson = (p[3] as? SyncValue.Text)?.value ?: "[]",
            description = (p[4] as? SyncValue.Text)?.value ?: "",
            isFavorite = (p[5] as? SyncValue.Bool)?.value ?: false,
            createdAt = (p[6] as? SyncValue.Int64)?.value ?: 0L,
            updatedAt = (p[7] as? SyncValue.Int64)?.value ?: 0L,
            hlcUpdatedAt = record.hlc.packed,
        )
        dao.upsert(entity)
    }
}

class StrengthWorkoutSessionSyncMapper(
    private val dao: StrengthWorkoutDao,
    private val syncDao: SyncDao,
) : SyncRecordMapper<StrengthWorkoutSessionEntity> {

    override fun encode(entity: StrengthWorkoutSessionEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            1 to SyncValue.Int64(entity.startedAt),
            2 to SyncValue.Int64(entity.endedAt),
            3 to SyncValue.Text(entity.note),
            4 to (entity.difficultyRaw?.let { SyncValue.Text(it) } ?: SyncValue.Null),
            5 to SyncValue.Int64(entity.createdAt),
            6 to SyncValue.Int64(entity.updatedAt),
        )
        return SyncRecord(
            type = SyncEntityType.STRENGTH_WORKOUT_SESSION,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.STRENGTH_WORKOUT_SESSION)
        if (record.isTombstone) {
            dao.delete(record.pk)
            syncDao.upsertTombstone(
                TombstoneEntity(
                    entityType = SyncEntityType.STRENGTH_WORKOUT_SESSION.tableName,
                    pk = record.pk,
                    hlc = record.hlc.packed,
                    deletedAt = requireNotNull(record.deletedAt),
                ),
            )
            return
        }
        val p = record.payload
        val entity = StrengthWorkoutSessionEntity(
            id = record.pk,
            startedAt = (p[1] as? SyncValue.Int64)?.value ?: 0L,
            endedAt = (p[2] as? SyncValue.Int64)?.value ?: 0L,
            note = (p[3] as? SyncValue.Text)?.value ?: "",
            difficultyRaw = (p[4] as? SyncValue.Text)?.value,
            createdAt = (p[5] as? SyncValue.Int64)?.value ?: 0L,
            updatedAt = (p[6] as? SyncValue.Int64)?.value ?: 0L,
            hlcUpdatedAt = record.hlc.packed,
        )
        dao.insertSession(entity)
    }
}

class SetLogSyncMapper(
    private val dao: StrengthWorkoutDao,
) : SyncRecordMapper<SetLogEntity> {

    override fun encode(entity: SetLogEntity, hlc: Hlc): SyncRecord {
        val payload = mutableMapOf<Int, SyncValue>(
            1 to SyncValue.Text(entity.workoutSessionId),
            2 to SyncValue.Text(entity.exerciseId),
            3 to SyncValue.Int64(entity.setNumber.toLong()),
            4 to SyncValue.Int64(entity.reps.toLong()),
            5 to (entity.weightKg?.let { SyncValue.Double(it) } ?: SyncValue.Null),
            6 to SyncValue.Bool(entity.isCompleted),
            7 to SyncValue.Bool(entity.skipped),
            8 to SyncValue.Text(entity.notes),
            9 to SyncValue.Int64(entity.createdAt),
        )
        return SyncRecord(
            type = SyncEntityType.SET_LOG,
            pk = entity.id,
            hlc = hlc,
            deletedAt = null,
            payload = payload,
        )
    }

    override suspend fun apply(record: SyncRecord) {
        require(record.type == SyncEntityType.SET_LOG)
        // set_log rows are CASCADE-deleted with their session; tombstones for a
        // lone set are unsupported (mirrors RoutePointSyncMapper).
        if (record.isTombstone) return
        val p = record.payload
        val workoutSessionId = (p[1] as? SyncValue.Text)?.value ?: return
        // FK to strength_workout_session — drop orphan sets (parent will arrive
        // in a subsequent round and the peer can re-emit then).
        if (dao.sessionById(workoutSessionId) == null) return
        val entity = SetLogEntity(
            id = record.pk,
            workoutSessionId = workoutSessionId,
            exerciseId = (p[2] as? SyncValue.Text)?.value ?: "",
            setNumber = ((p[3] as? SyncValue.Int64)?.value ?: 0L).toInt(),
            reps = ((p[4] as? SyncValue.Int64)?.value ?: 0L).toInt(),
            weightKg = (p[5] as? SyncValue.Double)?.value,
            isCompleted = (p[6] as? SyncValue.Bool)?.value ?: false,
            skipped = (p[7] as? SyncValue.Bool)?.value ?: false,
            notes = (p[8] as? SyncValue.Text)?.value ?: "",
            createdAt = (p[9] as? SyncValue.Int64)?.value ?: 0L,
            hlcUpdatedAt = record.hlc.packed,
        )
        dao.insertSets(listOf(entity))
    }
}
