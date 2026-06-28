package com.silverbp.android.sync

import androidx.sqlite.db.SupportSQLiteQuery
import com.silverbp.android.core.db.BpDao
import com.silverbp.android.core.db.BpReadingEntity
import com.silverbp.android.core.db.SyncDao
import com.silverbp.android.core.db.SyncDeviceEntity
import com.silverbp.android.core.db.SyncOutboxEntity
import com.silverbp.android.core.db.TombstoneEntity
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.LwwMerger
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
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
 * App-side integration of the B6 LWW gate over a real [BpReadingSyncMapper] —
 * the exact composition [CombinedRoomSyncSink] uses (LwwMerger →
 * LwwTables.resolveLocalHlc(local row hlc, tombstone hlc) → mapper.apply). Pins
 * that a stale peer copy can no longer REPLACE a newer local edit and that a
 * stale tombstone can no longer resurrect-then-delete a newer row — the data
 * loss the audit flagged.
 */
class LwwGateIntegrationTest {

    private val node = 0x1234_5678_9ABC_DEF0L

    private fun reading(id: String, sys: Int, hlc: Hlc) = BpReadingEntity(
        id = id,
        systolic = sys,
        diastolic = 80,
        pulse = 70,
        timestamp = 1_730_000_000_000L,
        arm = "left",
        posture = "sitting",
        partOfDay = "morning",
        beforeMedication = false,
        photoFilename = null,
        confidence = 1.0,
        source = "manual",
        note = "",
        irregularHeartbeat = false,
        medicationId = null,
        memberId = "owner-1",
        createdAt = 1L,
        updatedAt = 1L,
        hlcUpdatedAt = hlc.packed,
    )

    /** Builds the gate exactly as CombinedRoomSyncSink does for a bp_reading. */
    private fun gateOver(bpDao: BpDao, syncDao: SyncDao): LwwMerger {
        val mapper = BpReadingSyncMapper(bpDao, syncDao)
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
    fun stale_peer_copy_does_not_overwrite_newer_local_edit() = runTest {
        // Local row was edited at hlc=5000 (sys=150). Peer ships its old copy
        // (hlc=2000, sys=120). The edit must survive.
        val localHlc = Hlc.of(5_000L, 0, node)
        val bpDao = FakeBpDao().apply { insert(reading("r1", 150, localHlc)) }
        val syncDao = FakeSyncDao().apply { liveHlc = localHlc.packed }
        val gate = gateOver(bpDao, syncDao)

        val stalePeer = SyncRecord(
            type = SyncEntityType.BP_READING,
            pk = "r1",
            hlc = Hlc.of(2_000L, 0, node),
            deletedAt = null,
            payload = mapOf(1 to SyncValue.Int64(120), 2 to SyncValue.Int64(80), 4 to SyncValue.Int64(1L)),
        )
        val applied = gate.apply(stalePeer)

        assertFalse("stale peer record must be rejected", applied)
        assertEquals("local edit must survive", 150, bpDao.findById("r1")?.systolic)
    }

    @Test
    fun newer_peer_edit_overwrites_local() = runTest {
        val bpDao = FakeBpDao().apply { insert(reading("r1", 120, Hlc.of(1_000L, 0, node))) }
        val syncDao = FakeSyncDao().apply { liveHlc = Hlc.of(1_000L, 0, node).packed }
        val gate = gateOver(bpDao, syncDao)

        val newer = SyncRecord(
            type = SyncEntityType.BP_READING,
            pk = "r1",
            hlc = Hlc.of(9_000L, 0, node),
            deletedAt = null,
            payload = mapOf(1 to SyncValue.Int64(160), 2 to SyncValue.Int64(95), 4 to SyncValue.Int64(1L)),
        )
        assertTrue(gate.apply(newer))
        assertEquals(160, bpDao.findById("r1")?.systolic)
    }

    @Test
    fun stale_tombstone_does_not_delete_newer_local_row() = runTest {
        // Local row re-added/edited at hlc=8000; an older delete (hlc=3000)
        // must NOT take it out.
        val localHlc = Hlc.of(8_000L, 0, node)
        val bpDao = FakeBpDao().apply { insert(reading("r1", 130, localHlc)) }
        val syncDao = FakeSyncDao().apply { liveHlc = localHlc.packed }
        val gate = gateOver(bpDao, syncDao)

        val staleTomb = SyncRecord(
            type = SyncEntityType.BP_READING,
            pk = "r1",
            hlc = Hlc.of(3_000L, 0, node),
            deletedAt = 3_000L,
            payload = emptyMap(),
        )
        assertFalse(gate.apply(staleTomb))
        assertNotNull("newer row must survive a stale tombstone", bpDao.findById("r1"))
    }

    @Test
    fun newer_tombstone_deletes_and_records_tombstone() = runTest {
        val bpDao = FakeBpDao().apply { insert(reading("r1", 130, Hlc.of(2_000L, 0, node))) }
        val syncDao = FakeSyncDao().apply { liveHlc = Hlc.of(2_000L, 0, node).packed }
        val gate = gateOver(bpDao, syncDao)

        val tomb = SyncRecord(
            type = SyncEntityType.BP_READING,
            pk = "r1",
            hlc = Hlc.of(6_000L, 0, node),
            deletedAt = 6_000L,
            payload = emptyMap(),
        )
        assertTrue(gate.apply(tomb))
        assertNull(bpDao.findById("r1"))
        assertNotNull(syncDao.tombstoneFor("bp_reading", "r1"))
    }

    @Test
    fun pre_sync_zero_hlc_loses_to_real_incoming() = runTest {
        // A legacy local row with hlcUpdatedAt="0" must be overwritable by any
        // real inbound HLC (resolveLocalHlc treats "0" as no local trace).
        val bpDao = FakeBpDao().apply { insert(reading("r1", 110, Hlc.ZERO).copy(hlcUpdatedAt = "0")) }
        val syncDao = FakeSyncDao().apply { liveHlc = "0" }
        val gate = gateOver(bpDao, syncDao)

        val incoming = SyncRecord(
            type = SyncEntityType.BP_READING,
            pk = "r1",
            hlc = Hlc.of(1_000L, 0, node),
            deletedAt = null,
            payload = mapOf(1 to SyncValue.Int64(140), 2 to SyncValue.Int64(90), 4 to SyncValue.Int64(1L)),
        )
        assertTrue("real HLC must beat pre-sync '0'", gate.apply(incoming))
        assertEquals(140, bpDao.findById("r1")?.systolic)
    }

    @Test
    fun resolveLocalHlc_picks_greater_of_live_and_tombstone() {
        val older = Hlc.of(1_000L, 0, node).packed
        val newer = Hlc.of(2_000L, 0, node).packed
        assertEquals(Hlc(newer), LwwTables.resolveLocalHlc(live = older, tombstone = newer))
        assertEquals(Hlc(newer), LwwTables.resolveLocalHlc(live = newer, tombstone = older))
        assertNull(LwwTables.resolveLocalHlc(live = null, tombstone = null))
        assertNull(LwwTables.resolveLocalHlc(live = "0", tombstone = null))
        assertNull(LwwTables.resolveLocalHlc(live = "0".repeat(Hlc.HEX_LEN), tombstone = "0"))
    }

    // --- fakes ---

    private class FakeBpDao : BpDao {
        private val rows = mutableMapOf<String, BpReadingEntity>()
        override fun observeLatest(): Flow<BpReadingEntity?> = flowOf(rows.values.firstOrNull())
        override fun observeAll(): Flow<List<BpReadingEntity>> = flowOf(rows.values.toList())
        override fun observeRange(from: Long, to: Long): Flow<List<BpReadingEntity>> = flowOf(emptyList())
        override fun observeLatest(memberId: String): Flow<BpReadingEntity?> = flowOf(null)
        override fun observeAll(memberId: String): Flow<List<BpReadingEntity>> = flowOf(emptyList())
        override fun observeRange(memberId: String, from: Long, to: Long): Flow<List<BpReadingEntity>> =
            flowOf(emptyList())
        override suspend fun count(memberId: String): Int = 0
        override suspend fun findById(id: String): BpReadingEntity? = rows[id]
        override suspend fun insert(r: BpReadingEntity) { rows[r.id] = r }
        override suspend fun update(r: BpReadingEntity) { rows[r.id] = r }
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun count(): Int = rows.size
        override suspend fun findUnmirrored(ownerId: String): List<BpReadingEntity> = emptyList()
    }

    private class FakeSyncDao : SyncDao {
        override suspend fun allDevices(): List<com.silverbp.android.core.db.SyncDeviceEntity> = emptyList()
        /** Controllable live-row hlc returned by [localRowHlc]. */
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
