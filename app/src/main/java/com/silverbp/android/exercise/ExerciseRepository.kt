package com.silverbp.android.exercise

import com.silverbp.android.core.db.ExerciseDao
import com.silverbp.android.core.db.toDomain
import com.silverbp.android.core.db.toEntity
import com.silverbp.android.sync.LocalSyncWriter
import com.silverbp.android.sync.engine.SyncEntityType
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
 *
 * (v1.0) Owner-only by design per roadmap section 3-2 — these operate on the
 * device owner's own sensor / Health Connect / coaching data and are
 * intentionally NOT member-scoped (no memberId). Do not member-scope without a
 * product decision.
 */
class ExerciseRepository(
    private val dao: ExerciseDao,
    private val healthConnect: HealthConnectExerciseBridge,
    /** Coarse on/off for the Health Connect mirror; defaults off for tests. */
    private val healthConnectEnabled: suspend () -> Boolean = { false },
    private val localSync: LocalSyncWriter? = null,
    private val onSessionPersisted: () -> Unit = {},
) {

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
        val sessionHlc = localSync?.nextHlc()
        dao.upsertWithPoints(
            session = initial.toEntity().copy(
                hlcUpdatedAt = sessionHlc ?: if (existed) {
                    dao.findById(session.id.toString())?.hlcUpdatedAt ?: "0"
                } else {
                    "0"
                },
            ),
            points = points.map { point ->
                point.toEntity().copy(hlcUpdatedAt = localSync?.nextHlc() ?: "0")
            },
        )

        // Best-effort one-way mirror to Health Connect, gated on the master
        // toggle (the bridge independently re-checks the write permission). The
        // enabled-check is wrapped so a settings read can't throw out of here.
        val hcId = if (runCatching { healthConnectEnabled() }.getOrDefault(false)) {
            healthConnect.write(initial, points)
        } else {
            null
        }
        val saved = if (hcId != null && hcId != initial.hcRecordId) {
            val withHc = initial.copy(hcRecordId = hcId, updatedAt = Instant.now())
            dao.updateSession(
                withHc.toEntity().copy(
                    hlcUpdatedAt = localSync?.nextHlc() ?: sessionHlc ?: "0",
                ),
            )
            withHc
        } else {
            initial
        }
        onSessionPersisted()
        return saved
    }

    suspend fun delete(id: UUID) {
        val pk = id.toString()
        val existing = dao.findById(pk)
        if (localSync != null) {
            localSync.delete(SyncEntityType.EXERCISE_SESSION, pk)
        } else {
            dao.delete(pk)
        }
        // Best-effort removal of the Health Connect mirror (same gating as the
        // upsert mirror); only attempted when the session was actually mirrored.
        if (existing?.hcRecordId != null &&
            runCatching { healthConnectEnabled() }.getOrDefault(false)
        ) {
            healthConnect.delete(pk)
        }
        onSessionPersisted()
    }

    suspend fun count(): Int = dao.count()
}
