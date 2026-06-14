package com.silverbp.android.sync

import com.silverbp.android.core.db.MemberDao
import com.silverbp.android.core.db.MemberEntity
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberSyncMapperTest {

    private fun fixture(
        id: String = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        isOwner: Boolean = false,
    ) = MemberEntity(
        id = id,
        displayName = "外婆",
        isOwner = isOwner,
        birthYear = 1948,
        heightCm = 158,
        biologicalSex = "female",
        targetWeightKg = 55.5,
        hasDiabetes = true,
        hasCKD = false,
        hasASCVD = true,
        guideline = "taiwan_2022",
        colorIndex = 3,
        sortOrder = 2,
        archived = false,
        createdAt = 1_730_000_000_500L,
        updatedAt = 1_730_000_001_000L,
        hlcUpdatedAt = "0".repeat(32),
    )

    @Test
    fun encode_populates_every_field_tag() {
        val mapper = MemberSyncMapper(FakeMemberDao(), FakeSyncDao())
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xCAFEBABEL)
        val rec = mapper.encode(fixture(), hlc)

        assertEquals(SyncEntityType.MEMBER, rec.type)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", rec.pk)
        assertEquals(hlc, rec.hlc)
        assertNull(rec.deletedAt)

        val p = rec.payload
        assertEquals(SyncValue.Text("外婆"), p[1])
        assertEquals(SyncValue.Bool(false), p[2])
        assertEquals(SyncValue.Int64(1948), p[3])
        assertEquals(SyncValue.Bool(true), p[4])
        assertEquals(SyncValue.Bool(false), p[5])
        assertEquals(SyncValue.Bool(true), p[6])
        assertEquals(SyncValue.Text("taiwan_2022"), p[7])
        assertEquals(SyncValue.Int64(3), p[8])
        assertEquals(SyncValue.Int64(2), p[9])
        assertEquals(SyncValue.Bool(false), p[10])
        assertEquals(SyncValue.Int64(1_730_000_000_500L), p[11])
        assertEquals(SyncValue.Int64(1_730_000_001_000L), p[12])
        // Tags 13–15: weight-feature profile fields.
        assertEquals(SyncValue.Int64(158), p[13])
        assertEquals(SyncValue.Text("female"), p[14])
        assertEquals(SyncValue.Double(55.5), p[15])
    }

    @Test
    fun null_birth_year_emits_null() {
        val mapper = MemberSyncMapper(FakeMemberDao(), FakeSyncDao())
        val rec = mapper.encode(fixture().copy(birthYear = null), Hlc.of(1L, 0, 1L))
        assertEquals(SyncValue.Null, rec.payload[3])
    }

    @Test
    fun null_profile_fields_emit_null() {
        val mapper = MemberSyncMapper(FakeMemberDao(), FakeSyncDao())
        val rec = mapper.encode(
            fixture().copy(heightCm = null, biologicalSex = null, targetWeightKg = null),
            Hlc.of(1L, 0, 1L),
        )
        assertEquals(SyncValue.Null, rec.payload[13])
        assertEquals(SyncValue.Null, rec.payload[14])
        assertEquals(SyncValue.Null, rec.payload[15])
    }

    @Test
    fun cbor_round_trip_preserves_record() {
        val mapper = MemberSyncMapper(FakeMemberDao(), FakeSyncDao())
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        val original = mapper.encode(fixture(), hlc)
        val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun apply_round_trip_restores_entity() = runTest {
        val dao = FakeMemberDao()
        val mapper = MemberSyncMapper(dao, FakeSyncDao())
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        mapper.apply(mapper.encode(fixture(), hlc))

        val stored = dao.findById(fixture().id)
        assertEquals(fixture().copy(hlcUpdatedAt = hlc.packed), stored)
    }

    @Test
    fun apply_tombstone_deletes_non_owner_and_writes_tombstone() = runTest {
        val dao = FakeMemberDao().apply { upsert(fixture()) }
        val syncDao = FakeSyncDao()
        val mapper = MemberSyncMapper(dao, syncDao)
        val tomb = SyncRecord(
            type = SyncEntityType.MEMBER,
            pk = fixture().id,
            hlc = Hlc.of(1_730_000_010_000L, 0, 0xABCDL),
            deletedAt = 1_730_000_010_000L,
            payload = emptyMap(),
        )
        mapper.apply(tomb)

        assertNull("non-owner row should be deleted", dao.findById(fixture().id))
        val ts = syncDao.tombstoneFor(SyncEntityType.MEMBER.tableName, fixture().id)
        assertEquals(tomb.hlc.packed, ts?.hlc)
    }

    @Test
    fun apply_tombstone_never_deletes_owner_but_still_records_tombstone() = runTest {
        // The owner is the anchor for owner-only data — a stray owner tombstone
        // must not orphan every owner reading.
        val owner = fixture(id = "owner-1", isOwner = true)
        val dao = FakeMemberDao().apply { upsert(owner) }
        val syncDao = FakeSyncDao()
        val mapper = MemberSyncMapper(dao, syncDao)
        val tomb = SyncRecord(
            type = SyncEntityType.MEMBER,
            pk = owner.id,
            hlc = Hlc.of(1_730_000_010_000L, 0, 0xABCDL),
            deletedAt = 1_730_000_010_000L,
            payload = emptyMap(),
        )
        mapper.apply(tomb)

        assertNotNull("owner row must survive a tombstone", dao.findById(owner.id))
        assertTrue(dao.findById(owner.id)!!.isOwner)
    }

    // --- in-memory fakes ---

    private class FakeMemberDao : MemberDao {
        private val rows = mutableMapOf<String, MemberEntity>()
        override fun observeActive(): Flow<List<MemberEntity>> =
            flowOf(rows.values.filter { !it.archived }.sortedBy { it.sortOrder })
        override suspend fun getAll(): List<MemberEntity> = rows.values.sortedBy { it.sortOrder }
        override suspend fun getOwner(): MemberEntity? = rows.values.firstOrNull { it.isOwner }
        override suspend fun findById(id: String): MemberEntity? = rows[id]
        override suspend fun upsert(m: MemberEntity) { rows[m.id] = m }
        override suspend fun archive(id: String, now: Long) {
            rows[id]?.let { rows[id] = it.copy(archived = true, updatedAt = now) }
        }
        override suspend fun unarchive(id: String, now: Long) {
            rows[id]?.let { rows[id] = it.copy(archived = false, updatedAt = now) }
        }
        override suspend fun updateSortOrder(id: String, sortOrder: Int, now: Long) {
            rows[id]?.let { rows[id] = it.copy(sortOrder = sortOrder, updatedAt = now) }
        }
        override suspend fun count(): Int = rows.size
        override suspend fun deleteById(id: String) { rows.remove(id) }
    }

    private class FakeSyncDao : SyncDao {
        private val tombstones = mutableMapOf<Pair<String, String>, TombstoneEntity>()
        override suspend fun upsertTombstone(tombstone: TombstoneEntity) {
            tombstones[tombstone.entityType to tombstone.pk] = tombstone
        }
        override suspend fun rawHlc(query: androidx.sqlite.db.SupportSQLiteQuery): String? = null
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
