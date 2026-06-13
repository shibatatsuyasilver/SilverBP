package com.silverbp.android.sync

import com.silverbp.android.core.db.ExerciseCatalogItemEntity
import com.silverbp.android.core.db.ExerciseLibraryDao
import com.silverbp.android.core.db.SetLogEntity
import com.silverbp.android.core.db.StrengthWorkoutDao
import com.silverbp.android.core.db.StrengthWorkoutSessionEntity
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrengthSyncMappersTest {

    // ============================================================
    // exercise_catalog_item
    // ============================================================

    private fun catalogFixture(id: String = "catalog-001") = ExerciseCatalogItemEntity(
        id = id,
        name = "槓鈴臥推",
        bodyPart = "chest",
        muscleGroupsJson = "[\"胸大肌\",\"三頭肌\"]",
        description = "平板槓鈴推胸",
        isFavorite = true,
        createdAt = 1_730_000_000_500L,
        updatedAt = 1_730_000_001_000L,
        hlcUpdatedAt = "0".repeat(32),
    )

    @Test
    fun catalog_cbor_round_trip_preserves_record() {
        val mapper = ExerciseCatalogItemSyncMapper(FakeLibraryDao(), FakeSyncDao())
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        val original = mapper.encode(catalogFixture(), hlc)
        val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun catalog_round_trip_apply_preserves_is_favorite() = runTest {
        val dao = FakeLibraryDao()
        val mapper = ExerciseCatalogItemSyncMapper(dao, FakeSyncDao())
        val entity = catalogFixture()
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(mapper.encode(entity, hlc)))
        mapper.apply(decoded)

        val stored = dao.allOnceSync()
        assertEquals(1, stored.size)
        assertEquals(entity.copy(hlcUpdatedAt = hlc.packed), stored.single())
        assertTrue("isFavorite must survive", stored.single().isFavorite)
        assertEquals(SyncEntityType.EXERCISE_CATALOG_ITEM, decoded.type)
    }

    // ============================================================
    // strength_workout_session
    // ============================================================

    private fun sessionFixture(id: String = "session-001") = StrengthWorkoutSessionEntity(
        id = id,
        startedAt = 1_730_000_000_000L,
        endedAt = 1_730_000_900_000L,
        note = "胸推日",
        difficultyRaw = "just_right",
        createdAt = 1_730_000_000_500L,
        updatedAt = 1_730_000_901_000L,
        hlcUpdatedAt = "0".repeat(32),
    )

    @Test
    fun session_cbor_round_trip_preserves_record() {
        val mapper = StrengthWorkoutSessionSyncMapper(FakeStrengthDao(), FakeSyncDao())
        val hlc = Hlc.of(1_730_000_901_000L, 0, 0xABCDL)
        val original = mapper.encode(sessionFixture(), hlc)
        val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun session_round_trip_apply_preserves_difficulty() = runTest {
        val dao = FakeStrengthDao()
        val mapper = StrengthWorkoutSessionSyncMapper(dao, FakeSyncDao())
        val entity = sessionFixture()
        val hlc = Hlc.of(1_730_000_901_000L, 0, 0xABCDL)
        val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(mapper.encode(entity, hlc)))
        mapper.apply(decoded)

        val stored = dao.sessionById(entity.id)
        assertEquals(entity.copy(hlcUpdatedAt = hlc.packed), stored)
        assertEquals("just_right", stored?.difficultyRaw)
    }

    @Test
    fun session_null_difficulty_emits_null_sync_value() {
        val mapper = StrengthWorkoutSessionSyncMapper(FakeStrengthDao(), FakeSyncDao())
        val rec = mapper.encode(sessionFixture().copy(difficultyRaw = null), Hlc.of(1L, 0, 1L))
        assertEquals(com.silverbp.android.sync.engine.SyncValue.Null, rec.payload[4])
    }

    @Test
    fun session_tombstone_deletes_row_and_writes_tombstone() = runTest {
        val dao = FakeStrengthDao().apply { insertSession(sessionFixture()) }
        val syncDao = FakeSyncDao()
        val mapper = StrengthWorkoutSessionSyncMapper(dao, syncDao)
        val tombstone = mapper.encode(sessionFixture(), Hlc.of(1L, 0, 1L)).copy(
            deletedAt = 1_730_000_010_000L,
            payload = emptyMap(),
        )
        mapper.apply(tombstone)
        assertNull(dao.sessionById(sessionFixture().id))
        val ts = syncDao.tombstoneFor(SyncEntityType.STRENGTH_WORKOUT_SESSION.tableName, sessionFixture().id)
        assertEquals(tombstone.deletedAt, ts?.deletedAt)
    }

    // ============================================================
    // set_log
    // ============================================================

    private fun setFixture(id: String = "set-001", sessionId: String = "session-001") = SetLogEntity(
        id = id,
        workoutSessionId = sessionId,
        exerciseId = "catalog-001",
        setNumber = 2,
        reps = 12,
        weightKg = 47.5,
        isCompleted = true,
        skipped = false,
        notes = "RPE 8",
        createdAt = 1_730_000_000_500L,
        hlcUpdatedAt = "0".repeat(32),
    )

    @Test
    fun set_cbor_round_trip_preserves_record() {
        val mapper = SetLogSyncMapper(FakeStrengthDao())
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        val original = mapper.encode(setFixture(), hlc)
        val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun set_round_trip_apply_preserves_reps_weight_skipped() = runTest {
        // Parent session must exist for the FK gate to pass.
        val dao = FakeStrengthDao().apply { insertSession(sessionFixture()) }
        val mapper = SetLogSyncMapper(dao)
        val entity = setFixture()
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(mapper.encode(entity, hlc)))
        mapper.apply(decoded)

        val stored = dao.setsForSession(entity.workoutSessionId).single()
        assertEquals(entity.copy(hlcUpdatedAt = hlc.packed), stored)
        assertEquals(12, stored.reps)
        assertEquals(47.5, stored.weightKg!!, 0.0)
        assertEquals(false, stored.skipped)
    }

    @Test
    fun set_skipped_true_survives_round_trip() = runTest {
        val dao = FakeStrengthDao().apply { insertSession(sessionFixture()) }
        val mapper = SetLogSyncMapper(dao)
        val entity = setFixture().copy(skipped = true, weightKg = null)
        val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)
        val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(mapper.encode(entity, hlc)))
        mapper.apply(decoded)
        val stored = dao.setsForSession(entity.workoutSessionId).single()
        assertTrue("skipped must survive", stored.skipped)
        assertNull(stored.weightKg)
    }

    @Test
    fun set_orphan_without_parent_session_is_dropped() = runTest {
        val dao = FakeStrengthDao() // no parent session inserted
        val mapper = SetLogSyncMapper(dao)
        val rec = mapper.encode(setFixture(), Hlc.of(1L, 0, 1L))
        mapper.apply(rec)
        assertTrue(dao.setsForSession(setFixture().workoutSessionId).isEmpty())
    }

    // --- in-memory fakes (no Room / Robolectric required) ---

    private class FakeLibraryDao : ExerciseLibraryDao {
        private val rows = mutableMapOf<String, ExerciseCatalogItemEntity>()
        fun allOnceSync(): List<ExerciseCatalogItemEntity> = rows.values.sortedBy { it.name }
        override fun observeAll(): Flow<List<ExerciseCatalogItemEntity>> = flowOf(allOnceSync())
        override suspend fun allOnce(): List<ExerciseCatalogItemEntity> = allOnceSync()
        override fun observeFavorites(): Flow<List<ExerciseCatalogItemEntity>> =
            flowOf(rows.values.filter { it.isFavorite }.sortedBy { it.name })
        override fun search(q: String): Flow<List<ExerciseCatalogItemEntity>> =
            flowOf(rows.values.filter { it.name.contains(q) }.sortedBy { it.name })
        override fun observeByBodyPart(raw: String): Flow<List<ExerciseCatalogItemEntity>> =
            flowOf(rows.values.filter { it.bodyPart == raw }.sortedBy { it.name })
        override suspend fun setFavorite(id: String, fav: Boolean) {
            rows[id]?.let { rows[id] = it.copy(isFavorite = fav) }
        }
        override suspend fun upsert(item: ExerciseCatalogItemEntity) { rows[item.id] = item }
        override suspend fun upsertAll(items: List<ExerciseCatalogItemEntity>) {
            items.forEach { rows[it.id] = it }
        }
        override suspend fun count(): Int = rows.size
    }

    private class FakeStrengthDao : StrengthWorkoutDao {
        private val sessions = mutableMapOf<String, StrengthWorkoutSessionEntity>()
        private val sets = mutableMapOf<String, SetLogEntity>()
        override fun observeAllSessions(): Flow<List<StrengthWorkoutSessionEntity>> =
            flowOf(sessions.values.sortedByDescending { it.startedAt })
        override fun observeSessionById(id: String): Flow<StrengthWorkoutSessionEntity?> =
            flowOf(sessions[id])
        override suspend fun sessionById(id: String): StrengthWorkoutSessionEntity? = sessions[id]
        override fun observeSetsForSession(id: String): Flow<List<SetLogEntity>> =
            flowOf(sets.values.filter { it.workoutSessionId == id }.sortedBy { it.setNumber })
        override suspend fun setsForSession(id: String): List<SetLogEntity> =
            sets.values.filter { it.workoutSessionId == id }.sortedBy { it.setNumber }
        override suspend fun insertSession(session: StrengthWorkoutSessionEntity) {
            sessions[session.id] = session
        }
        override suspend fun insertSets(sets: List<SetLogEntity>) {
            sets.forEach { this.sets[it.id] = it }
        }
        override suspend fun clearSets(id: String) {
            sets.entries.removeIf { it.value.workoutSessionId == id }
        }
        override suspend fun delete(id: String) {
            sessions.remove(id)
            sets.entries.removeIf { it.value.workoutSessionId == id } // CASCADE
        }
        override suspend fun count(): Int = sessions.size
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
