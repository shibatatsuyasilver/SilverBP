package com.silverbp.android.core

import com.silverbp.android.core.db.GlucoseDao
import com.silverbp.android.core.db.toDomain
import com.silverbp.android.core.db.toEntity
import com.silverbp.android.core.member.MemberRepository
import com.silverbp.android.sync.LocalSyncWriter
import com.silverbp.android.sync.engine.SyncEntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

/**
 * One-way mirror sink for glucose readings, implemented by the integration
 * track's `health.HealthConnectGlucoseBridge`. Declared here (not imported from
 * `health`) so the v19 data layer compiles standalone before the bridge lands;
 * [GlucoseRepository] accepts a nullable instance, exactly like [BpRepository]
 * takes a nullable `HealthConnectBpBridge`.
 */
interface GlucoseHealthConnectBridge {
    /** Write one reading; returns the HC record id on success, null on any failure. */
    suspend fun write(reading: GlucoseReading): String?
}

/**
 * Member-scoped read/write access to glucose readings, with the owner-only
 * Health Connect mirror guard. Mirrors [BpRepository] exactly: the mirror only
 * fires for the owner member's readings (writing a family member's glucose into
 * the device owner's Google Health would be a correctness / privacy bug, roadmap
 * §3-5 / §4-4), and [GlucoseDao.findUnmirrored] is already owner-filtered so the
 * retry set never picks up non-owner rows.
 */
class GlucoseRepository(
    private val dao: GlucoseDao,
    private val members: MemberRepository,
    private val healthConnect: GlucoseHealthConnectBridge? = null,
    /** Coarse on/off for the Health Connect mirror; defaults off for tests. */
    private val healthConnectEnabled: suspend () -> Boolean = { false },
    private val localSync: LocalSyncWriter? = null,
) {

    // ============================================================
    // Member-scoped reads (v19) — the primary API.
    // ============================================================

    fun observeLatest(memberId: String): Flow<GlucoseReading?> =
        dao.observeLatest(memberId).map { it?.toDomain() }

    fun observeAll(memberId: String): Flow<List<GlucoseReading>> =
        dao.observeAll(memberId).map { list -> list.map { it.toDomain() } }

    fun observeRange(memberId: String, from: Instant, to: Instant): Flow<List<GlucoseReading>> =
        dao.observeRange(memberId, from.toEpochMilli(), to.toEpochMilli())
            .map { list -> list.map { it.toDomain() } }

    /** Per-member count — backs the free-tier 10-record gate (roadmap §4-6). */
    suspend fun count(memberId: String): Int = dao.count(memberId)

    /** The device owner's member id; owner-only consumers scope their reads with it. */
    suspend fun ownerId(): String = members.ownerId()

    suspend fun findById(id: UUID): GlucoseReading? = dao.findById(id.toString())?.toDomain()

    suspend fun upsert(reading: GlucoseReading) {
        val now = Instant.now()
        val existing = dao.findById(reading.id.toString())
        // Empty memberId (fresh drafts) resolves to the owner so a reading is
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
            // Health Connect record. Dropping it here also re-satisfies the
            // invariant if the reading is later moved back to the owner.
            hcRecordId = if (memberId == ownerId) {
                reading.hcRecordId ?: existing?.hcRecordId
            } else {
                null
            },
        )
        val entity = toSave.toEntity().copy(
            hlcUpdatedAt = localSync?.nextHlc() ?: existing?.hlcUpdatedAt ?: "0",
        )
        dao.upsert(entity)

        // Best-effort one-way mirror to Health Connect; never fails the local
        // save (the bridge swallows its own errors, and the enabled-check is
        // wrapped so a settings read can't throw out of here). On success we
        // stamp the returned record id back so the glucose sync worker won't
        // retry it. Owner-only guard (roadmap §3-5): a family member's glucose
        // must NOT be written into the device owner's Google Health.
        if (memberId == ownerId &&
            healthConnect != null &&
            runCatching { healthConnectEnabled() }.getOrDefault(false)
        ) {
            val hcId = healthConnect.write(toSave)
            if (hcId != null && hcId != toSave.hcRecordId) {
                val mirrored = toSave.copy(hcRecordId = hcId, updatedAt = Instant.now())
                dao.upsert(
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
            localSync.delete(SyncEntityType.GLUCOSE_READING, pk)
        } else {
            dao.delete(pk)
        }
    }

    /** Owner-only retry set for the Health Connect mirror (mirrors [BpRepository]). */
    suspend fun findUnmirrored(): List<GlucoseReading> =
        dao.findUnmirrored(members.ownerId()).map { it.toDomain() }
}
