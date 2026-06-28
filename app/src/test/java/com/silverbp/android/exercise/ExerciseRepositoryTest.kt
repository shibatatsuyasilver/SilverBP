package com.silverbp.android.exercise

import android.content.ContextWrapper
import com.silverbp.android.core.db.ExerciseDao
import com.silverbp.android.core.db.ExerciseSessionEntity
import com.silverbp.android.core.db.RoutePointEntity
import com.silverbp.android.core.db.toEntity
import com.silverbp.android.sync.LocalSyncWriter
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Regression coverage for the Wave-2 Health Connect remediation on
 * [ExerciseRepository]: the `healthConnectEnabled` toggle guard on the upsert
 * mirror (#11) and the new delete→HC-mirror-removal branch (#14).
 *
 * Faking the bridge directly is NOT possible here:
 * [HealthConnectExerciseBridge] is a `final` concrete class that takes a
 * non-null `Context`, the JVM unit-test classpath has no mocking framework /
 * Robolectric / all-open plugin (unlike the `GlucoseHealthConnectBridge`
 * interface, which can be faked). So we inject the REAL bridge wrapped around a
 * null-base [ContextWrapper] (the same proven trick `ModelDownloaderTest` uses):
 * its `client()` resolves to null under the stubbed android.jar, so `write` /
 * `delete` are safe no-ops that return null without throwing.
 *
 * Consequence: we can pin every OBSERVABLE effect of the refactor — the master
 * toggle is consulted, the mirror leaves `hcRecordId == null` when it can't
 * reach Health Connect, the enabled/delete branches run without crashing, and
 * the persistence / tombstone / `onSessionPersisted` contract is intact — but we
 * cannot assert "bridge.write/delete was invoked" because a final concrete
 * bridge has no observable side effect in a pure JVM test. Extracting the bridge
 * to an interface (like the glucose/weight bridges) would make those assertions
 * possible with a file-local recording fake.
 */
class ExerciseRepositoryTest {

    private fun session(id: UUID = UUID.randomUUID(), hcRecordId: String? = null) = ExerciseSession(
        id = id,
        kind = ActivityKind.Walking,
        startedAt = Instant.ofEpochMilli(1_730_000_000_000L),
        endedAt = Instant.ofEpochMilli(1_730_000_600_000L),
        activeDurationMillis = 600_000L,
        distanceMeters = 800.0,
        hcRecordId = hcRecordId,
    )

    private fun point(sessionId: UUID, ts: Long, lat: Double, lon: Double) = RoutePoint(
        sessionId = sessionId,
        timestamp = Instant.ofEpochMilli(ts),
        lat = lat,
        lon = lon,
        horizontalAccuracy = 5f,
    )

    private fun bridge() = HealthConnectExerciseBridge(NoopContext())

    // --- #11: toggle-guarded upsert mirror -------------------------------

    @Test
    fun upsert_persists_session_and_points_and_fires_callback() = runTest {
        val dao = FakeExerciseDao()
        val localSync = FakeLocalSyncWriter(Hlc.of(1_730_000_010_000L, 0, 0xABCDL).packed)
        var persisted = 0
        val repo = ExerciseRepository(
            dao = dao,
            healthConnect = bridge(),
            healthConnectEnabled = { false },
            localSync = localSync,
            onSessionPersisted = { persisted++ },
        )
        val id = UUID.randomUUID()
        val pts = listOf(
            point(id, 1_730_000_000_000L, 25.00, 121.00),
            point(id, 1_730_000_300_000L, 25.01, 121.01),
        )

        val saved = repo.upsert(session(id), pts)

        assertEquals(id, saved.id)
        assertNotNull(dao.findById(id.toString()))
        assertEquals(2, dao.pointsFor(id.toString()).size)
        // Session row carries the HLC stamped from the sync clock.
        assertEquals(localSync.hlc, dao.findById(id.toString())!!.hlcUpdatedAt)
        assertEquals(1, persisted)
    }

    @Test
    fun upsert_with_health_connect_disabled_does_not_mirror() = runTest {
        val dao = FakeExerciseDao()
        var enabledCalls = 0
        val repo = ExerciseRepository(
            dao = dao,
            healthConnect = bridge(),
            healthConnectEnabled = { enabledCalls++; false },
        )
        val id = UUID.randomUUID()

        val saved = repo.upsert(session(id), emptyList())

        // Toggle is OFF: the mirror branch is skipped, so no record id is stamped.
        assertNull(saved.hcRecordId)
        assertNull(dao.findById(id.toString())!!.hcRecordId)
        // The master toggle is consulted exactly once per upsert.
        assertEquals(1, enabledCalls)
    }

    @Test
    fun upsert_with_health_connect_enabled_runs_mirror_path_without_crashing() = runTest {
        val dao = FakeExerciseDao()
        var enabledCalls = 0
        val repo = ExerciseRepository(
            dao = dao,
            healthConnect = bridge(),
            healthConnectEnabled = { enabledCalls++; true },
        )
        val id = UUID.randomUUID()

        val saved = repo.upsert(session(id), emptyList())

        // Toggle ON: the enabled branch and bridge.write call execute. The real
        // bridge can't reach Health Connect from a JVM test (client() == null),
        // so write returns null and no record id is stamped — but it must not
        // throw, and the session is still persisted.
        assertEquals(1, enabledCalls)
        assertNull(saved.hcRecordId)
        assertNotNull(dao.findById(id.toString()))
    }

    // --- #14: delete→HC-mirror removal -----------------------------------

    @Test
    fun delete_writes_session_tombstone_and_fires_callback() = runTest {
        val dao = FakeExerciseDao()
        val localSync = FakeLocalSyncWriter(Hlc.of(1_730_000_010_000L, 0, 0xABCDL).packed)
        var persisted = 0
        val repo = ExerciseRepository(
            dao = dao,
            healthConnect = bridge(),
            healthConnectEnabled = { true },
            localSync = localSync,
            onSessionPersisted = { persisted++ },
        )
        val id = UUID.randomUUID()
        // Seed a previously-mirrored session so the delete→HC-delete branch
        // (existing.hcRecordId != null && enabled) is taken — it no-ops safely
        // on the real bridge while the local tombstone is still written.
        dao.seedSession(session(id, hcRecordId = "hc-1").toEntity())

        repo.delete(id)

        assertEquals(SyncEntityType.EXERCISE_SESSION to id.toString(), localSync.deleted.single())
        assertEquals(1, persisted)
    }

    @Test
    fun delete_without_sync_writer_removes_row_and_fires_callback() = runTest {
        val dao = FakeExerciseDao()
        var persisted = 0
        val repo = ExerciseRepository(
            dao = dao,
            healthConnect = bridge(),
            healthConnectEnabled = { true },
            onSessionPersisted = { persisted++ },
        )
        val id = UUID.randomUUID()
        dao.seedSession(session(id, hcRecordId = "hc-1").toEntity())

        repo.delete(id)

        // No sync writer: delete() falls back to dao.delete and the HC-delete
        // branch still runs without disturbing the row removal.
        assertNull(dao.findById(id.toString()))
        assertEquals(1, persisted)
    }

    // --- in-memory fakes (file-local) ------------------------------------

    /**
     * Null-base context: the real [HealthConnectExerciseBridge.client] resolves
     * to null under the stubbed android.jar, so write/delete no-op. Proven by
     * `ModelDownloaderTest`'s identical use of `ContextWrapper(null)`.
     */
    private class NoopContext : ContextWrapper(null)

    private class FakeExerciseDao : ExerciseDao {
        private val sessionRows = linkedMapOf<String, ExerciseSessionEntity>()
        private val pointRows = linkedMapOf<String, RoutePointEntity>()

        fun seedSession(e: ExerciseSessionEntity) { sessionRows[e.id] = e }

        override fun observeAll(): Flow<List<ExerciseSessionEntity>> = flowOf(sessionRows.values.toList())
        override fun observeRange(from: Long, to: Long): Flow<List<ExerciseSessionEntity>> = flowOf(emptyList())
        override fun observeById(id: String): Flow<ExerciseSessionEntity?> = flowOf(sessionRows[id])
        override suspend fun findById(id: String): ExerciseSessionEntity? = sessionRows[id]
        override suspend fun findUnmirrored(): List<ExerciseSessionEntity> =
            sessionRows.values.filter { it.hcRecordId == null }
        override fun observePoints(sessionId: String): Flow<List<RoutePointEntity>> =
            flowOf(pointRows.values.filter { it.sessionId == sessionId })
        override suspend fun pointsFor(sessionId: String): List<RoutePointEntity> =
            pointRows.values.filter { it.sessionId == sessionId }
        override suspend fun allPoints(): List<RoutePointEntity> = pointRows.values.toList()
        override suspend fun insertSession(session: ExerciseSessionEntity) { sessionRows[session.id] = session }
        override suspend fun updateSession(session: ExerciseSessionEntity) { sessionRows[session.id] = session }
        override suspend fun insertPoints(points: List<RoutePointEntity>) {
            points.forEach { pointRows[it.id] = it }
        }
        override suspend fun clearPoints(sessionId: String) {
            pointRows.values.removeAll { it.sessionId == sessionId }
        }
        override suspend fun delete(id: String) {
            sessionRows.remove(id)
            pointRows.values.removeAll { it.sessionId == id }
        }
        override suspend fun count(): Int = sessionRows.size
    }

    private class FakeLocalSyncWriter(val hlc: String) : LocalSyncWriter {
        val deleted = mutableListOf<Pair<SyncEntityType, String>>()
        override fun nextHlc(): String = hlc
        override suspend fun delete(type: SyncEntityType, pk: String) {
            deleted += type to pk
        }
        override suspend fun stamp(type: SyncEntityType, pk: String) {}
    }
}
