package com.silverbp.android.sync

import com.silverbp.android.core.db.BpWorkoutAssociationDao
import com.silverbp.android.core.db.BpWorkoutAssociationEntity
import com.silverbp.android.core.db.SyncDao
import com.silverbp.android.core.db.SyncDeviceEntity
import com.silverbp.android.core.db.SyncOutboxEntity
import com.silverbp.android.core.db.TombstoneEntity
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.transport.SyncRecordCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BpWorkoutAssociationSyncMapperTest {

    private fun fixture(id: String = "assoc-001") = BpWorkoutAssociationEntity(
        id = id,
        bpReadingId = "bp-001",
        sessionId = "session-001",
        sessionType = "cardio",
        contextType = "pre",
        createdAt = 1_730_000_000_500L,
        hlcUpdatedAt = "0".repeat(32),
    )

    @Test
    fun assoc_cbor_round_trip_preserves_record() {
        val mapper = BpWorkoutAssociationSyncMapper(FakeAssocDao(), FakeSyncDao())
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        val original = mapper.encode(fixture(), hlc)
        val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun assoc_round_trip_apply_preserves_context_and_type() = runTest {
        val dao = FakeAssocDao()
        val mapper = BpWorkoutAssociationSyncMapper(dao, FakeSyncDao())
        val entity = fixture().copy(sessionType = "strength", contextType = "post")
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(mapper.encode(entity, hlc)))
        mapper.apply(decoded)

        val stored = dao.forSession(entity.sessionId).single()
        assertEquals(entity.copy(hlcUpdatedAt = hlc.packed), stored)
        assertEquals("strength", stored.sessionType)
        assertEquals("post", stored.contextType)
        assertEquals(SyncEntityType.BP_WORKOUT_ASSOCIATION, decoded.type)
    }

    @Test
    fun assoc_tombstone_writes_tombstone_without_row() = runTest {
        val dao = FakeAssocDao()
        val syncDao = FakeSyncDao()
        val mapper = BpWorkoutAssociationSyncMapper(dao, syncDao)
        val tombstone = mapper.encode(fixture(), Hlc.of(1L, 0, 1L)).copy(
            deletedAt = 1_730_000_010_000L,
            payload = emptyMap(),
        )
        mapper.apply(tombstone)
        assertEquals(0, dao.listAll().size)
        val ts = syncDao.tombstoneFor(SyncEntityType.BP_WORKOUT_ASSOCIATION.tableName, fixture().id)
        assertEquals(tombstone.deletedAt, ts?.deletedAt)
    }

    // --- in-memory fakes (no Room / Robolectric required) ---

    private class FakeAssocDao : BpWorkoutAssociationDao {
        private val rows = mutableMapOf<String, BpWorkoutAssociationEntity>()
        override suspend fun upsert(association: BpWorkoutAssociationEntity) {
            rows[association.id] = association
        }
        override fun observeForSession(sessionId: String): Flow<List<BpWorkoutAssociationEntity>> =
            flowOf(rows.values.filter { it.sessionId == sessionId }.sortedBy { it.createdAt })
        override suspend fun forSession(sessionId: String): List<BpWorkoutAssociationEntity> =
            rows.values.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }
        override suspend fun findById(id: String): BpWorkoutAssociationEntity? = rows[id]
        override suspend fun listAll(): List<BpWorkoutAssociationEntity> =
            rows.values.sortedBy { it.createdAt }
    }

    private class FakeSyncDao : SyncDao {
        private val tombstones = mutableMapOf<Pair<String, String>, TombstoneEntity>()
        private val devices = mutableMapOf<String, SyncDeviceEntity>()
        private val outbox = mutableListOf<SyncOutboxEntity>()
        private var nextSeq = 1L
        override suspend fun upsertTombstone(tombstone: TombstoneEntity) {
            tombstones[tombstone.entityType to tombstone.pk] = tombstone
        }
        override suspend fun rawHlc(query: androidx.sqlite.db.SupportSQLiteQuery): String? = null
        override suspend fun tombstoneFor(entityType: String, pk: String): TombstoneEntity? =
            tombstones[entityType to pk]
        override suspend fun tombstonesSince(sinceHlc: String): List<TombstoneEntity> =
            tombstones.values.filter { it.hlc > sinceHlc }.sortedBy { it.hlc }
        override suspend fun gcTombstones(pruneBeforeHlc: String): Int {
            val n = tombstones.values.count { it.hlc < pruneBeforeHlc }
            tombstones.entries.removeIf { it.value.hlc < pruneBeforeHlc }
            return n
        }
        override suspend fun upsertDevice(device: SyncDeviceEntity) { devices[device.deviceId] = device }
        override fun devicesFlow(): Flow<List<SyncDeviceEntity>> =
            flowOf(devices.values.sortedByDescending { it.lastSeenAt })
        override suspend fun device(deviceId: String): SyncDeviceEntity? = devices[deviceId]
        override suspend fun touchDevice(deviceId: String, nowMs: Long, hlc: String) {
            devices[deviceId]?.let { devices[deviceId] = it.copy(lastSeenAt = nowMs, lastHlcSeen = hlc) }
        }
        override suspend fun forgetDevice(deviceId: String) { devices.remove(deviceId) }
        override suspend fun minLastHlcSeen(): String? = devices.values.minOfOrNull { it.lastHlcSeen }
        override suspend fun enqueueOutbox(entry: SyncOutboxEntity): Long {
            val seq = nextSeq++
            outbox += entry.copy(seq = seq)
            return seq
        }
        override suspend fun peekOutbox(limit: Int): List<SyncOutboxEntity> =
            outbox.sortedBy { it.seq }.take(limit)
        override suspend fun ackOutboxThrough(seq: Long): Int {
            val n = outbox.count { it.seq <= seq }
            outbox.removeAll { it.seq <= seq }
            return n
        }
    }
}
