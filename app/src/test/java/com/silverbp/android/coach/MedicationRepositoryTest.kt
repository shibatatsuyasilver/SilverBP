package com.silverbp.android.coach

import com.silverbp.android.core.db.MedicationDao
import com.silverbp.android.core.db.MedicationEntity
import com.silverbp.android.core.db.MedicationKind
import com.silverbp.android.core.db.MedicationScheduleDao
import com.silverbp.android.core.db.MedicationScheduleEntity
import com.silverbp.android.core.db.TombstoneEntity
import com.silverbp.android.sync.LocalSyncWriter
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicationRepositoryTest {
    private val ownerId = "owner-member"
    private val memberId = "member-a"
    private val otherMemberId = "member-b"

    @Test
    fun save_new_medication_sets_current_member_and_stamps_hlc() = runTest {
        val medicationDao = FakeMedicationDao()
        val scheduleDao = FakeScheduleDao(medicationDao)
        val hlc = Hlc.of(1_730_000_000_000L, 0, 0xA1L).packed
        val repo = repo(medicationDao, scheduleDao, sync = FakeLocalSyncWriter(hlc))

        repo.saveForCurrentMember(
            medication = medication(id = "med-1", memberId = ""),
            scheduleRows = listOf(schedule(id = "sched-1", medicationId = "ignored-med")),
        )

        assertEquals(memberId, medicationDao.rows["med-1"]!!.memberId)
        assertEquals(hlc, medicationDao.rows["med-1"]!!.hlcUpdatedAt)
        assertEquals("med-1", scheduleDao.rows["sched-1"]!!.medicationId)
        assertEquals(hlc, scheduleDao.rows["sched-1"]!!.hlcUpdatedAt)
    }

    @Test
    fun observe_for_member_backfills_blank_member_ids_to_owner_and_scopes_results() = runTest {
        val medicationDao = FakeMedicationDao().apply {
            upsert(medication(id = "legacy", name = "Legacy", memberId = ""))
            upsert(medication(id = "owner-med", name = "Owner", memberId = ownerId))
            upsert(medication(id = "other-med", name = "Other", memberId = otherMemberId))
        }
        val scheduleDao = FakeScheduleDao(medicationDao).apply {
            upsert(schedule(id = "legacy-schedule", medicationId = "legacy"))
            upsert(schedule(id = "other-schedule", medicationId = "other-med"))
        }
        val hlc = Hlc.of(1_730_000_000_001L, 0, 0xA2L).packed
        val repo = repo(
            medicationDao = medicationDao,
            scheduleDao = scheduleDao,
            current = ownerId,
            sync = FakeLocalSyncWriter(hlc),
        )

        val ownerMeds = repo.observeForMember(ownerId).first()

        assertEquals(setOf("legacy", "owner-med"), ownerMeds.map { it.id }.toSet())
        assertEquals(ownerId, medicationDao.rows["legacy"]!!.memberId)
        assertEquals(hlc, medicationDao.rows["legacy"]!!.hlcUpdatedAt)
        assertFalse(ownerMeds.any { it.id == "other-med" })
        assertEquals(
            setOf("legacy-schedule"),
            repo.observeSchedulesForMember(ownerId).first().map { it.id }.toSet(),
        )
    }

    @Test
    fun save_deletes_removed_schedule_with_tombstone_and_stamps_remaining_rows() = runTest {
        val medicationDao = FakeMedicationDao().apply {
            upsert(medication(id = "med-1", memberId = memberId, hlc = "old-med-hlc"))
        }
        val scheduleDao = FakeScheduleDao(medicationDao).apply {
            upsert(schedule(id = "keep", medicationId = "med-1", hlc = "old-keep-hlc"))
            upsert(schedule(id = "remove", medicationId = "med-1", hlc = "old-remove-hlc"))
        }
        val tombstones = mutableListOf<TombstoneEntity>()
        val hlc = Hlc.of(1_730_000_000_002L, 0, 0xA3L).packed
        val repo = repo(
            medicationDao = medicationDao,
            scheduleDao = scheduleDao,
            sync = FakeLocalSyncWriter(hlc),
            tombstones = tombstones,
        )

        repo.saveForCurrentMember(
            medication = medication(id = "med-1", name = "Updated", memberId = memberId),
            scheduleRows = listOf(schedule(id = "keep", medicationId = "med-1", hour = 9)),
        )

        assertEquals(hlc, medicationDao.rows["med-1"]!!.hlcUpdatedAt)
        assertEquals(hlc, scheduleDao.rows["keep"]!!.hlcUpdatedAt)
        assertNull(scheduleDao.rows["remove"])
        assertEquals(
            setOf(SyncEntityType.MEDICATION_SCHEDULE.tableName to "remove"),
            tombstones.map { it.entityType to it.pk }.toSet(),
        )
        assertTrue(tombstones.all { it.hlc == hlc })
    }

    @Test
    fun delete_writes_medication_and_schedule_tombstones_in_one_transaction() = runTest {
        val medicationDao = FakeMedicationDao().apply {
            upsert(medication(id = "med-1", memberId = memberId))
        }
        val scheduleDao = FakeScheduleDao(medicationDao).apply {
            upsert(schedule(id = "sched-1", medicationId = "med-1"))
            upsert(schedule(id = "sched-2", medicationId = "med-1"))
        }
        val tombstones = mutableListOf<TombstoneEntity>()
        var transactions = 0
        val hlc = Hlc.of(1_730_000_000_003L, 0, 0xA4L).packed
        val repo = repo(
            medicationDao = medicationDao,
            scheduleDao = scheduleDao,
            sync = FakeLocalSyncWriter(hlc),
            tombstones = tombstones,
            inTransaction = { block ->
                transactions++
                block()
            },
        )

        repo.delete("med-1")

        assertEquals(1, transactions)
        assertNull(medicationDao.rows["med-1"])
        assertNull(scheduleDao.rows["sched-1"])
        assertNull(scheduleDao.rows["sched-2"])
        assertEquals(
            setOf(
                SyncEntityType.MEDICATION.tableName to "med-1",
                SyncEntityType.MEDICATION_SCHEDULE.tableName to "sched-1",
                SyncEntityType.MEDICATION_SCHEDULE.tableName to "sched-2",
            ),
            tombstones.map { it.entityType to it.pk }.toSet(),
        )
        assertTrue(tombstones.all { it.hlc == hlc && it.deletedAt == 123L })
    }

    private fun repo(
        medicationDao: FakeMedicationDao,
        scheduleDao: FakeScheduleDao,
        current: String = memberId,
        sync: LocalSyncWriter? = null,
        tombstones: MutableList<TombstoneEntity> = mutableListOf(),
        inTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
    ) = MedicationRepository(
        medications = medicationDao,
        schedules = scheduleDao,
        currentMemberId = { current },
        ownerMemberId = { ownerId },
        localSync = sync,
        writeTombstone = { tombstones += it },
        inTransaction = inTransaction,
        nowMs = { 123L },
    )

    private fun medication(
        id: String,
        name: String = "Medication $id",
        memberId: String = this.memberId,
        hlc: String = "0",
    ) = MedicationEntity(
        id = id,
        name = name,
        dose = "10 mg",
        kind = MedicationKind.MEDICATION,
        hlcUpdatedAt = hlc,
        memberId = memberId,
    )

    private fun schedule(
        id: String,
        medicationId: String,
        hour: Int = 8,
        hlc: String = "0",
    ) = MedicationScheduleEntity(
        id = id,
        medicationId = medicationId,
        daysOfWeekMask = DayOfWeekMask.ALL,
        hour = hour,
        minute = 0,
        enabled = true,
        hlcUpdatedAt = hlc,
    )

    private class FakeMedicationDao : MedicationDao {
        val rows = linkedMapOf<String, MedicationEntity>()

        override fun observeAll(): Flow<List<MedicationEntity>> =
            flowOf(rows.values.sortedBy { it.name })

        override fun observeByKind(kind: String): Flow<List<MedicationEntity>> =
            flowOf(rows.values.filter { it.kind == kind }.sortedBy { it.name })

        override fun observeForMember(memberId: String): Flow<List<MedicationEntity>> =
            flowOf(rows.values.filter { it.memberId == memberId }.sortedBy { it.name })

        override suspend fun findById(id: String): MedicationEntity? = rows[id]

        override suspend fun countBlankMemberIds(): Int =
            rows.values.count { it.memberId.isBlank() }

        override suspend fun backfillBlankMemberIds(ownerId: String, hlc: String?): Int {
            var count = 0
            for ((id, row) in rows.toList()) {
                if (row.memberId.isBlank()) {
                    rows[id] = row.copy(
                        memberId = ownerId,
                        hlcUpdatedAt = hlc ?: row.hlcUpdatedAt,
                    )
                    count++
                }
            }
            return count
        }

        override suspend fun upsert(m: MedicationEntity) {
            rows[m.id] = m
        }

        override suspend fun delete(id: String) {
            rows.remove(id)
        }
    }

    private class FakeScheduleDao(
        private val medications: FakeMedicationDao,
    ) : MedicationScheduleDao {
        val rows = linkedMapOf<String, MedicationScheduleEntity>()

        override fun observeAll(): Flow<List<MedicationScheduleEntity>> =
            flowOf(sorted(rows.values))

        override fun observeForMedication(medicationId: String): Flow<List<MedicationScheduleEntity>> =
            flowOf(sorted(rows.values.filter { it.medicationId == medicationId }))

        override fun observeForMember(memberId: String): Flow<List<MedicationScheduleEntity>> =
            flowOf(
                sorted(
                    rows.values.filter { row ->
                        medications.rows[row.medicationId]?.memberId == memberId
                    },
                ),
            )

        override suspend fun all(): List<MedicationScheduleEntity> =
            sorted(rows.values)

        override suspend fun allEnabled(): List<MedicationScheduleEntity> =
            sorted(rows.values.filter { it.enabled })

        override suspend fun forMedication(medicationId: String): List<MedicationScheduleEntity> =
            sorted(rows.values.filter { it.medicationId == medicationId })

        override suspend fun findById(id: String): MedicationScheduleEntity? = rows[id]

        override suspend fun upsert(s: MedicationScheduleEntity) {
            rows[s.id] = s
        }

        override suspend fun upsertAll(list: List<MedicationScheduleEntity>) {
            list.forEach { rows[it.id] = it }
        }

        override suspend fun deleteById(id: String) {
            rows.remove(id)
        }

        override suspend fun deleteForMedication(medicationId: String) {
            rows.entries.removeIf { it.value.medicationId == medicationId }
        }

        private fun sorted(rows: Collection<MedicationScheduleEntity>): List<MedicationScheduleEntity> =
            rows.sortedWith(compareBy({ it.medicationId }, { it.hour }, { it.minute }))
    }

    private class FakeLocalSyncWriter(vararg hlcs: String) : LocalSyncWriter {
        private val queue = ArrayDeque(hlcs.toList())

        override fun nextHlc(): String = queue.removeFirst()

        override suspend fun delete(type: SyncEntityType, pk: String) {
            error("MedicationRepository uses explicit transaction-scoped tombstones")
        }
        override suspend fun stamp(type: SyncEntityType, pk: String) {}
    }
}
