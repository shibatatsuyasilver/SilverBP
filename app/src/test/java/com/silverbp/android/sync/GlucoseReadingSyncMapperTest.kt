package com.silverbp.android.sync

import androidx.sqlite.db.SupportSQLiteQuery
import com.silverbp.android.core.db.GlucoseDao
import com.silverbp.android.core.db.GlucoseReadingEntity
import com.silverbp.android.core.db.SyncDao
import com.silverbp.android.core.db.SyncDeviceEntity
import com.silverbp.android.core.db.SyncOutboxEntity
import com.silverbp.android.core.db.TombstoneEntity
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
 * Mirrors [BpReadingSyncMapperTest] for the v19 `glucose_reading` table: field-tag
 * encode coverage, memberId backward-compat resolution, CBOR round-trip, and
 * tombstone handling. Plus a B6 LWW-gate composition over the real mapper —
 * exactly how [CombinedRoomSyncSink] wires it (LwwMerger → LwwTables →
 * mapper.apply) — pinning that a stale peer copy can't REPLACE a newer local row.
 */
class GlucoseReadingSyncMapperTest {

    private val node = 0x1234_5678_9ABC_DEF0L

    private fun fixture(id: String = "362c65d9-d66f-48bf-bafd-1c98e1d9bd81") = GlucoseReadingEntity(
        id = id,
        memberId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        valueMgdl = 132.0,
        displayUnit = "mgdl",
        measureContext = "after_meal",
        timestamp = 1_730_000_000_000L,
        source = "manual",
        confidence = 0.87,
        note = "午餐後一小時",
        photoFilename = "glucose/2026-05-07-01.jpg",
        createdAt = 1_730_000_000_500L,
        updatedAt = 1_730_000_001_000L,
        hlcUpdatedAt = "0".repeat(32),
        hcRecordId = "hc-local-id-should-not-sync",
    )

    @Test
    fun encode_populates_every_field_tag() {
        val mapper = GlucoseReadingSyncMapper(FakeGlucoseDao(), FakeSyncDao())
        val entity = fixture()
        val hlc = Hlc.of(physicalMs = 1_730_000_001_000L, logical = 0, nodeId = 0xCAFEBABEL)
        val rec = mapper.encode(entity, hlc)

        assertEquals(SyncEntityType.GLUCOSE_READING, rec.type)
        assertEquals(entity.id, rec.pk)
        assertEquals(hlc, rec.hlc)
        assertNull(rec.deletedAt)

        val p = rec.payload
        assertEquals(SyncValue.Double(132.0), p[1])
        assertEquals(SyncValue.Text("mgdl"), p[2])
        assertEquals(SyncValue.Text("after_meal"), p[3])
        assertEquals(SyncValue.Int64(1_730_000_000_000L), p[4])
        assertEquals(SyncValue.Text("manual"), p[5])
        assertEquals(SyncValue.Double(0.87), p[6])
        assertEquals(SyncValue.Text("午餐後一小時"), p[7])
        assertEquals(SyncValue.Text("glucose/2026-05-07-01.jpg"), p[8])
        assertEquals(SyncValue.Int64(1_730_000_000_500L), p[9])
        assertEquals(SyncValue.Int64(1_730_000_001_000L), p[10])
        assertEquals(SyncValue.Text("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), p[11])
        // hcRecordId is device-local — never encoded.
        assertEquals(11, p.size)
    }

    @Test
    fun apply_carries_memberId_and_drops_local_hcRecordId() = runTest {
        val dao = FakeGlucoseDao()
        val mapper = GlucoseReadingSyncMapper(dao, FakeSyncDao())
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        mapper.apply(mapper.encode(fixture(), hlc))
        val stored = dao.findById(fixture().id)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", stored?.memberId)
        assertNull("hcRecordId must not arrive over the wire", stored?.hcRecordId)
        assertEquals(hlc.packed, stored?.hlcUpdatedAt)
    }

    @Test
    fun apply_resolves_absent_memberId_to_owner() = runTest {
        val dao = FakeGlucoseDao()
        val mapper = GlucoseReadingSyncMapper(dao, FakeSyncDao(), ownerIdProvider = { "owner-id-1" })
        val rec = SyncRecord(
            type = SyncEntityType.GLUCOSE_READING,
            pk = fixture().id,
            hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL),
            deletedAt = null,
            payload = mapOf(
                1 to SyncValue.Double(95.0),
                3 to SyncValue.Text("fasting"),
                4 to SyncValue.Int64(1_730_000_000_000L),
                // no tag 11
            ),
        )
        mapper.apply(rec)
        assertEquals("owner-id-1", dao.findById(fixture().id)?.memberId)
    }

    @Test
    fun null_photo_emits_null_sync_value() {
        val mapper = GlucoseReadingSyncMapper(FakeGlucoseDao(), FakeSyncDao())
        val rec = mapper.encode(fixture().copy(photoFilename = null), Hlc.of(1L, 0, 1L))
        assertEquals(SyncValue.Null, rec.payload[8])
    }

    @Test
    fun cbor_round_trip_preserves_record() {
        val mapper = GlucoseReadingSyncMapper(FakeGlucoseDao(), FakeSyncDao())
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        val original = mapper.encode(fixture(), hlc)
        val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun apply_inserts_new_row_when_absent() = runTest {
        val dao = FakeGlucoseDao()
        val mapper = GlucoseReadingSyncMapper(dao, FakeSyncDao())
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
        val dao = FakeGlucoseDao().apply { upsert(fixture()) }
        val syncDao = FakeSyncDao()
        val mapper = GlucoseReadingSyncMapper(dao, syncDao)
        val tombstone = SyncRecord(
            type = SyncEntityType.GLUCOSE_READING,
            pk = fixture().id,
            hlc = Hlc.of(1_730_000_010_000L, 0, 0xABCDL),
            deletedAt = 1_730_000_010_000L,
            payload = emptyMap(),
        )
        mapper.apply(tombstone)
        assertNull(dao.findById(fixture().id))
        val ts = syncDao.tombstoneFor(SyncEntityType.GLUCOSE_READING.tableName, fixture().id)
        assertEquals(tombstone.hlc.packed, ts?.hlc)
        assertEquals(tombstone.deletedAt, ts?.deletedAt)
    }

    // --- B6 LWW gate over the real mapper (the CombinedRoomSyncSink composition) ---

    private fun gateOver(dao: GlucoseDao, syncDao: FakeSyncDao): LwwMerger {
        val mapper = GlucoseReadingSyncMapper(dao, syncDao)
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
    fun lww_glucose_is_pk_gated() {
        assertEquals("glucose_reading" to "id", LwwTables.pkColumnFor(SyncEntityType.GLUCOSE_READING))
    }

    @Test
    fun stale_peer_copy_does_not_overwrite_newer_local_edit() = runTest {
        val localHlc = Hlc.of(5_000L, 0, node)
        val dao = FakeGlucoseDao().apply { upsert(fixture().copy(valueMgdl = 150.0, hlcUpdatedAt = localHlc.packed)) }
        val syncDao = FakeSyncDao().apply { liveHlc = localHlc.packed }
        val gate = gateOver(dao, syncDao)

        val stalePeer = SyncRecord(
            type = SyncEntityType.GLUCOSE_READING,
            pk = fixture().id,
            hlc = Hlc.of(2_000L, 0, node),
            deletedAt = null,
            payload = mapOf(1 to SyncValue.Double(99.0), 4 to SyncValue.Int64(1L)),
        )
        assertFalse(gate.apply(stalePeer))
        assertEquals(150.0, dao.findById(fixture().id)?.valueMgdl)
    }

    @Test
    fun newer_peer_edit_overwrites_local() = runTest {
        val dao = FakeGlucoseDao().apply { upsert(fixture().copy(valueMgdl = 99.0, hlcUpdatedAt = Hlc.of(1_000L, 0, node).packed)) }
        val syncDao = FakeSyncDao().apply { liveHlc = Hlc.of(1_000L, 0, node).packed }
        val gate = gateOver(dao, syncDao)

        val newer = SyncRecord(
            type = SyncEntityType.GLUCOSE_READING,
            pk = fixture().id,
            hlc = Hlc.of(9_000L, 0, node),
            deletedAt = null,
            payload = mapOf(1 to SyncValue.Double(210.0), 4 to SyncValue.Int64(1L)),
        )
        assertTrue(gate.apply(newer))
        assertEquals(210.0, dao.findById(fixture().id)?.valueMgdl)
    }

    @Test
    fun newer_tombstone_deletes_and_records_tombstone() = runTest {
        val dao = FakeGlucoseDao().apply { upsert(fixture().copy(hlcUpdatedAt = Hlc.of(2_000L, 0, node).packed)) }
        val syncDao = FakeSyncDao().apply { liveHlc = Hlc.of(2_000L, 0, node).packed }
        val gate = gateOver(dao, syncDao)

        val tomb = SyncRecord(
            type = SyncEntityType.GLUCOSE_READING,
            pk = fixture().id,
            hlc = Hlc.of(6_000L, 0, node),
            deletedAt = 6_000L,
            payload = emptyMap(),
        )
        assertTrue(gate.apply(tomb))
        assertNull(dao.findById(fixture().id))
        assertNotNull(syncDao.tombstoneFor("glucose_reading", fixture().id))
    }

    // --- fakes ---

    private class FakeGlucoseDao : GlucoseDao {
        private val rows = mutableMapOf<String, GlucoseReadingEntity>()
        override fun observeLatest(memberId: String): Flow<GlucoseReadingEntity?> =
            flowOf(rows.values.filter { it.memberId == memberId }.maxByOrNull { it.timestamp })
        override fun observeAll(memberId: String): Flow<List<GlucoseReadingEntity>> =
            flowOf(rows.values.filter { it.memberId == memberId }.sortedByDescending { it.timestamp })
        override fun observeRange(memberId: String, from: Long, to: Long): Flow<List<GlucoseReadingEntity>> =
            flowOf(rows.values.filter { it.memberId == memberId && it.timestamp in from..to }.sortedBy { it.timestamp })
        override suspend fun count(memberId: String): Int = rows.values.count { it.memberId == memberId }
        override suspend fun findById(id: String): GlucoseReadingEntity? = rows[id]
        override suspend fun getAll(): List<GlucoseReadingEntity> = rows.values.sortedBy { it.timestamp }
        override suspend fun upsert(r: GlucoseReadingEntity) { rows[r.id] = r }
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun findUnmirrored(ownerId: String): List<GlucoseReadingEntity> =
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
