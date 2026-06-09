package com.silverbp.android.exercise

import com.silverbp.android.core.db.ExerciseDao
import com.silverbp.android.core.db.ExerciseSessionEntity
import com.silverbp.android.core.db.toDomain
import com.silverbp.android.core.db.toEntity
import com.silverbp.android.sync.engine.HlcClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

/**
 * Persistence + Health Connect coordinator for exercise sessions. Mirrors
 * [com.silverbp.android.core.BpRepository] conventions: Flow reads, suspend
 * writes, mapping inside the repo (not in the DAO or ViewModel).
 *
 * [upsert] is the canonical save path: it writes the session + points in a
 * Room transaction first, then best-effort writes to Health Connect and
 * stamps the returned record id back onto the session row. After a successful
 * write, [onSessionPersisted] fires so achievement evaluation can re-run
 * without the repo holding a hard dependency on the achievements module.
 */
class ExerciseRepository(
    private val dao: ExerciseDao,
    private val healthConnect: HealthConnectExerciseBridge,
    private val onSessionPersisted: () -> Unit = {},
    /** Stamps a monotonic HLC on each local session write for cross-device LWW; null in tests. */
    private val clock: HlcClock? = null,
) {

    /** Stamp the current local-write HLC onto the session entity (no-op when no clock). */
    private fun ExerciseSessionEntity.stamped(): ExerciseSessionEntity =
        clock?.let { copy(hlcUpdatedAt = it.next().packed) } ?: this

    fun observeAll(): Flow<List<ExerciseSession>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeRange(from: Instant, to: Instant): Flow<List<ExerciseSession>> =
        dao.observeRange(from.toEpochMilli(), to.toEpochMilli())
            .map { list -> list.map { it.toDomain() } }

    fun observeWithRoute(id: UUID): Flow<Pair<ExerciseSession, List<RoutePoint>>?> =
        combine(
            dao.observeById(id.toString()),
            dao.observePoints(id.toString()),
        ) { sessionEntity, pointEntities ->
            sessionEntity?.let { s ->
                s.toDomain() to pointEntities.map { it.toDomain() }
            }
        }

    suspend fun findById(id: UUID): ExerciseSession? =
        dao.findById(id.toString())?.toDomain()

    suspend fun pointsFor(id: UUID): List<RoutePoint> =
        dao.pointsFor(id.toString()).map { it.toDomain() }

    suspend fun upsert(session: ExerciseSession, points: List<RoutePoint>): ExerciseSession {
        val now = Instant.now()
        val existed = dao.findById(session.id.toString()) != null
        val initial = if (existed) {
            session.copy(updatedAt = now)
        } else {
            session.copy(createdAt = now, updatedAt = now)
        }
        val sessionEntity = initial.toEntity().stamped()
        dao.upsertWithPoints(
            session = sessionEntity,
            points = points.map { it.toEntity() },
        )

        val hcId = healthConnect.write(initial, points)
        val saved = if (hcId != null && hcId != initial.hcRecordId) {
            val withHc = initial.copy(hcRecordId = hcId, updatedAt = Instant.now())
            // hcRecordId is local-only (not carried over sync) — keep the same HLC.
            dao.updateSession(sessionEntity.copy(hcRecordId = hcId, updatedAt = withHc.updatedAt.toEpochMilli()))
            withHc
        } else {
            initial
        }
        onSessionPersisted()
        return saved
    }

    suspend fun delete(id: UUID) {
        dao.delete(id.toString())
        onSessionPersisted()
    }

    suspend fun count(): Int = dao.count()
}

