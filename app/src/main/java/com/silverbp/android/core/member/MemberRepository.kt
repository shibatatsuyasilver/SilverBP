package com.silverbp.android.core.member

import com.silverbp.android.core.Member
import com.silverbp.android.core.db.MemberDao
import com.silverbp.android.core.db.MemberEntity
import com.silverbp.android.core.db.toDomain
import com.silverbp.android.core.db.toEntity
import com.silverbp.android.sync.LocalSyncWriter
import com.silverbp.android.sync.engine.SyncEntityType
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
class MemberRepository(
    private val dao: MemberDao,
    private val localSync: LocalSyncWriter? = null,
    /**
     * Runs a block inside a single DB transaction so the multi-row member
     * mutations below (owner reassignment, archive/sort-order + HLC stamp)
     * commit or roll back together. Defaults to a pass-through so the in-memory
     * test constructors keep compiling; production wires
     * `database.withTransaction { block() }` (see ServiceLocator) — mirrors
     * [com.silverbp.android.coach.MedicationRepository].
     */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
) {

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
        // Stamp a real HLC from the shared sync clock so a local member write is
        // never left at "0" — the LWW gate treats "0" as "no local trace" and lets
        // a stale peer overwrite it. Mirrors the other repositories' write path.
        val hlc = localSync?.nextHlc()
        // Demoting the previous owner and writing this member must commit
        // together: if the entity write failed after the demote, the table would
        // be left with no owner at all, breaking the single-owner invariant.
        inTransaction {
            if (member.isOwner) {
                val current = dao.getOwner()
                if (current != null && current.id != memberId) {
                    dao.upsert(
                        current.copy(
                            isOwner = false,
                            updatedAt = System.currentTimeMillis(),
                            hlcUpdatedAt = hlc ?: current.hlcUpdatedAt,
                        ),
                    )
                }
                cachedOwnerId = memberId
            }
            val entity = member.toEntity()
            dao.upsert(entity.copy(hlcUpdatedAt = hlc ?: entity.hlcUpdatedAt))
        }
    }

    suspend fun archive(id: UUID) {
        // Archive + HLC stamp must commit together: a failed stamp would otherwise
        // leave the member archived locally but un-propagatable over incremental
        // sync (the row keeps its old HLC — QA #3).
        inTransaction {
            dao.archive(id.toString(), System.currentTimeMillis())
            // Bump the HLC so the archive propagates over incremental sync — without
            // it the member row keeps its old HLC and a paired device never sees the
            // archive (QA #3).
            localSync?.stamp(SyncEntityType.MEMBER, id.toString())
        }
    }

    suspend fun unarchive(id: UUID) {
        inTransaction {
            dao.unarchive(id.toString(), System.currentTimeMillis())
            localSync?.stamp(SyncEntityType.MEMBER, id.toString())
        }
    }

    suspend fun updateSortOrder(id: UUID, sortOrder: Int) {
        inTransaction {
            dao.updateSortOrder(id.toString(), sortOrder, System.currentTimeMillis())
            localSync?.stamp(SyncEntityType.MEMBER, id.toString())
        }
    }

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
