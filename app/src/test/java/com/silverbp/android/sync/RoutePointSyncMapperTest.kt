package com.silverbp.android.sync

import com.silverbp.android.core.db.ExerciseDao
import com.silverbp.android.core.db.ExerciseSessionEntity
import com.silverbp.android.core.db.RoutePointEntity
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.OrphanRecordException
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the route_point *apply* path used by both LAN sync and backup restore.
 * Regression for "restored exercise sessions have no route map": a route_point
 * must attach to its parent session, and the orphan/missing-parent cases must
 * be observable rather than a silent loss.
 */
class RoutePointSyncMapperTest {

    private fun hlc(ms: Long) = Hlc.of(physicalMs = ms, logical = 0, nodeId = 1L)

    private fun routeRecord(
        pk: String,
        sessionId: String?,
        lat: Double = 25.0,
        lon: Double = 121.0,
    ) = SyncRecord(
        type = SyncEntityType.ROUTE_POINT,
        pk = pk,
        hlc = hlc(1_730_000_000_000L),
        deletedAt = null,
        payload = buildMap {
            if (sessionId != null) put(1, SyncValue.Text(sessionId))
            put(2, SyncValue.Int64(1_730_000_000_000L))
            put(3, SyncValue.Double(lat))
            put(4, SyncValue.Double(lon))
            put(5, SyncValue.Double(5.0))
        },
    )

    private fun session(id: String) = ExerciseSessionEntity(
        id = id,
        activityKind = "walking",
        startedAt = 1_730_000_000_000L,
        endedAt = 1_730_000_600_000L,
        activeDurationMillis = 600_000L,
        distanceMeters = 1000.0,
        stepCount = 1200,
        averagePaceSecPerKm = 360.0,
        source = "gps",
        note = "",
        hcRecordId = null,
        createdAt = 1_730_000_000_000L,
        updatedAt = 1_730_000_000_000L,
        hlcUpdatedAt = "0".repeat(32),
    )

    @Test
    fun `route_point attaches when parent session present`() = runTest {
        val dao = FakeExerciseDao()
        dao.insertSession(session("sess-1"))
        val mapper = RoutePointSyncMapper(dao)

        mapper.apply(routeRecord("rp-1", "sess-1", lat = 25.0339, lon = 121.5645))

        val stored = dao.pointsFor("sess-1")
        assertEquals(1, stored.size)
        assertEquals(25.0339, stored.single().lat, 1e-9)
        assertEquals(121.5645, stored.single().lon, 1e-9)
    }

    @Test
    fun `route_point throws orphan when parent session missing (so watermark is not advanced)`() = runTest {
        val dao = FakeExerciseDao()
        val mapper = RoutePointSyncMapper(dao)

        var threw = false
        try {
            mapper.apply(routeRecord("rp-1", "missing-session"))
        } catch (e: OrphanRecordException) {
            threw = true
        }
        assertTrue("missing parent must throw OrphanRecordException, not silently drop", threw)
        assertTrue(dao.allPoints().isEmpty())
    }

    @Test
    fun `route_point with missing sessionId is dropped (documents lossy path the visibility log guards)`() = runTest {
        val dao = FakeExerciseDao()
        val mapper = RoutePointSyncMapper(dao)

        // No throw — current behaviour returns early. The production mapper now
        // logs this in debug so a malformed record is observable instead of an
        // invisible loss.
        mapper.apply(routeRecord("rp-1", sessionId = null))

        assertTrue(dao.allPoints().isEmpty())
    }

    // In-memory ExerciseDao that actually stores sessions and points (the
    // existing ActivityKindSyncRoundTripTest fake returns no points).
    private class FakeExerciseDao : ExerciseDao {
        private val sessions = mutableMapOf<String, ExerciseSessionEntity>()
        private val points = mutableMapOf<String, RoutePointEntity>()

        override fun observeAll(): Flow<List<ExerciseSessionEntity>> = flowOf(sessions.values.toList())
        override fun observeRange(from: Long, to: Long): Flow<List<ExerciseSessionEntity>> =
            flowOf(sessions.values.filter { it.startedAt in from..to })
        override fun observeById(id: String): Flow<ExerciseSessionEntity?> = flowOf(sessions[id])
        override suspend fun findById(id: String): ExerciseSessionEntity? = sessions[id]
        override suspend fun findUnmirrored(): List<ExerciseSessionEntity> =
            sessions.values.filter { it.hcRecordId == null }
        override fun observePoints(sessionId: String): Flow<List<RoutePointEntity>> =
            flowOf(points.values.filter { it.sessionId == sessionId })
        override suspend fun pointsFor(sessionId: String): List<RoutePointEntity> =
            points.values.filter { it.sessionId == sessionId }
        override suspend fun allPoints(): List<RoutePointEntity> = points.values.toList()
        override suspend fun insertSession(session: ExerciseSessionEntity) { sessions[session.id] = session }
        override suspend fun updateSession(session: ExerciseSessionEntity) { sessions[session.id] = session }
        override suspend fun insertPoints(points: List<RoutePointEntity>) {
            points.forEach { this.points[it.id] = it }
        }
        override suspend fun clearPoints(sessionId: String) {
            points.entries.removeIf { it.value.sessionId == sessionId }
        }
        override suspend fun delete(id: String) {
            sessions.remove(id)
            points.entries.removeIf { it.value.sessionId == id }
        }
        override suspend fun count(): Int = sessions.size
    }
}
