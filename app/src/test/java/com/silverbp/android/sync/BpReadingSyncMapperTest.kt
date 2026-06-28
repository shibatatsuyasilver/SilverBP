package com.silverbp.android.sync

import com.silverbp.android.core.db.BpDao
import com.silverbp.android.core.db.BpReadingEntity
import com.silverbp.android.core.db.SyncDao
import com.silverbp.android.core.db.SyncDeviceEntity
import com.silverbp.android.core.db.SyncOutboxEntity
import com.silverbp.android.core.db.TombstoneEntity
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.transport.SyncRecordCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BpReadingSyncMapperTest {

    private fun fixture(id: String = "362c65d9-d66f-48bf-bafd-1c98e1d9bd81") = BpReadingEntity(
        id = id,
        systolic = 132,
        diastolic = 84,
        pulse = 71,
        timestamp = 1_730_000_000_000L,
        arm = "right",
        posture = "supine",
        partOfDay = "evening",
        beforeMedication = false,
        photoFilename = "bp/2026-05-07-01.jpg",
        confidence = 0.87,
        source = "camera_gemma",
        note = "晚餐後一小時",
        irregularHeartbeat = true,
        medicationId = "11111111-2222-3333-4444-555555555555",
        memberId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        createdAt = 1_730_000_000_500L,
        updatedAt = 1_730_000_001_000L,
        hlcUpdatedAt = "0".repeat(32),
    )

    @Test
    fun encode_populates_every_field_tag() {
        val mapper = BpReadingSyncMapper(FakeBpDao(), FakeSyncDao())
        val entity = fixture()
        val hlc = Hlc.of(physicalMs = 1_730_000_001_000L, logical = 0, nodeId = 0xCAFEBABEL)
        val rec = mapper.encode(entity, hlc)

        assertEquals(SyncEntityType.BP_READING, rec.type)
        assertEquals(entity.id, rec.pk)
        assertEquals(hlc, rec.hlc)
        assertNull(rec.deletedAt)

        val p = rec.payload
        assertEquals(SyncValue.Int64(132), p[1])
        assertEquals(SyncValue.Int64(84), p[2])
        assertEquals(SyncValue.Int64(71), p[3])
        assertEquals(SyncValue.Int64(1_730_000_000_000L), p[4])
        assertEquals(SyncValue.Text("right"), p[5])
        assertEquals(SyncValue.Text("supine"), p[6])
        assertEquals(SyncValue.Text("evening"), p[7])
        assertEquals(SyncValue.Bool(false), p[8])
        assertEquals(SyncValue.Text("bp/2026-05-07-01.jpg"), p[9])
        assertEquals(SyncValue.Double(0.87), p[10])
        assertEquals(SyncValue.Text("camera_gemma"), p[11])
        assertEquals(SyncValue.Text("晚餐後一小時"), p[12])
        assertEquals(SyncValue.Bool(true), p[13])
        assertEquals(SyncValue.Text("11111111-2222-3333-4444-555555555555"), p[14])
        assertEquals(SyncValue.Int64(1_730_000_000_500L), p[15])
        assertEquals(SyncValue.Int64(1_730_000_001_000L), p[16])
        // tag 17 — v18 owning member id.
        assertEquals(SyncValue.Text("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), p[17])
    }

    @Test
    fun apply_carries_memberId_when_present() = runTest {
        val bpDao = FakeBpDao()
        val mapper = BpReadingSyncMapper(bpDao, FakeSyncDao()) // owner provider unused
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        mapper.apply(mapper.encode(fixture(), hlc))
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", bpDao.findById(fixture().id)?.memberId)
    }

    @Test
    fun apply_resolves_absent_memberId_to_owner() = runTest {
        // A pre-v18 peer / backup sends no tag-17 field → resolve to owner.
        val bpDao = FakeBpDao()
        val mapper = BpReadingSyncMapper(
            bpDao,
            FakeSyncDao(),
            ownerIdProvider = { "owner-id-1" },
        )
        val rec = SyncRecord(
            type = SyncEntityType.BP_READING,
            pk = fixture().id,
            hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL),
            deletedAt = null,
            payload = mapOf(
                1 to SyncValue.Int64(120),
                2 to SyncValue.Int64(78),
                4 to SyncValue.Int64(1_730_000_000_000L),
                // no tag 17
            ),
        )
        mapper.apply(rec)
        assertEquals("owner-id-1", bpDao.findById(fixture().id)?.memberId)
    }

    @Test
    fun null_optional_fields_emit_null_sync_value() {
        val mapper = BpReadingSyncMapper(FakeBpDao(), FakeSyncDao())
        val entity = fixture().copy(
            pulse = null,
            photoFilename = null,
            medicationId = null,
        )
        val rec = mapper.encode(entity, Hlc.ZERO.let { Hlc.of(1L, 0, 1L) })
        assertEquals(SyncValue.Null, rec.payload[3])
        assertEquals(SyncValue.Null, rec.payload[9])
        assertEquals(SyncValue.Null, rec.payload[14])
    }

    @Test
    fun cbor_round_trip_preserves_record() {
        val mapper = BpReadingSyncMapper(FakeBpDao(), FakeSyncDao())
        val entity = fixture()
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        val original = mapper.encode(entity, hlc)
        val bytes = SyncRecordCodec.encode(original)
        val decoded = SyncRecordCodec.decode(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun apply_inserts_new_row_when_absent() = runTest {
        val bpDao = FakeBpDao()
        val syncDao = FakeSyncDao()
        val mapper = BpReadingSyncMapper(bpDao, syncDao)
        val entity = fixture()
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        val record = mapper.encode(entity, hlc)
        mapper.apply(record)

        val stored = bpDao.findById(entity.id)
        assertEquals(entity.copy(hlcUpdatedAt = hlc.packed), stored)
    }

    @Test
    fun apply_tombstone_deletes_row_and_writes_tombstone() = runTest {
        val bpDao = FakeBpDao().apply {
            insert(fixture())
        }
        val syncDao = FakeSyncDao()
        val mapper = BpReadingSyncMapper(bpDao, syncDao)

        val tombstone = SyncRecord(
            type = SyncEntityType.BP_READING,
            pk = fixture().id,
            hlc = Hlc.of(1_730_000_010_000L, 0, 0xABCDL),
            deletedAt = 1_730_000_010_000L,
            payload = emptyMap(),
        )
        mapper.apply(tombstone)

        assertNull("row should be deleted", bpDao.findById(fixture().id))
        val ts = syncDao.tombstoneFor(SyncEntityType.BP_READING.tableName, fixture().id)
        assertEquals(tombstone.hlc.packed, ts?.hlc)
        assertEquals(tombstone.deletedAt, ts?.deletedAt)
    }

    // --- in-memory fakes (no Room / Robolectric required) ---

    private class FakeBpDao : BpDao {
        private val rows = mutableMapOf<String, BpReadingEntity>()

        override fun observeLatest(): Flow<BpReadingEntity?> =
            flowOf(rows.values.maxByOrNull { it.timestamp })
        override fun observeAll(): Flow<List<BpReadingEntity>> =
            flowOf(rows.values.sortedByDescending { it.timestamp })
        override fun observeRange(from: Long, to: Long): Flow<List<BpReadingEntity>> =
            flowOf(rows.values.filter { it.timestamp in from..to }.sortedBy { it.timestamp })
        override fun observeLatest(memberId: String): Flow<BpReadingEntity?> =
            flowOf(rows.values.filter { it.memberId == memberId }.maxByOrNull { it.timestamp })
        override fun observeAll(memberId: String): Flow<List<BpReadingEntity>> =
            flowOf(rows.values.filter { it.memberId == memberId }.sortedByDescending { it.timestamp })
        override fun observeRange(memberId: String, from: Long, to: Long): Flow<List<BpReadingEntity>> =
            flowOf(
                rows.values.filter { it.memberId == memberId && it.timestamp in from..to }
                    .sortedBy { it.timestamp },
            )
        override suspend fun count(memberId: String): Int =
            rows.values.count { it.memberId == memberId }
        override suspend fun findById(id: String): BpReadingEntity? = rows[id]
        override suspend fun insert(r: BpReadingEntity) { rows[r.id] = r }
        override suspend fun update(r: BpReadingEntity) { rows[r.id] = r }
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun count(): Int = rows.size
        override suspend fun findUnmirrored(ownerId: String): List<BpReadingEntity> =
            rows.values.filter { it.hcRecordId == null && it.memberId == ownerId }
    }

    private class FakeSyncDao : SyncDao {
        override suspend fun allDevices(): List<com.silverbp.android.core.db.SyncDeviceEntity> = emptyList()
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
        override suspend fun upsertDevice(device: SyncDeviceEntity) {
            devices[device.deviceId] = device
        }
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
