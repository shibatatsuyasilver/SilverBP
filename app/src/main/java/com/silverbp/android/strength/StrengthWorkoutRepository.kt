package com.silverbp.android.strength

import com.silverbp.android.core.db.ExerciseLibraryDao
import com.silverbp.android.core.db.StrengthWorkoutDao
import com.silverbp.android.core.db.toDomain
import com.silverbp.android.core.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistence coordinator for strength workout sessions + their set logs.
 * Mirrors [com.silverbp.android.exercise.ExerciseRepository]: Flow reads,
 * suspend writes, mapping inside the repo.
 *
 * [upsert] is the canonical save path: it writes the session + all set logs in
 * one Room transaction via [StrengthWorkoutDao.insertSessionWithSets].
 */
class StrengthWorkoutRepository(
    private val dao: StrengthWorkoutDao,
    private val libraryDao: ExerciseLibraryDao,
) {

    fun observeAllSessions(): Flow<List<StrengthWorkoutSession>> =
        dao.observeAllSessions().map { list -> list.map { it.toShallowDomain() } }

    /** Full session graph (exercises + their sets), or null if not found. */
    suspend fun sessionWithSets(id: String): StrengthWorkoutSession? {
        val session = dao.sessionById(id) ?: return null
        val sets = dao.setsForSession(id)
        val catalog = libraryDao.allOnce().associate { it.id to it.toDomain().localized() }
        return session.toDomain(sets, catalog)
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun count(): Int = dao.count()

    suspend fun upsert(session: StrengthWorkoutSession) {
        val now = System.currentTimeMillis()
        val sessionEntity = session.toEntity(createdAt = now, updatedAt = now)
        val setEntities = session.items.flatMap { (_, sets) ->
            sets.map { it.toEntity(workoutSessionId = session.id, createdAt = now) }
        }
        dao.insertSessionWithSets(sessionEntity, setEntities)
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
