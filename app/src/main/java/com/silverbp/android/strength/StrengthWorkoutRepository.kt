package com.silverbp.android.strength

import com.silverbp.android.core.db.ExerciseLibraryDao
import com.silverbp.android.core.db.StrengthWorkoutDao
import com.silverbp.android.core.db.toDomain
import com.silverbp.android.core.db.toEntity
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.sync.LocalSyncWriter
import com.silverbp.android.sync.engine.SyncEntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistence coordinator for strength workout sessions + their set logs.
 * Mirrors [com.silverbp.android.exercise.ExerciseRepository]: Flow reads,
 * suspend writes, mapping inside the repo.
 *
 * [upsert] is the canonical save path: it writes the session + all set logs in
 * one Room transaction via [StrengthWorkoutDao.insertSessionWithSets].
 *
 * (v1.0) Owner-only by design per roadmap section 3-2 — these operate on the
 * device owner's own sensor / Health Connect / coaching data and are
 * intentionally NOT member-scoped (no memberId). Do not member-scope without a
 * product decision.
 */
class StrengthWorkoutRepository(
    private val dao: StrengthWorkoutDao,
    private val libraryDao: ExerciseLibraryDao,
    private val localSync: LocalSyncWriter? = null,
) {

    private val syncWriter: LocalSyncWriter?
        get() = localSync ?: runCatching { ServiceLocator.localSyncWriter }.getOrNull()

    fun observeAllSessions(): Flow<List<StrengthWorkoutSession>> =
        dao.observeAllSessions().map { list -> list.map { it.toShallowDomain() } }

    /** Full session graph (exercises + their sets), or null if not found. */
    suspend fun sessionWithSets(id: String): StrengthWorkoutSession? {
        val session = dao.sessionById(id) ?: return null
        val sets = dao.setsForSession(id)
        val catalog = libraryDao.allOnce().associate { it.id to it.toDomain().localized() }
        return session.toDomain(sets, catalog)
    }

    suspend fun delete(id: String) {
        val writer = syncWriter
        if (writer != null) {
            dao.deleteSessionWithTombstones(
                id = id,
                sessionEntityType = SyncEntityType.STRENGTH_WORKOUT_SESSION.tableName,
                setEntityType = SyncEntityType.SET_LOG.tableName,
                hlc = writer.nextHlc(),
                deletedAt = System.currentTimeMillis(),
            )
        } else {
            dao.delete(id)
        }
    }

    suspend fun count(): Int = dao.count()

    suspend fun upsert(session: StrengthWorkoutSession) {
        val now = System.currentTimeMillis()
        val writer = syncWriter
        val sessionHlc = writer?.nextHlc()
        // Preserve the original creation time when editing an existing session;
        // only stamp createdAt = now for a brand-new row. updatedAt is always now.
        val createdAt = dao.sessionById(session.id)?.createdAt ?: now
        val sessionEntity = session.toEntity(createdAt = createdAt, updatedAt = now).copy(
            hlcUpdatedAt = sessionHlc ?: "0",
        )
        val setEntities = session.items.flatMap { (_, sets) ->
            sets.map {
                it.toEntity(workoutSessionId = session.id, createdAt = now).copy(
                    hlcUpdatedAt = writer?.nextHlc() ?: "0",
                )
            }
        }
        if (writer != null) {
            dao.insertSessionWithSetsAndTombstonesForRemovedSets(
                session = sessionEntity,
                sets = setEntities,
                setEntityType = SyncEntityType.SET_LOG.tableName,
                deletedSetHlc = requireNotNull(sessionHlc),
                deletedAt = now,
            )
        } else {
            dao.insertSessionWithSets(sessionEntity, setEntities)
        }
    }

    /** Session row only, no set/exercise children attached. */
    private fun com.silverbp.android.core.db.StrengthWorkoutSessionEntity.toShallowDomain() =
        StrengthWorkoutSession(
            id = id,
            startedAt = startedAt,
            endedAt = endedAt,
            note = note,
            difficulty = difficultyRaw?.let(DifficultyFeedback::fromRaw),
            items = emptyList(),
        )
}
