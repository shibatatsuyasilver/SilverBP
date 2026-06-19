package com.silverbp.android.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Read+write API for [StrengthWorkoutSessionEntity] + [SetLogEntity]. Mirrors
 * [ExerciseDao]: Flow reads, suspend writes, entity types only. The
 * [insertSessionWithSets] transaction reuses the cardio
 * [ExerciseDao.upsertWithPoints] pattern — one atomic multi-table write.
 *
 * Cascade delete on the FK clears [set_log] rows automatically when a session
 * is deleted — see [SetLogEntity]'s ForeignKey definition.
 */
@Dao
interface StrengthWorkoutDao {

    @Query("SELECT * FROM strength_workout_session ORDER BY startedAt DESC")
    fun observeAllSessions(): Flow<List<StrengthWorkoutSessionEntity>>

    @Query("SELECT * FROM strength_workout_session WHERE id = :id LIMIT 1")
    fun observeSessionById(id: String): Flow<StrengthWorkoutSessionEntity?>

    @Query("SELECT * FROM strength_workout_session WHERE id = :id LIMIT 1")
    suspend fun sessionById(id: String): StrengthWorkoutSessionEntity?

    @Query("SELECT * FROM set_log WHERE workoutSessionId = :id ORDER BY setNumber ASC")
    fun observeSetsForSession(id: String): Flow<List<SetLogEntity>>

    @Query("SELECT * FROM set_log WHERE workoutSessionId = :id ORDER BY setNumber ASC")
    suspend fun setsForSession(id: String): List<SetLogEntity>

    @Query("SELECT id FROM set_log WHERE workoutSessionId = :id")
    suspend fun setIdsForSession(id: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: StrengthWorkoutSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSets(sets: List<SetLogEntity>)

    @Query("DELETE FROM set_log WHERE workoutSessionId = :id")
    suspend fun clearSets(id: String)

    @Query("DELETE FROM strength_workout_session WHERE id = :id")
    suspend fun delete(id: String)

    @Upsert
    suspend fun upsertTombstones(tombstones: List<TombstoneEntity>)

    @Query("SELECT COUNT(*) FROM strength_workout_session")
    suspend fun count(): Int

    /**
     * Replace a session and its full set list in one transaction. Existing
     * sets are cleared first so re-saving the same session id (e.g. user
     * re-edits the note) does not duplicate set rows.
     */
    @Transaction
    suspend fun insertSessionWithSets(
        session: StrengthWorkoutSessionEntity,
        sets: List<SetLogEntity>,
    ) {
        insertSession(session)
        clearSets(session.id)
        if (sets.isNotEmpty()) insertSets(sets)
    }

    @Transaction
    suspend fun insertSessionWithSetsAndTombstonesForRemovedSets(
        session: StrengthWorkoutSessionEntity,
        sets: List<SetLogEntity>,
        setEntityType: String,
        deletedSetHlc: String,
        deletedAt: Long,
    ) {
        val removedSetIds = setIdsForSession(session.id).toSet() - sets.map { it.id }.toSet()
        insertSessionWithSets(session, sets)
        if (removedSetIds.isNotEmpty()) {
            upsertTombstones(
                removedSetIds.map { id ->
                    TombstoneEntity(
                        entityType = setEntityType,
                        pk = id,
                        hlc = deletedSetHlc,
                        deletedAt = deletedAt,
                    )
                },
            )
        }
    }

    @Transaction
    suspend fun deleteSessionWithTombstones(
        id: String,
        sessionEntityType: String,
        setEntityType: String,
        hlc: String,
        deletedAt: Long,
    ) {
        val setIds = setIdsForSession(id)
        delete(id)
        upsertTombstones(
            buildList {
                add(TombstoneEntity(entityType = sessionEntityType, pk = id, hlc = hlc, deletedAt = deletedAt))
                setIds.forEach { setId ->
                    add(TombstoneEntity(entityType = setEntityType, pk = setId, hlc = hlc, deletedAt = deletedAt))
                }
            },
        )
    }
}
