package com.silverbp.android.sync

import com.silverbp.android.core.db.MedicationDoseDao
import com.silverbp.android.core.db.MedicationDoseEntity
import com.silverbp.android.core.db.MedicationMemberRow
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.transport.SyncRecordCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MedicationDoseSyncMapperTest {
    private val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)

    @Test
    fun encode_appends_minute_and_schedule_id() {
        val mapper = MedicationDoseSyncMapper(FakeDoseDao())
        val rec = mapper.encode(fixture(scheduledMinute = 30, scheduleId = "sched-30"), hlc)

        assertEquals(SyncValue.Int64(30), rec.payload[6])
        assertEquals(SyncValue.Text("sched-30"), rec.payload[7])
    }

    @Test
    fun apply_does_not_merge_two_same_hour_different_minute_doses() = runTest {
        val dao = FakeDoseDao().apply {
            upsert(fixture(id = "dose-1000", scheduledHour = 10, scheduledMinute = 0, scheduleId = "sched-00"))
        }
        val mapper = MedicationDoseSyncMapper(dao)
        val incoming = fixture(
            id = "dose-1030",
            scheduledHour = 10,
            scheduledMinute = 30,
            scheduleId = "sched-30",
            taken = true,
        )

        mapper.apply(SyncRecordCodec.decode(SyncRecordCodec.encode(mapper.encode(incoming, hlc))))

        assertEquals(2, dao.rows.size)
        assertNotEquals(dao.rows["dose-1000"]!!.id, dao.rows["dose-1030"]!!.id)
    }

    @Test
    fun apply_matches_legacy_android_deterministic_id_when_schedule_id_arrives() = runTest {
        val dayStart = 1_730_000_000_000L
        val legacyId = "med-$dayStart-sched-00"
        val dao = FakeDoseDao().apply {
            upsert(
                fixture(
                    id = legacyId,
                    dayStart = dayStart,
                    scheduledHour = 10,
                    scheduledMinute = 0,
                    scheduleId = null,
                    taken = false,
                ),
            )
        }
        val mapper = MedicationDoseSyncMapper(dao)
        val incoming = fixture(
            id = "peer-pk",
            dayStart = dayStart,
            scheduledHour = 10,
            scheduledMinute = 0,
            scheduleId = "sched-00",
            taken = true,
        )

        mapper.apply(SyncRecordCodec.decode(SyncRecordCodec.encode(mapper.encode(incoming, hlc))))

        assertEquals(1, dao.rows.size)
        assertEquals(true, dao.rows[legacyId]!!.taken)
        assertEquals("sched-00", dao.rows[legacyId]!!.scheduleId)
    }

    private fun fixture(
        id: String = "dose-1",
        dayStart: Long = 1_730_000_000_000L,
        medicationId: String = "med-1",
        scheduledHour: Int = 10,
        scheduledMinute: Int = 0,
        scheduleId: String? = "sched-00",
        taken: Boolean = false,
    ) = MedicationDoseEntity(
        id = id,
        dayStart = dayStart,
        medicationId = medicationId,
        scheduledHour = scheduledHour,
        scheduledMinute = scheduledMinute,
        scheduleId = scheduleId,
        taken = taken,
        updatedAt = 1_730_000_001_000L,
        hlcUpdatedAt = "0".repeat(32),
    )

    private class FakeDoseDao : MedicationDoseDao {
        val rows = linkedMapOf<String, MedicationDoseEntity>()

        override suspend fun upsert(dose: MedicationDoseEntity) {
            rows[dose.id] = dose
        }

        override fun observeForDay(dayStart: Long): Flow<List<MedicationDoseEntity>> =
            flowOf(rows.values.filter { it.dayStart == dayStart })

        override suspend fun forDay(dayStart: Long): List<MedicationDoseEntity> =
            rows.values.filter { it.dayStart == dayStart }

        override fun observeForRange(from: Long, to: Long): Flow<List<MedicationDoseEntity>> =
            flowOf(rows.values.filter { it.dayStart >= from && it.dayStart < to })

        override suspend fun countTakenInRange(from: Long, to: Long): Int =
            rows.values.count { it.dayStart >= from && it.dayStart < to && it.taken }

        override suspend fun all(): List<MedicationDoseEntity> = rows.values.toList()

        override suspend fun count(): Int = rows.size

        override suspend fun findById(id: String): MedicationDoseEntity? = rows[id]

        override suspend fun findBySchedule(
            dayStart: Long,
            medicationId: String,
            scheduleId: String,
        ): MedicationDoseEntity? =
            rows.values.firstOrNull {
                it.dayStart == dayStart && it.medicationId == medicationId && it.scheduleId == scheduleId
            }

        override suspend fun findByContent(
            dayStart: Long,
            medicationId: String,
            scheduledHour: Int,
            scheduledMinute: Int,
        ): MedicationDoseEntity? =
            rows.values.firstOrNull {
                it.dayStart == dayStart &&
                    it.medicationId == medicationId &&
                    it.scheduledHour == scheduledHour &&
                    it.scheduledMinute == scheduledMinute
            }

        override suspend fun memberForMedication(medicationId: String): MedicationMemberRow? = null
    }
}
