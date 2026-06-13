package com.silverbp.android.core.member

import com.silverbp.android.core.db.MemberDao
import com.silverbp.android.core.db.MemberEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the owner-id cache invalidation contract (adversarial findings 1 & 4): a
 * Replace-mode restore wipes/replaces the `member` table, so the memoized owner
 * id must not survive as a phantom that strands every owner-scoped read.
 */
class MemberRepositoryTest {

    private fun owner(id: String) = MemberEntity(
        id = id,
        displayName = "",
        isOwner = true,
        birthYear = null,
        hasDiabetes = false,
        hasCKD = false,
        hasASCVD = false,
        guideline = "taiwan2022",
        colorIndex = 0,
        sortOrder = 0,
        archived = false,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun ownerId_memoizes_within_process() = runTest {
        val dao = FakeMemberDao().apply { upsert(owner("A-uuid")) }
        val repo = MemberRepository(dao)
        assertEquals("A-uuid", repo.ownerId())
        // Second call hits the cache; still correct because the live row matches.
        assertEquals("A-uuid", repo.ownerId())
    }

    @Test
    fun read_through_cache_drops_stale_id_when_live_owner_changed() = runTest {
        // Warm the cache on owner A (mirrors the cold-start anomaly-watcher path).
        val dao = FakeMemberDao().apply { upsert(owner("A-uuid")) }
        val repo = MemberRepository(dao)
        assertEquals("A-uuid", repo.ownerId())

        // Simulate a cross-device Replace import: owner A deleted, owner B inserted
        // by MemberSyncMapper (bypasses the repository, so the cache isn't updated).
        dao.deleteById("A-uuid")
        dao.upsert(owner("B-uuid"))

        // Even WITHOUT an explicit invalidate, the read-through guard must not
        // return the now-deleted A-uuid; it re-reads the live owner.
        assertEquals("B-uuid", repo.ownerId())
    }

    @Test
    fun invalidate_then_resync_picks_up_new_owner() = runTest {
        val dao = FakeMemberDao().apply { upsert(owner("A-uuid")) }
        val repo = MemberRepository(dao)
        assertEquals("A-uuid", repo.ownerId())

        // Replace import: clearSyncTables() deletes member, BackupManager calls
        // invalidateOwnerCache() inside the transaction, then owner B applies.
        dao.deleteById("A-uuid")
        repo.invalidateOwnerCache()
        dao.upsert(owner("B-uuid"))

        assertEquals("B-uuid", repo.ownerId())
    }

    @Test
    fun pre_v18_replace_synthesizes_new_owner_and_does_not_reuse_stale_cache() = runTest {
        // Warm the cache (warm owner A from a previous session).
        val dao = FakeMemberDao().apply { upsert(owner("A-uuid")) }
        val repo = MemberRepository(dao)
        assertEquals("A-uuid", repo.ownerId())

        // Pre-v18 backup Replace: member table cleared, no MEMBER records, cache
        // invalidated. ownerId() must now synthesize a fresh owner row (not reuse
        // A-uuid, which would create no row and orphan every imported reading).
        dao.deleteById("A-uuid")
        repo.invalidateOwnerCache()

        val synthesized = repo.ownerId()
        // A brand-new owner row was created (finding 4: empty member table strands
        // every reading otherwise).
        val live = dao.getOwner()
        assertNotNull("ownerId() must create an owner row when the table is empty", live)
        assertEquals(synthesized, live!!.id)
        assertTrue(live.isOwner)

        // Subsequent BP records that resolve their absent memberId via ownerId()
        // land on the SAME id the owner row carries — no split ownership.
        assertEquals(synthesized, repo.ownerId())
    }

    @Test
    fun synthesized_owner_after_invalidate_differs_from_stale_id() = runTest {
        // Regression guard: before the fix, ownerId() short-circuited on the cache
        // and returned the OLD id without creating a row. Confirm the synthesized
        // id is now backed by a real row even though it differs from the old one.
        val dao = FakeMemberDao().apply { upsert(owner("A-uuid")) }
        val repo = MemberRepository(dao)
        assertEquals("A-uuid", repo.ownerId())

        dao.deleteById("A-uuid")
        repo.invalidateOwnerCache()
        val fresh = repo.ownerId()

        assertNotEquals("A-uuid", fresh) // a new UUID was minted
        assertNotNull(dao.getOwner())     // and an actual row exists for it
    }

    // --- in-memory fake (same shape as MemberSyncMapperTest's) ---

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
}
