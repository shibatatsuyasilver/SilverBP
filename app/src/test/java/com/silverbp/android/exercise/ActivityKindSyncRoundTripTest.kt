package com.silverbp.android.exercise

import com.silverbp.android.core.db.ExerciseDao
import com.silverbp.android.core.db.ExerciseSessionEntity
import com.silverbp.android.core.db.RoutePointEntity
import com.silverbp.android.core.db.SyncDao
import com.silverbp.android.core.db.SyncDeviceEntity
import com.silverbp.android.core.db.SyncOutboxEntity
import com.silverbp.android.core.db.TombstoneEntity
import com.silverbp.android.sync.ExerciseSessionSyncMapper
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.transport.SyncRecordCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards that every [ActivityKind] raw round-trips both through [ActivityKind.fromRaw]
 * and through the [ExerciseSessionSyncMapper] encode → CBOR → decode → apply path,
 * so adding a kind (brisk walking / cycling) can't silently break cross-device /
 * iOS sync parity. The raw strings here MUST match iOS BPExercise.ActivityKind.
 */
class ActivityKindSyncRoundTripTest {

    @Test
    fun fromRaw_round_trips_every_kind() {
        for (kind in ActivityKind.entries) {
            assertEquals(kind, ActivityKind.fromRaw(kind.raw))
        }
    }

    @Test
    fun expected_raw_strings() {
        assertEquals("walking", ActivityKind.Walking.raw)
        assertEquals("running", ActivityKind.Running.raw)
        assertEquals("brisk_walking", ActivityKind.BriskWalking.raw)
        assertEquals("cycling", ActivityKind.Cycling.raw)
    }

    @Test
    fun mapper_encode_decode_apply_preserves_kind() = runTest {
        for (kind in ActivityKind.entries) {
            val dao = FakeExerciseDao()
            val mapper = ExerciseSessionSyncMapper(dao, FakeSyncDao())
            val entity = fixture(activityKind = kind.raw)
            val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)

            val encoded = mapper.encode(entity, hlc)
            assertEquals(SyncValue.Text(kind.raw), encoded.payload[1])

            val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(encoded))
            assertEquals(encoded, decoded)

            mapper.apply(decoded)
            assertEquals(kind.raw, dao.findById(entity.id)?.activityKind)
        }
    }

    @Test
    fun mapper_preserves_active_duration_and_machine_ocr_fields() = runTest {
        val dao = FakeExerciseDao()
        val mapper = ExerciseSessionSyncMapper(dao, FakeSyncDao())
        val entity = fixture(activityKind = ActivityKind.Treadmill.raw).copy(
            activeDurationMillis = 420_000L,
            caloriesKcal = 123.4,
            heartRateBpm = 142,
            caloriesIsEstimate = false,
            heartRateIsEstimate = false,
            distanceUnitRaw = "mi",
            floors = 12,
            rawMetricsJson = """{"speed":"6.0"}""",
        )
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)

        val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(mapper.encode(entity, hlc)))
        mapper.apply(decoded)

        val stored = dao.findById(entity.id)!!
        assertEquals(420_000L, stored.activeDurationMillis)
        assertEquals(123.4, stored.caloriesKcal!!, 0.0001)
        assertEquals(142, stored.heartRateBpm)
        assertEquals(false, stored.caloriesIsEstimate)
        assertEquals(false, stored.heartRateIsEstimate)
        assertEquals("mi", stored.distanceUnitRaw)
        assertEquals(12, stored.floors)
        assertEquals("""{"speed":"6.0"}""", stored.rawMetricsJson)
    }

    private fun fixture(activityKind: String) = ExerciseSessionEntity(
        id = "362c65d9-d66f-48bf-bafd-1c98e1d9bd81",
        activityKind = activityKind,
        startedAt = 1_730_000_000_000L,
        endedAt = 1_730_000_600_000L,
        activeDurationMillis = 600_000L,
        distanceMeters = 1234.5,
        stepCount = 1500,
        averagePaceSecPerKm = 360.0,
        source = "gps",
        note = "晚餐後散步",
        hcRecordId = null,
        createdAt = 1_730_000_000_500L,
        updatedAt = 1_730_000_001_000L,
        hlcUpdatedAt = "0".repeat(32),
    )

    // --- in-memory fakes (no Room required) ---

    private class FakeExerciseDao : ExerciseDao {
        private val rows = mutableMapOf<String, ExerciseSessionEntity>()

        override fun observeAll(): Flow<List<ExerciseSessionEntity>> =
            flowOf(rows.values.sortedByDescending { it.startedAt })
        override fun observeRange(from: Long, to: Long): Flow<List<ExerciseSessionEntity>> =
            flowOf(rows.values.filter { it.startedAt in from..to })
        override fun observeById(id: String): Flow<ExerciseSessionEntity?> = flowOf(rows[id])
        override suspend fun findById(id: String): ExerciseSessionEntity? = rows[id]
        override suspend fun findUnmirrored(): List<ExerciseSessionEntity> =
            rows.values.filter { it.hcRecordId == null }
        override fun observePoints(sessionId: String): Flow<List<RoutePointEntity>> = flowOf(emptyList())
        override suspend fun pointsFor(sessionId: String): List<RoutePointEntity> = emptyList()
        override suspend fun allPoints(): List<RoutePointEntity> = emptyList()
        override suspend fun insertSession(session: ExerciseSessionEntity) { rows[session.id] = session }
        override suspend fun updateSession(session: ExerciseSessionEntity) { rows[session.id] = session }
        override suspend fun insertPoints(points: List<RoutePointEntity>) {}
        override suspend fun clearPoints(sessionId: String) {}
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun count(): Int = rows.size
    }

    private class FakeSyncDao : SyncDao {
        override suspend fun upsertTombstone(tombstone: TombstoneEntity) {}
        override suspend fun rawHlc(query: androidx.sqlite.db.SupportSQLiteQuery): String? = null
        override suspend fun tombstoneFor(entityType: String, pk: String): TombstoneEntity? = null
        override suspend fun tombstonesSince(sinceHlc: String): List<TombstoneEntity> = emptyList()
        override suspend fun gcTombstones(pruneBeforeHlc: String): Int = 0
        override suspend fun upsertDevice(device: SyncDeviceEntity) {}
        override fun devicesFlow(): Flow<List<SyncDeviceEntity>> = flowOf(emptyList())
        override suspend fun device(deviceId: String): SyncDeviceEntity? = null
        override suspend fun touchDevice(deviceId: String, nowMs: Long, hlc: String) {}
        override suspend fun forgetDevice(deviceId: String) {}
        override suspend fun minLastHlcSeen(): String? = null
        override suspend fun enqueueOutbox(entry: SyncOutboxEntity): Long = 0L
        override suspend fun peekOutbox(limit: Int): List<SyncOutboxEntity> = emptyList()
        override suspend fun ackOutboxThrough(seq: Long): Int = 0
    }
}
