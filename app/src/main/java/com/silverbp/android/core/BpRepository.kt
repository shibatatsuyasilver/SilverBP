package com.silverbp.android.core

import com.silverbp.android.core.db.BpDao
import com.silverbp.android.core.db.toDomain
import com.silverbp.android.core.db.toEntity
import com.silverbp.android.core.member.MemberRepository
import com.silverbp.android.health.HealthConnectBpBridge
import com.silverbp.android.sync.LocalSyncWriter
import com.silverbp.android.sync.engine.SyncEntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

class BpRepository(
    private val dao: BpDao,
    private val members: MemberRepository,
    private val healthConnect: HealthConnectBpBridge? = null,
    /** Coarse on/off for the Health Connect mirror; defaults off for tests. */
    private val healthConnectEnabled: suspend () -> Boolean = { false },
    private val localSync: LocalSyncWriter? = null,
) {

    // ============================================================
    // Member-scoped reads (v18) — the primary API.
    // ============================================================

    fun observeLatest(memberId: String): Flow<BpReading?> =
        dao.observeLatest(memberId).map { it?.toDomain() }

    fun observeAll(memberId: String): Flow<List<BpReading>> =
        dao.observeAll(memberId).map { list -> list.map { it.toDomain() } }

    fun observeRange(memberId: String, from: Instant, to: Instant): Flow<List<BpReading>> =
        dao.observeRange(memberId, from.toEpochMilli(), to.toEpochMilli())
            .map { list -> list.map { it.toDomain() } }

    suspend fun count(memberId: String): Int = dao.count(memberId)

    /**
     * The device owner's member id. Owner-only consumers (Coach, Chat context,
     * exercise/workout BP gates, the anomaly watcher — none of which are
     * member-scoped in Phase 1) call this to scope their reads explicitly
     * instead of relying on a magic un-scoped query.
     */
    suspend fun ownerId(): String = members.ownerId()

    suspend fun findById(id: UUID): BpReading? = dao.findById(id.toString())?.toDomain()

    suspend fun upsert(reading: BpReading) {
        val now = Instant.now()
        val existing = dao.findById(reading.id.toString())
        // Empty memberId (legacy drafts) resolves to the owner so a reading is
        // never stranded without an owner.
        val ownerId = members.ownerId()
        val memberId = reading.memberId.ifBlank { ownerId }
        val toSave = reading.copy(
            memberId = memberId,
            updatedAt = now,
            createdAt = if (existing == null) now else reading.createdAt,
            // Owner rows keep any prior mirror id the caller didn't carry, so
            // editing an unchanged reading doesn't needlessly clear it and
            // re-mirror. Non-owner rows must ALWAYS have hcRecordId == null
            // (roadmap §3-5): re-attributing a previously-mirrored owner reading
            // to a family member would otherwise inherit the owner's stale mirror
            // id off `existing`, leaving a non-owner row pointing at the owner's
            // Health Connect record (a cross-member privacy bug). Dropping it here
            // also re-satisfies the §3-5 invariant if the reading is later moved
            // back to the owner (null id → the mirror guard re-mirrors it).
            hcRecordId = if (memberId == ownerId) {
                reading.hcRecordId ?: existing?.hcRecordId
            } else {
                null
            },
        )
        val entity = toSave.toEntity().copy(
            hlcUpdatedAt = localSync?.nextHlc() ?: existing?.hlcUpdatedAt ?: "0",
        )
        if (existing == null) dao.insert(entity) else dao.update(entity)

        // Best-effort one-way mirror to Health Connect so the reading flows into
        // Google Health / other health apps. Must never fail the local save: the
        // bridge swallows its own errors and the enabled-check is wrapped so a
        // settings read can't throw out of here. On success we stamp the returned
        // record id back so [com.silverbp.android.health.BpSyncWorker] knows this
        // one is done and won't retry it (mirrors ExerciseRepository.upsert).
        //
        // Owner-only guard (roadmap §3-5): a family member's BP must NOT be
        // written into the device owner's Google Health — that's a correctness /
        // privacy bug. Non-owner rows stay hcRecordId == null by design.
        if (memberId == ownerId &&
            healthConnect != null &&
            runCatching { healthConnectEnabled() }.getOrDefault(false)
        ) {
            val hcId = healthConnect.write(toSave)
            if (hcId != null && hcId != toSave.hcRecordId) {
                val mirrored = toSave.copy(hcRecordId = hcId, updatedAt = Instant.now())
                dao.update(
                    mirrored.toEntity().copy(
                        hlcUpdatedAt = localSync?.nextHlc() ?: entity.hlcUpdatedAt,
                    ),
                )
            }
        }
    }

    suspend fun delete(id: UUID) {
        val pk = id.toString()
        if (localSync != null) {
            localSync.delete(SyncEntityType.BP_READING, pk)
        } else {
            dao.delete(pk)
        }
    }
}
