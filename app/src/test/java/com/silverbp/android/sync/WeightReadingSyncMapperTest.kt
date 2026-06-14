package com.silverbp.android.sync

import androidx.sqlite.db.SupportSQLiteQuery
import com.silverbp.android.core.db.SyncDao
import com.silverbp.android.core.db.SyncDeviceEntity
import com.silverbp.android.core.db.SyncOutboxEntity
import com.silverbp.android.core.db.TombstoneEntity
import com.silverbp.android.core.db.WeightDao
import com.silverbp.android.core.db.WeightReadingEntity
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.LwwMerger
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.transport.SyncRecordCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors [GlucoseReadingSyncMapperTest] for the v20 `weight_reading` table:
 * field-tag encode coverage, memberId backward-compat resolution, CBOR round-trip,
 * tombstone handling, plus the B6 LWW-gate composition over the real mapper —
 * exactly how [CombinedRoomSyncSink] wires it (LwwMerger → LwwTables →
 * mapper.apply). Unlike glucose, weight carries no confidence/measureContext, so
 * the payload is 9 tags.
 */
class WeightReadingSyncMapperTest {

    private val node = 0x1234_5678_9ABC_DEF0L

    private fun fixture(id: String = "9f1c0e22-7b3a-4d51-8c2e-1a2b3c4d5e6f") = WeightReadingEntity(
        id = id,
        memberId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        weightKg = 72.4,
        displayUnit = "kg",
        timestamp = 1_730_000_000_000L,
        source = "manual",
        note = "晨起空腹",
        photoFilename = "weight/2026-05-07-01.jpg",
        createdAt = 1_730_000_000_500L,
        updatedAt = 1_730_000_001_000L,
        hlcUpdatedAt = "0".repeat(32),
        hcRecordId = "hc-local-id-should-not-sync",
    )

    @Test
    fun encode_populates_every_field_tag() {
        val mapper = WeightReadingSyncMapper(FakeWeightDao(), FakeSyncDao())
        val entity = fixture()
        val hlc = Hlc.of(physicalMs = 1_730_000_001_000L, logical = 0, nodeId = 0xCAFEBABEL)
        val rec = mapper.encode(entity, hlc)

        assertEquals(SyncEntityType.WEIGHT_READING, rec.type)
        assertEquals(entity.id, rec.pk)
        assertEquals(hlc, rec.hlc)
        assertNull(rec.deletedAt)

        val p = rec.payload
        assertEquals(SyncValue.Double(72.4), p[1])
        assertEquals(SyncValue.Text("kg"), p[2])
        assertEquals(SyncValue.Int64(1_730_000_000_000L), p[3])
        assertEquals(SyncValue.Text("manual"), p[4])
        assertEquals(SyncValue.Text("晨起空腹"), p[5])
        assertEquals(SyncValue.Text("weight/2026-05-07-01.jpg"), p[6])
        assertEquals(SyncValue.Int64(1_730_000_000_500L), p[7])
        assertEquals(SyncValue.Int64(1_730_000_001_000L), p[8])
        assertEquals(SyncValue.Text("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), p[9])
        // hcRecordId is device-local — never encoded.
        assertEquals(9, p.size)
    }

    @Test
    fun apply_carries_memberId_and_drops_local_hcRecordId() = runTest {
        val dao = FakeWeightDao()
        val mapper = WeightReadingSyncMapper(dao, FakeSyncDao())
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        mapper.apply(mapper.encode(fixture(), hlc))
        val stored = dao.findById(fixture().id)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", stored?.memberId)
        assertNull("hcRecordId must not arrive over the wire", stored?.hcRecordId)
        assertEquals(hlc.packed, stored?.hlcUpdatedAt)
    }

    @Test
    fun apply_resolves_absent_memberId_to_owner() = runTest {
        val dao = FakeWeightDao()
        val mapper = WeightReadingSyncMapper(dao, FakeSyncDao(), ownerIdProvider = { "owner-id-1" })
        val rec = SyncRecord(
            type = SyncEntityType.WEIGHT_READING,
            pk = fixture().id,
            hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL),
            deletedAt = null,
            payload = mapOf(
                1 to SyncValue.Double(65.0),
                3 to SyncValue.Int64(1_730_000_000_000L),
                // no tag 9
            ),
        )
        mapper.apply(rec)
        assertEquals("owner-id-1", dao.findById(fixture().id)?.memberId)
    }

    @Test
    fun null_photo_emits_null_sync_value() {
        val mapper = WeightReadingSyncMapper(FakeWeightDao(), FakeSyncDao())
        val rec = mapper.encode(fixture().copy(photoFilename = null), Hlc.of(1L, 0, 1L))
        assertEquals(SyncValue.Null, rec.payload[6])
    }

    @Test
    fun cbor_round_trip_preserves_record() {
        val mapper = WeightReadingSyncMapper(FakeWeightDao(), FakeSyncDao())
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        val original = mapper.encode(fixture(), hlc)
        val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun apply_inserts_new_row_when_absent() = runTest {
        val dao = FakeWeightDao()
        val mapper = WeightReadingSyncMapper(dao, FakeSyncDao())
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        mapper.apply(mapper.encode(fixture(), hlc))
        // Stored row equals the fixture with the wire hlc stamped and hcRecordId
        // cleared (local-only, never synced).
        assertEquals(
            fixture().copy(hlcUpdatedAt = hlc.packed, hcRecordId = null),
            dao.findById(fixture().id),
        )
    }

    @Test
    fun apply_tombstone_deletes_row_and_writes_tombstone() = runTest {
        val dao = FakeWeightDao().apply { upsert(fixture()) }
        val syncDao = FakeSyncDao()
        val mapper = WeightReadingSyncMapper(dao, syncDao)
        val tombstone = SyncRecord(
            type = SyncEntityType.WEIGHT_READING,
            pk = fixture().id,
            hlc = Hlc.of(1_730_000_010_000L, 0, 0xABCDL),
            deletedAt = 1_730_000_010_000L,
            payload = emptyMap(),
        )
        mapper.apply(tombstone)
        assertNull(dao.findById(fixture().id))
        val ts = syncDao.tombstoneFor(SyncEntityType.WEIGHT_READING.tableName, fixture().id)
        assertEquals(tombstone.hlc.packed, ts?.hlc)
        assertEquals(tombstone.deletedAt, ts?.deletedAt)
    }

    // --- B6 LWW gate over the real mapper (the CombinedRoomSyncSink composition) ---

    private fun gateOver(dao: WeightDao, syncDao: FakeSyncDao): LwwMerger {
        val mapper = WeightReadingSyncMapper(dao, syncDao)
        return LwwMerger(
            inner = { rec -> mapper.apply(rec) },
            localHlc = { rec ->
                val (table, pkCol) = LwwTables.pkColumnFor(rec.type) ?: return@LwwMerger null
                LwwTables.resolveLocalHlc(
                    syncDao.localRowHlc(table, pkCol, rec.pk),
                    syncDao.tombstoneFor(rec.type.tableName, rec.pk)?.hlc,
                )
            },
        )
    }

    @Test
    fun lww_weight_is_pk_gated() {
        assertEquals("weight_reading" to "id", LwwTables.pkColumnFor(SyncEntityType.WEIGHT_READING))
    }

    @Test
    fun stale_peer_copy_does_not_overwrite_newer_local_edit() = runTest {
        val localHlc = Hlc.of(5_000L, 0, node)
        val dao = FakeWeightDao().apply { upsert(fixture().copy(weightKg = 80.0, hlcUpdatedAt = localHlc.packed)) }
        val syncDao = FakeSyncDao().apply { liveHlc = localHlc.packed }
        val gate = gateOver(dao, syncDao)

        val stalePeer = SyncRecord(
            type = SyncEntityType.WEIGHT_READING,
            pk = fixture().id,
            hlc = Hlc.of(2_000L, 0, node),
            deletedAt = null,
            payload = mapOf(1 to SyncValue.Double(60.0), 3 to SyncValue.Int64(1L)),
        )
        assertFalse(gate.apply(stalePeer))
        assertEquals(80.0, dao.findById(fixture().id)?.weightKg)
    }

    @Test
    fun newer_peer_edit_overwrites_local() = runTest {
        val dao = FakeWeightDao().apply { upsert(fixture().copy(weightKg = 60.0, hlcUpdatedAt = Hlc.of(1_000L, 0, node).packed)) }
        val syncDao = FakeSyncDao().apply { liveHlc = Hlc.of(1_000L, 0, node).packed }
        val gate = gateOver(dao, syncDao)

        val newer = SyncRecord(
            type = SyncEntityType.WEIGHT_READING,
            pk = fixture().id,
            hlc = Hlc.of(9_000L, 0, node),
            deletedAt = null,
            payload = mapOf(1 to SyncValue.Double(75.5), 3 to SyncValue.Int64(1L)),
        )
        assertTrue(gate.apply(newer))
        assertEquals(75.5, dao.findById(fixture().id)?.weightKg)
    }

    @Test
    fun newer_tombstone_deletes_and_records_tombstone() = runTest {
        val dao = FakeWeightDao().apply { upsert(fixture().copy(hlcUpdatedAt = Hlc.of(2_000L, 0, node).packed)) }
        val syncDao = FakeSyncDao().apply { liveHlc = Hlc.of(2_000L, 0, node).packed }
        val gate = gateOver(dao, syncDao)

        val tomb = SyncRecord(
            type = SyncEntityType.WEIGHT_READING,
            pk = fixture().id,
            hlc = Hlc.of(6_000L, 0, node),
            deletedAt = 6_000L,
            payload = emptyMap(),
        )
        assertTrue(gate.apply(tomb))
        assertNull(dao.findById(fixture().id))
        assertNotNull(syncDao.tombstoneFor("weight_reading", fixture().id))
    }

    // --- fakes ---

    private class FakeWeightDao : WeightDao {
        private val rows = mutableMapOf<String, WeightReadingEntity>()
        override fun observeLatest(memberId: String): Flow<WeightReadingEntity?> =
            flowOf(rows.values.filter { it.memberId == memberId }.maxByOrNull { it.timestamp })
        override fun observeAll(memberId: String): Flow<List<WeightReadingEntity>> =
            flowOf(rows.values.filter { it.memberId == memberId }.sortedByDescending { it.timestamp })
        override fun observeRange(memberId: String, from: Long, to: Long): Flow<List<WeightReadingEntity>> =
            flowOf(rows.values.filter { it.memberId == memberId && it.timestamp in from..to }.sortedBy { it.timestamp })
        override suspend fun count(memberId: String): Int = rows.values.count { it.memberId == memberId }
        override suspend fun findById(id: String): WeightReadingEntity? = rows[id]
        override suspend fun getAll(): List<WeightReadingEntity> = rows.values.sortedBy { it.timestamp }
        override suspend fun upsert(r: WeightReadingEntity) { rows[r.id] = r }
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun findUnmirrored(ownerId: String): List<WeightReadingEntity> =
            rows.values.filter { it.hcRecordId == null && it.memberId == ownerId }
    }

    private class FakeSyncDao : SyncDao {
        var liveHlc: String? = null
        private val tombstones = mutableMapOf<Pair<String, String>, TombstoneEntity>()
        override suspend fun upsertTombstone(tombstone: TombstoneEntity) {
            tombstones[tombstone.entityType to tombstone.pk] = tombstone
        }
        override suspend fun rawHlc(query: SupportSQLiteQuery): String? = liveHlc
        override suspend fun tombstoneFor(entityType: String, pk: String): TombstoneEntity? =
            tombstones[entityType to pk]
        override suspend fun tombstonesSince(sinceHlc: String): List<TombstoneEntity> =
            tombstones.values.filter { it.hlc > sinceHlc }
        override suspend fun gcTombstones(pruneBeforeHlc: String): Int = 0
        override suspend fun upsertDevice(device: SyncDeviceEntity) {}
        override fun devicesFlow(): Flow<List<SyncDeviceEntity>> = flowOf(emptyList())
        override suspend fun device(deviceId: String): SyncDeviceEntity? = null
        override suspend fun touchDevice(deviceId: String, nowMs: Long, hlc: String) {}
        override suspend fun forgetDevice(deviceId: String) {}
        override suspend fun minLastHlcSeen(): String? = null
        override suspend fun enqueueOutbox(entry: SyncOutboxEntity): Long = 0
        override suspend fun peekOutbox(limit: Int): List<SyncOutboxEntity> = emptyList()
        override suspend fun ackOutboxThrough(seq: Long): Int = 0
    }
}
