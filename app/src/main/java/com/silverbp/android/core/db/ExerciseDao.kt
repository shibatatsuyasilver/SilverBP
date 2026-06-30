package com.silverbp.android.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Read+write API for [ExerciseSessionEntity] + [RoutePointEntity]. Mirrors
 * [BpDao] conventions: Flow for reads, suspend for writes; entity types only
 * (mapping to domain happens in [com.silverbp.android.exercise.ExerciseRepository]).
 *
 * Cascade delete on FK clears [route_point] rows automatically when a session
 * is deleted — see [RoutePointEntity]'s ForeignKey definition.
 */
@Dao
interface ExerciseDao {

    @Query("SELECT * FROM exercise_session ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<ExerciseSessionEntity>>

    @Query(
        "SELECT * FROM exercise_session " +
            "WHERE startedAt BETWEEN :from AND :to " +
            "ORDER BY startedAt DESC"
    )
    fun observeRange(from: Long, to: Long): Flow<List<ExerciseSessionEntity>>

    @Query("SELECT * FROM exercise_session WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<ExerciseSessionEntity?>

    @Query("SELECT * FROM exercise_session WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ExerciseSessionEntity?

    @Query("SELECT * FROM exercise_session WHERE hcRecordId IS NULL ORDER BY startedAt ASC")
    suspend fun findUnmirrored(): List<ExerciseSessionEntity>

    @Query("SELECT * FROM route_point WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observePoints(sessionId: String): Flow<List<RoutePointEntity>>

    @Query("SELECT * FROM route_point WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun pointsFor(sessionId: String): List<RoutePointEntity>

    /**
     * Bulk read of every route_point row across all sessions, used by the
     * cross-device sync source so a single round can flush both sessions and
     * their points without N+1 querying per session.
     */
    @Query("SELECT * FROM route_point ORDER BY sessionId, timestamp ASC")
    suspend fun allPoints(): List<RoutePointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ExerciseSessionEntity)

    @Update
    suspend fun updateSession(session: ExerciseSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<RoutePointEntity>)

    @Query("DELETE FROM route_point WHERE sessionId = :sessionId")
    suspend fun clearPoints(sessionId: String)

    @Query("DELETE FROM exercise_session WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM exercise_session")
    suspend fun count(): Int

    /**
     * Replace a session and its full point list in one transaction. Existing
     * points are deleted first so re-saving the same session id (e.g. user
     * re-edits the note) does not duplicate route rows.
     *
     * Clearing is gated on a non-empty [points] list: an indoor/machine session
     * legitimately saves with no points, and any caller that re-saves a session
     * with an empty list (a future regression, a partial edit path) must NOT
     * wipe an already-recorded GPS route. To intentionally clear a route, call
     * [clearPoints] explicitly.
     */
    @Transaction
    suspend fun upsertWithPoints(
        session: ExerciseSessionEntity,
        points: List<RoutePointEntity>,
    ) {
        val existed = findById(session.id) != null
        if (existed) updateSession(session) else insertSession(session)
        if (points.isNotEmpty()) {
            clearPoints(session.id)
            insertPoints(points)
        }
    }
}
