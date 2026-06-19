package com.silverbp.android.coach

import com.silverbp.android.core.db.BpWorkoutAssociationDao
import com.silverbp.android.core.db.BpWorkoutAssociationEntity
import com.silverbp.android.sync.LocalSyncWriter
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BpWorkoutAssociationRepositoryTest {

    @Test
    fun add_association_stamps_hlc() = runTest {
        val dao = FakeAssocDao()
        val hlc = Hlc.of(1_000L, 0, 1L).packed
        val repo = BpWorkoutAssociationRepository(dao, FakeLocalSyncWriter(hlc))

        val id = repo.addAssociation("bp-001", "session-001", "strength", "post")

        assertEquals(hlc, dao.findById(id)?.hlcUpdatedAt)
    }

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

    private class FakeLocalSyncWriter(private val hlc: String) : LocalSyncWriter {
        override fun nextHlc(): String = hlc
        override suspend fun delete(type: SyncEntityType, pk: String) = error("unused")
    }
}
