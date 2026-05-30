package com.silverbp.android.core

import com.silverbp.android.core.db.BpDao
import com.silverbp.android.core.db.toDomain
import com.silverbp.android.core.db.toEntity
import com.silverbp.android.health.HealthConnectBpBridge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

class BpRepository(
    private val dao: BpDao,
    private val healthConnect: HealthConnectBpBridge? = null,
    /** Coarse on/off for the Health Connect mirror; defaults off for tests. */
    private val healthConnectEnabled: suspend () -> Boolean = { false },
) {

    fun observeLatest(): Flow<BpReading?> = dao.observeLatest().map { it?.toDomain() }

    fun observeAll(): Flow<List<BpReading>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeRange(from: Instant, to: Instant): Flow<List<BpReading>> =
        dao.observeRange(from.toEpochMilli(), to.toEpochMilli()).map { list -> list.map { it.toDomain() } }

    suspend fun findById(id: UUID): BpReading? = dao.findById(id.toString())?.toDomain()

    suspend fun upsert(reading: BpReading) {
        val now = Instant.now()
        val existing = dao.findById(reading.id.toString())
        val toSave = reading.copy(
            updatedAt = now,
            createdAt = if (existing == null) now else reading.createdAt,
            // Keep any prior mirror id the caller didn't carry, so editing an
            // unchanged reading doesn't needlessly clear it and re-mirror.
            hcRecordId = reading.hcRecordId ?: existing?.hcRecordId,
        )
        if (existing == null) dao.insert(toSave.toEntity()) else dao.update(toSave.toEntity())

        // Best-effort one-way mirror to Health Connect so the reading flows into
        // Google Health / other health apps. Must never fail the local save: the
        // bridge swallows its own errors and the enabled-check is wrapped so a
        // settings read can't throw out of here. On success we stamp the returned
        // record id back so [com.silverbp.android.health.BpSyncWorker] knows this
        // one is done and won't retry it (mirrors ExerciseRepository.upsert).
        if (healthConnect != null && runCatching { healthConnectEnabled() }.getOrDefault(false)) {
            val hcId = healthConnect.write(toSave)
            if (hcId != null && hcId != toSave.hcRecordId) {
                dao.update(toSave.copy(hcRecordId = hcId, updatedAt = Instant.now()).toEntity())
            }
        }
    }

    suspend fun delete(id: UUID) = dao.delete(id.toString())

    suspend fun count(): Int = dao.count()
}
