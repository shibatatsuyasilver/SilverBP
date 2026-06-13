package com.silverbp.android.core.member

import com.silverbp.android.core.Member
import com.silverbp.android.core.db.MemberDao
import com.silverbp.android.core.db.MemberEntity
import com.silverbp.android.core.db.toDomain
import com.silverbp.android.core.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Read/write access to the `member` table plus enforcement of the single-owner
 * invariant (Room can't express it as a partial unique index — see
 * [com.silverbp.android.core.db.MemberEntity]).
 *
 * [ownerId] is the anchor for Health-Connect-mirrored / owner-only data and is
 * read on every BP save, so it's memoized in-process after the first hit. The
 * owner row is normally created by MIGRATION_17_18 and never deleted, but a
 * Replace-mode backup restore deletes and re-creates the whole `member` table
 * (`DELETE FROM member`, possibly minting a different owner id), so the cache is
 * read-through: [ownerId] re-validates that the memoized id still exists in the
 * table before trusting it, and [invalidateOwnerCache] lets a destructive
 * restore drop it explicitly. Without this the cache strands every owner-scoped
 * read on an id that no longer has a member row (cross-device restore data loss).
 */
class MemberRepository(private val dao: MemberDao) {

    @Volatile private var cachedOwnerId: String? = null

    fun observeActive(): Flow<List<Member>> =
        dao.observeActive().map { list -> list.map { it.toDomain() } }

    suspend fun getAll(): List<Member> = dao.getAll().map { it.toDomain() }

    suspend fun findById(id: UUID): Member? = dao.findById(id.toString())?.toDomain()

    /**
     * The owner member's id. Memoized after the first read, but read-through:
     * the cached id is only trusted while the owner row still carries it. After a
     * Replace-mode restore wipes/replaces the `member` table the cached id may no
     * longer match the live owner row (different id, or no row at all), so we
     * re-read [MemberDao.getOwner] and re-cache; if the table is now empty we
     * synthesise an owner. Falls back to synthesizing an owner only in the
     * pathological case where the migration backfill never ran (e.g. a brand-new
     * in-memory DB in tests) so callers always get a stable, valid id.
     */
    suspend fun ownerId(): String {
        val cached = cachedOwnerId
        val live = dao.getOwner()
        // Cache hit only counts if the live owner row still has that id; a
        // destructive restore can replace the owner with a different id (or none).
        if (cached != null && live?.id == cached) return cached
        val id = live?.id ?: ensureOwner().id
        cachedOwnerId = id
        return id
    }

    /**
     * Drop the memoized owner id. Called before/after a destructive restore
     * ([com.silverbp.android.backup.BackupManager] Replace mode) clears the
     * `member` table so the next [ownerId] re-reads the restored owner instead of
     * returning a now-deleted id.
     */
    fun invalidateOwnerCache() {
        cachedOwnerId = null
    }

    suspend fun owner(): Member = (dao.getOwner() ?: ensureOwner()).toDomain()

    /**
     * Insert or update a member. Saving with [Member.isOwner] = true demotes any
     * other owner first so the single-owner invariant is preserved; the entity's
     * `isOwner` index makes that lookup cheap.
     */
    suspend fun upsert(member: Member) {
        val memberId = member.id.toString()
        if (member.isOwner) {
            val current = dao.getOwner()
            if (current != null && current.id != memberId) {
                dao.upsert(current.copy(isOwner = false, updatedAt = System.currentTimeMillis()))
            }
            cachedOwnerId = memberId
        }
        dao.upsert(member.toEntity())
    }

    suspend fun archive(id: UUID) = dao.archive(id.toString(), System.currentTimeMillis())

    suspend fun unarchive(id: UUID) = dao.unarchive(id.toString(), System.currentTimeMillis())

    suspend fun updateSortOrder(id: UUID, sortOrder: Int) =
        dao.updateSortOrder(id.toString(), sortOrder, System.currentTimeMillis())

    suspend fun count(): Int = dao.count()

    /** Create a synthetic owner when none exists (migration didn't run). */
    private suspend fun ensureOwner(): MemberEntity {
        val now = System.currentTimeMillis()
        val owner = MemberEntity(
            id = UUID.randomUUID().toString(),
            displayName = "",
            isOwner = true,
            birthYear = null,
            hasDiabetes = false,
            hasCKD = false,
            hasASCVD = false,
            guideline = com.silverbp.android.core.HypertensionGuideline.Taiwan2022.raw,
            colorIndex = 0,
            sortOrder = 0,
            archived = false,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(owner)
        cachedOwnerId = owner.id
        return owner
    }
}
