package com.silverbp.android.sync

import com.silverbp.android.core.db.WeightDao
import com.silverbp.android.core.db.WeightLogEntity
import com.silverbp.android.sync.engine.Hlc
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WeightReadingHcUpdateTest {

    private fun fixture(id: String = "weight-001") = WeightLogEntity(
        id = id,
        memberId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        valueKg = 72.4,
        displayUnit = "kg",
        timestamp = 1_730_000_000_000L,
        source = "manual",
        confidence = 0.92,
        note = "morning",
        photoFilename = null,
        createdAt = 1_730_000_000_500L,
        updatedAt = 1_730_000_001_000L,
        hlcUpdatedAt = "0".repeat(32),
        hcRecordId = null,
    )

    @Test
    fun health_connect_retry_hc_id_update_preserves_hlc() = runTest {
        val dao = FakeWeightDao()
        val hlc = Hlc.of(1_730_000_020_000L, 0, 0xABCDL).packed
        dao.upsert(fixture().copy(hlcUpdatedAt = hlc))

        dao.updateHcRecordId(fixture().id, "hc-weight-001")

        val stored = dao.findById(fixture().id)
        assertEquals("hc-weight-001", stored?.hcRecordId)
        assertEquals(hlc, stored?.hlcUpdatedAt)
    }

    private class FakeWeightDao : WeightDao {
        private val rows = mutableMapOf<String, WeightLogEntity>()
        override fun observeLatest(memberId: String): Flow<WeightLogEntity?> =
            flowOf(rows.values.filter { it.memberId == memberId }.maxByOrNull { it.timestamp })
        override fun observeAll(memberId: String): Flow<List<WeightLogEntity>> =
            flowOf(rows.values.filter { it.memberId == memberId }.sortedByDescending { it.timestamp })
        override fun observeRange(memberId: String, from: Long, to: Long): Flow<List<WeightLogEntity>> =
            flowOf(rows.values.filter { it.memberId == memberId && it.timestamp in from..to }.sortedBy { it.timestamp })
        override suspend fun count(memberId: String): Int = rows.values.count { it.memberId == memberId }
        override suspend fun findById(id: String): WeightLogEntity? = rows[id]
        override suspend fun getAll(): List<WeightLogEntity> = rows.values.sortedBy { it.timestamp }
        override suspend fun upsert(r: WeightLogEntity) { rows[r.id] = r }
        override suspend fun updateHcRecordId(id: String, hcId: String) {
            rows[id]?.let { rows[id] = it.copy(hcRecordId = hcId) }
        }
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun findUnmirrored(ownerId: String): List<WeightLogEntity> =
            rows.values.filter { it.hcRecordId == null && it.memberId == ownerId }
    }
}
