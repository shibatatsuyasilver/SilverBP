package com.silverbp.android.strength

import com.silverbp.android.core.db.ExerciseCatalogItemEntity
import com.silverbp.android.core.db.ExerciseLibraryDao
import com.silverbp.android.core.db.SetLogEntity
import com.silverbp.android.core.db.StrengthWorkoutDao
import com.silverbp.android.core.db.StrengthWorkoutSessionEntity
import com.silverbp.android.core.db.TombstoneEntity
import com.silverbp.android.sync.LocalSyncWriter
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrengthWorkoutRepositoryTest {

    private fun catalogItem() = ExerciseCatalogItem(
        id = "catalog-001",
        name = "Bench Press",
        bodyPart = BodyPart.UpperBody,
        muscleGroups = listOf("chest"),
        description = "",
    )

    private fun session(vararg sets: SetLog) = StrengthWorkoutSession(
        id = "session-001",
        startedAt = 1_730_000_000_000L,
        endedAt = 1_730_000_900_000L,
        note = "workout",
        difficulty = DifficultyFeedback.JustRight,
        items = listOf(catalogItem() to sets.toList()),
    )

    @Test
    fun upsert_stamps_session_and_set_logs_with_hlc() = runTest {
        val dao = FakeStrengthDao()
        val writer = FakeLocalSyncWriter(
            Hlc.of(1_000L, 0, 1L).packed,
            Hlc.of(1_001L, 0, 1L).packed,
        )
        val repo = StrengthWorkoutRepository(dao, FakeLibraryDao(), writer)

        repo.upsert(
            session(
                SetLog("set-001", "catalog-001", setNumber = 1, reps = 10),
            ),
        )

        assertEquals(Hlc.of(1_000L, 0, 1L).packed, dao.sessionById("session-001")?.hlcUpdatedAt)
        assertEquals(Hlc.of(1_001L, 0, 1L).packed, dao.setsForSession("session-001").single().hlcUpdatedAt)
    }

    @Test
    fun upsert_writes_tombstone_for_removed_set_log_in_same_mutation_path() = runTest {
        val dao = FakeStrengthDao().apply {
            insertSession(StrengthWorkoutSessionEntity("session-001", 1L, 2L, "", null, 1L, 2L))
            insertSets(
                listOf(
                    SetLogEntity("keep-set", "session-001", "catalog-001", 1, 10, null, true, false, "", 1L),
                    SetLogEntity("removed-set", "session-001", "catalog-001", 2, 8, null, true, false, "", 1L),
                ),
            )
        }
        val deletedHlc = Hlc.of(1_000L, 0, 1L).packed
        val writer = FakeLocalSyncWriter(
            deletedHlc,
            Hlc.of(1_001L, 0, 1L).packed,
        )
        val repo = StrengthWorkoutRepository(dao, FakeLibraryDao(), writer)

        repo.upsert(session(SetLog("keep-set", "catalog-001", setNumber = 1, reps = 10)))

        assertEquals(listOf("keep-set"), dao.setIdsForSession("session-001"))
        val tombstone = dao.tombstones.single()
        assertEquals(SyncEntityType.SET_LOG.tableName, tombstone.entityType)
        assertEquals("removed-set", tombstone.pk)
        assertEquals(deletedHlc, tombstone.hlc)
    }

    @Test
    fun delete_writes_session_and_set_log_tombstones() = runTest {
        val dao = FakeStrengthDao().apply {
            insertSession(StrengthWorkoutSessionEntity("session-001", 1L, 2L, "", null, 1L, 2L))
            insertSets(
                listOf(
                    SetLogEntity("set-001", "session-001", "catalog-001", 1, 10, null, true, false, "", 1L),
                ),
            )
        }
        val deleteHlc = Hlc.of(2_000L, 0, 1L).packed
        val repo = StrengthWorkoutRepository(dao, FakeLibraryDao(), FakeLocalSyncWriter(deleteHlc))

        repo.delete("session-001")

        assertEquals(null, dao.sessionById("session-001"))
        assertTrue(dao.setsForSession("session-001").isEmpty())
        assertEquals(
            setOf(
                SyncEntityType.STRENGTH_WORKOUT_SESSION.tableName to "session-001",
                SyncEntityType.SET_LOG.tableName to "set-001",
            ),
            dao.tombstones.map { it.entityType to it.pk }.toSet(),
        )
        assertTrue(dao.tombstones.all { it.hlc == deleteHlc })
    }

    private class FakeStrengthDao : StrengthWorkoutDao {
        private val sessions = mutableMapOf<String, StrengthWorkoutSessionEntity>()
        private val sets = mutableMapOf<String, SetLogEntity>()
        val tombstones = mutableListOf<TombstoneEntity>()
        override fun observeAllSessions(): Flow<List<StrengthWorkoutSessionEntity>> =
            flowOf(sessions.values.sortedByDescending { it.startedAt })
        override fun observeSessionById(id: String): Flow<StrengthWorkoutSessionEntity?> = flowOf(sessions[id])
        override suspend fun sessionById(id: String): StrengthWorkoutSessionEntity? = sessions[id]
        override fun observeSetsForSession(id: String): Flow<List<SetLogEntity>> =
            flowOf(sets.values.filter { it.workoutSessionId == id }.sortedBy { it.setNumber })
        override suspend fun setsForSession(id: String): List<SetLogEntity> =
            sets.values.filter { it.workoutSessionId == id }.sortedBy { it.setNumber }
        override suspend fun setIdsForSession(id: String): List<String> =
            sets.values.filter { it.workoutSessionId == id }.sortedBy { it.setNumber }.map { it.id }
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
            sets.entries.removeIf { it.value.workoutSessionId == id }
        }
        override suspend fun upsertTombstones(tombstones: List<TombstoneEntity>) {
            this.tombstones += tombstones
        }
        override suspend fun count(): Int = sessions.size
    }

    private class FakeLibraryDao : ExerciseLibraryDao {
        override fun observeAll(): Flow<List<ExerciseCatalogItemEntity>> = flowOf(emptyList())
        override suspend fun allOnce(): List<ExerciseCatalogItemEntity> = emptyList()
        override fun observeFavorites(): Flow<List<ExerciseCatalogItemEntity>> = flowOf(emptyList())
        override fun search(q: String): Flow<List<ExerciseCatalogItemEntity>> = flowOf(emptyList())
        override fun observeByBodyPart(raw: String): Flow<List<ExerciseCatalogItemEntity>> = flowOf(emptyList())
        override suspend fun setFavorite(id: String, fav: Boolean) {}
        override suspend fun setFavoriteWithHlc(id: String, fav: Boolean, hlc: String) {}
        override suspend fun upsert(item: ExerciseCatalogItemEntity) {}
        override suspend fun upsertAll(items: List<ExerciseCatalogItemEntity>) {}
        override suspend fun count(): Int = 0
    }

    private class FakeLocalSyncWriter(vararg values: String) : LocalSyncWriter {
        private val hlcs = ArrayDeque(values.toList())
        override fun nextHlc(): String = hlcs.removeFirst()
        override suspend fun delete(type: SyncEntityType, pk: String) = error("unused")
    }
}
