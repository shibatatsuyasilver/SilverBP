package com.silverbp.android.core

import com.silverbp.android.core.db.BpDao
import com.silverbp.android.core.db.BpReadingEntity
import com.silverbp.android.core.db.MemberDao
import com.silverbp.android.core.db.MemberEntity
import com.silverbp.android.core.member.MemberRepository
import com.silverbp.android.sync.LocalSyncWriter
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Pins the §3-5 invariant that a non-owner BP row ALWAYS has hcRecordId == null
 * (adversarial finding 3): re-attributing a previously-mirrored owner reading to
 * a family member must drop the inherited mirror id, otherwise the non-owner row
 * would point at the owner's Health Connect record (a cross-member privacy bug).
 */
class BpRepositoryTest {

    private val ownerId = "owner-uuid"
    private val memberId = "member-uuid"

    private fun reading(id: UUID, member: String, hcRecordId: String?) = BpReading(
        id = id,
        systolic = 120,
        diastolic = 80,
        pulse = 70,
        timestamp = Instant.ofEpochMilli(1_730_000_000_000L),
        memberId = member,
        hcRecordId = hcRecordId,
    )

    @Test
    fun reattributing_mirrored_owner_reading_to_member_clears_hcRecordId() = runTest {
        val bpDao = FakeBpDao()
        val repo = BpRepository(bpDao, ownerRepo())
        val id = UUID.randomUUID()

        // 1. Owner reading already mirrored: row carries hcRecordId = "abc".
        bpDao.seed(reading(id, ownerId, "abc").toEntityWith(ownerId, "abc"))

        // 2. Edit reattributes it to a non-owner member; the rebuilt reading
        //    carries hcRecordId = null (ConfirmReadingViewModel doesn't set it).
        repo.upsert(reading(id, memberId, hcRecordId = null))

        // 3. The persisted non-owner row must NOT have recovered "abc" off the
        //    existing row.
        val stored = bpDao.findById(id.toString())!!
        assertEquals(memberId, stored.memberId)
        assertNull("non-owner row must always have hcRecordId == null (§3-5)", stored.hcRecordId)
    }

    @Test
    fun editing_owner_reading_keeps_existing_hcRecordId() = runTest {
        val bpDao = FakeBpDao()
        val repo = BpRepository(bpDao, ownerRepo())
        val id = UUID.randomUUID()
        bpDao.seed(reading(id, ownerId, "abc").toEntityWith(ownerId, "abc"))

        // Editing an owner reading without re-supplying the mirror id keeps it.
        repo.upsert(reading(id, ownerId, hcRecordId = null))

        assertEquals("abc", bpDao.findById(id.toString())!!.hcRecordId)
    }

    @Test
    fun new_non_owner_reading_is_never_mirrored() = runTest {
        val bpDao = FakeBpDao()
        val repo = BpRepository(bpDao, ownerRepo())
        val id = UUID.randomUUID()

        repo.upsert(reading(id, memberId, hcRecordId = null))

        assertNull(bpDao.findById(id.toString())!!.hcRecordId)
    }

    @Test
    fun local_upsert_stamps_hlc_from_sync_clock() = runTest {
        val bpDao = FakeBpDao()
        val localSync = FakeLocalSyncWriter(Hlc.of(1_730_000_010_000L, 0, 0xABCDL).packed)
        val repo = BpRepository(bpDao, ownerRepo(), localSync = localSync)
        val id = UUID.randomUUID()

        repo.upsert(reading(id, ownerId, hcRecordId = null))

        assertEquals(localSync.hlc, bpDao.findById(id.toString())!!.hlcUpdatedAt)
    }

    @Test
    fun local_delete_delegates_to_tombstone_writer() = runTest {
        val bpDao = FakeBpDao()
        val localSync = FakeLocalSyncWriter(Hlc.of(1_730_000_010_000L, 0, 0xABCDL).packed)
        val repo = BpRepository(bpDao, ownerRepo(), localSync = localSync)
        val id = UUID.randomUUID()

        repo.delete(id)

        assertEquals(SyncEntityType.BP_READING to id.toString(), localSync.deleted.single())
    }

    private fun BpReading.toEntityWith(member: String, hc: String?) = BpReadingEntity(
        id = id.toString(),
        systolic = systolic,
        diastolic = diastolic,
        pulse = pulse,
        timestamp = timestamp.toEpochMilli(),
        arm = arm.raw,
        posture = posture.raw,
        partOfDay = partOfDay.raw,
        beforeMedication = beforeMedication,
        photoFilename = photoFilename,
        confidence = confidence,
        source = source.raw,
        note = note,
        irregularHeartbeat = irregularHeartbeat,
        medicationId = medicationId?.toString(),
        memberId = member,
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
        hcRecordId = hc,
    )

    private fun ownerRepo(): MemberRepository {
        val dao = FakeMemberDao()
        // Seed the owner synchronously so ownerId() resolves to ownerId.
        dao.seedOwner(ownerId)
        return MemberRepository(dao)
    }

    // --- in-memory fakes ---

    private class FakeBpDao : BpDao {
        private val rows = mutableMapOf<String, BpReadingEntity>()
        fun seed(e: BpReadingEntity) { rows[e.id] = e }
        override fun observeLatest(): Flow<BpReadingEntity?> = flowOf(rows.values.maxByOrNull { it.timestamp })
        override fun observeAll(): Flow<List<BpReadingEntity>> = flowOf(rows.values.toList())
        override fun observeRange(from: Long, to: Long): Flow<List<BpReadingEntity>> = flowOf(emptyList())
        override fun observeLatest(memberId: String): Flow<BpReadingEntity?> =
            flowOf(rows.values.filter { it.memberId == memberId }.maxByOrNull { it.timestamp })
        override fun observeAll(memberId: String): Flow<List<BpReadingEntity>> =
            flowOf(rows.values.filter { it.memberId == memberId })
        override fun observeRange(memberId: String, from: Long, to: Long): Flow<List<BpReadingEntity>> = flowOf(emptyList())
        override suspend fun count(memberId: String): Int = rows.values.count { it.memberId == memberId }
        override suspend fun findById(id: String): BpReadingEntity? = rows[id]
        override suspend fun insert(r: BpReadingEntity) { rows[r.id] = r }
        override suspend fun update(r: BpReadingEntity) { rows[r.id] = r }
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun count(): Int = rows.size
        override suspend fun findUnmirrored(ownerId: String): List<BpReadingEntity> =
            rows.values.filter { it.hcRecordId == null && it.memberId == ownerId }
    }

    private class FakeMemberDao : MemberDao {
        private val rows = mutableMapOf<String, MemberEntity>()
        fun seedOwner(id: String) {
            rows[id] = MemberEntity(
                id = id, displayName = "", isOwner = true, birthYear = null,
                hasDiabetes = false, hasCKD = false, hasASCVD = false,
                guideline = "taiwan2022", colorIndex = 0, sortOrder = 0,
                archived = false, createdAt = 1L, updatedAt = 1L,
            )
        }
        override fun observeActive(): Flow<List<MemberEntity>> =
            flowOf(rows.values.filter { !it.archived }.sortedBy { it.sortOrder })
        override suspend fun getAll(): List<MemberEntity> = rows.values.sortedBy { it.sortOrder }
        override suspend fun getOwner(): MemberEntity? = rows.values.firstOrNull { it.isOwner }
        override suspend fun findById(id: String): MemberEntity? = rows[id]
        override suspend fun upsert(m: MemberEntity) { rows[m.id] = m }
        override suspend fun archive(id: String, now: Long) {}
        override suspend fun unarchive(id: String, now: Long) {}
        override suspend fun updateSortOrder(id: String, sortOrder: Int, now: Long) {}
        override suspend fun count(): Int = rows.size
        override suspend fun deleteById(id: String) { rows.remove(id) }
    }

    private class FakeLocalSyncWriter(val hlc: String) : LocalSyncWriter {
        val deleted = mutableListOf<Pair<SyncEntityType, String>>()
        override fun nextHlc(): String = hlc
        override suspend fun delete(type: SyncEntityType, pk: String) {
            deleted += type to pk
        }
    }
}
