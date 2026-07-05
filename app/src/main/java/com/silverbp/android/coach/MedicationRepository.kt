package com.silverbp.android.coach

import com.silverbp.android.core.db.MedicationDao
import com.silverbp.android.core.db.MedicationEntity
import com.silverbp.android.core.db.MedicationScheduleDao
import com.silverbp.android.core.db.MedicationScheduleEntity
import com.silverbp.android.core.db.TombstoneEntity
import com.silverbp.android.sync.LocalSyncWriter
import com.silverbp.android.sync.engine.SyncEntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

data class MedicationEditData(
    val medication: MedicationEntity,
    val schedules: List<MedicationScheduleEntity>,
)

class MedicationRepository(
    private val medications: MedicationDao,
    private val schedules: MedicationScheduleDao,
    private val currentMemberId: suspend () -> String,
    private val ownerMemberId: suspend () -> String,
    private val localSync: LocalSyncWriter? = null,
    private val writeTombstone: suspend (TombstoneEntity) -> Unit = {},
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var legacyBackfillChecked = false

    fun observeForMember(memberId: String): Flow<List<MedicationEntity>> = flow {
        ensureLegacyBackfilled()
        emitAll(medications.observeForMember(memberId))
    }

    fun observeSchedulesForMember(memberId: String): Flow<List<MedicationScheduleEntity>> = flow {
        ensureLegacyBackfilled()
        emitAll(schedules.observeForMember(memberId))
    }

    suspend fun findForCurrentMember(medicationId: String): MedicationEditData? {
        ensureLegacyBackfilled()
        val memberId = currentMemberId()
        val medication = medications.findById(medicationId)
            ?.takeIf { it.memberId == memberId }
            ?: return null
        return MedicationEditData(
            medication = medication,
            schedules = schedules.forMedication(medicationId),
        )
    }

    suspend fun saveForCurrentMember(
        medication: MedicationEntity,
        scheduleRows: List<MedicationScheduleEntity>,
    ) {
        ensureLegacyBackfilled()
        val currentMember = currentMemberId()
        val existing = medications.findById(medication.id)
        if (existing != null && existing.memberId.isNotBlank() && existing.memberId != currentMember) {
            return
        }
        val existingSchedules = schedules.forMedication(medication.id)
        val existingSchedulesById = existingSchedules.associateBy { it.id }
        val hlc = localSync?.nextHlc()
        val memberId = existing?.memberId?.takeIf { it.isNotBlank() } ?: currentMember
        val medicationToSave = medication.copy(
            memberId = memberId,
            hlcUpdatedAt = hlc ?: existing?.hlcUpdatedAt ?: medication.hlcUpdatedAt,
        )
        val schedulesToSave = scheduleRows.map { row ->
            row.copy(
                medicationId = medication.id,
                hlcUpdatedAt = hlc ?: existingSchedulesById[row.id]?.hlcUpdatedAt ?: row.hlcUpdatedAt,
            )
        }
        val incomingScheduleIds = schedulesToSave.map { it.id }.toSet()
        val schedulesToDelete = existingSchedules.filter { it.id !in incomingScheduleIds }

        inTransaction {
            medications.upsert(medicationToSave)
            if (schedulesToSave.isNotEmpty()) {
                schedules.upsertAll(schedulesToSave)
            }
            deleteSchedulesWithOptionalTombstones(schedulesToDelete, hlc, nowMs())
        }
    }

    suspend fun delete(medicationId: String) {
        ensureLegacyBackfilled()
        val existing = medications.findById(medicationId) ?: return
        if (existing.memberId != currentMemberId()) return
        val existingSchedules = schedules.forMedication(medicationId)
        val hlc = localSync?.nextHlc()
        val deletedAt = nowMs()
        inTransaction {
            if (hlc != null) {
                deleteSchedulesWithOptionalTombstones(existingSchedules, hlc, deletedAt)
            }
            medications.delete(medicationId)
            if (hlc != null) {
                tombstone(SyncEntityType.MEDICATION, medicationId, hlc, deletedAt)
            }
        }
    }

    suspend fun ensureLegacyBackfilled() {
        if (legacyBackfillChecked) return
        if (medications.countBlankMemberIds() > 0) {
            medications.backfillBlankMemberIds(
                ownerId = ownerMemberId(),
                hlc = localSync?.nextHlc(),
            )
        }
        reconcileOrphans()
        legacyBackfillChecked = true
    }

    /**
     * Re-home medications whose [MedicationEntity.memberId] resolves to no member
     * row back to the local owner, so a cross-device import that stranded a
     * medication under a foreign/absent member doesn't leave it invisible to the
     * member-scoped manage screen (and thus undeletable). A genuine family
     * member's medication (its member row exists) is left untouched.
     *
     * Unlike [ensureLegacyBackfilled] this is NOT gated by the once-per-process
     * flag, so callers can force a fresh sweep right after an import even when the
     * lazy backfill has already run earlier this session.
     */
    suspend fun reconcileOrphans() {
        if (medications.countOrphanedMemberIds() > 0) {
            medications.rehomeOrphanedMedications(
                ownerId = ownerMemberId(),
                hlc = localSync?.nextHlc(),
            )
        }
    }

    private suspend fun deleteSchedulesWithOptionalTombstones(
        rows: List<MedicationScheduleEntity>,
        hlc: String?,
        deletedAt: Long,
    ) {
        for (row in rows) {
            schedules.deleteById(row.id)
            if (hlc != null) {
                tombstone(SyncEntityType.MEDICATION_SCHEDULE, row.id, hlc, deletedAt)
            }
        }
    }

    private suspend fun tombstone(
        type: SyncEntityType,
        pk: String,
        hlc: String,
        deletedAt: Long,
    ) {
        writeTombstone(
            TombstoneEntity(
                entityType = type.tableName,
                pk = pk,
                hlc = hlc,
                deletedAt = deletedAt,
            ),
        )
    }
}
