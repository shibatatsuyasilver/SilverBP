package com.silverbp.android.sync

import com.silverbp.android.core.db.CoachPlanDao
import com.silverbp.android.core.db.CoachPlanEntity
import com.silverbp.android.core.db.CoachTaskEntity
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.transport.SyncRecordCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trip coverage for the skip/move fields added to coach_task sync (tags 10/11). */
class CoachTaskSyncMapperTest {

    private val hlc = Hlc.of(1_730_000_001_000L, 0, 0xABCDL)

    private fun planFixture(id: String = "plan-001") = CoachPlanEntity(
        id = id,
        weekStart = 1_730_000_000_000L,
        generatedAt = 1_730_000_000_000L,
        ruleVersion = 1,
        phaseRaw = "baseline",
        goalsJson = "{}",
        hlcUpdatedAt = "0".repeat(32),
    )

    private fun taskFixture(id: String = "task-001") = CoachTaskEntity(
        id = id,
        planId = "plan-001",
        dayOffset = 1,
        moduleRaw = "exercise",
        title = "快走 30 分鐘",
        targetValue = 30.0,
        targetUnit = "min",
        intensityRaw = "moderate",
        safetyHold = false,
        completedAt = null,
        skipped = true,
        movedDayOffset = 3,
        hlcUpdatedAt = "0".repeat(32),
    )

    @Test
    fun cbor_round_trip_preserves_skip_and_move() {
        val mapper = CoachTaskSyncMapper(FakeCoachPlanDao())
        val original = mapper.encode(taskFixture(), hlc)
        val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(SyncValue.Bool(true), decoded.payload[10])
        assertEquals(SyncValue.Int64(3L), decoded.payload[11])
    }

    @Test
    fun null_moved_day_offset_emits_null() {
        val mapper = CoachTaskSyncMapper(FakeCoachPlanDao())
        val rec = mapper.encode(taskFixture().copy(movedDayOffset = null), hlc)
        assertEquals(SyncValue.Null, rec.payload[11])
    }

    @Test
    fun apply_preserves_skip_and_move() = runTest {
        val dao = FakeCoachPlanDao().apply { seedPlan(planFixture()) }
        val mapper = CoachTaskSyncMapper(dao)
        val decoded = SyncRecordCodec.decode(SyncRecordCodec.encode(mapper.encode(taskFixture(), hlc)))
        mapper.apply(decoded)

        val stored = dao.inserted.single()
        assertEquals(taskFixture().copy(hlcUpdatedAt = hlc.packed), stored)
        assertTrue("skipped must survive", stored.skipped)
        assertEquals(3, stored.movedDayOffset)
    }

    @Test
    fun apply_without_tags_falls_back_to_defaults() = runTest {
        // Simulate a record from a peer that predates tags 10/11.
        val dao = FakeCoachPlanDao().apply { seedPlan(planFixture()) }
        val mapper = CoachTaskSyncMapper(dao)
        val full = mapper.encode(taskFixture(), hlc)
        val legacy = full.copy(payload = full.payload - 10 - 11)
        mapper.apply(SyncRecordCodec.decode(SyncRecordCodec.encode(legacy)))

        val stored = dao.inserted.single()
        assertFalse("missing tag 10 defaults to not-skipped", stored.skipped)
        assertNull("missing tag 11 defaults to no move", stored.movedDayOffset)
    }

    // --- in-memory fake (no Room / Robolectric required) ---

    private class FakeCoachPlanDao : CoachPlanDao {
        private val plans = mutableMapOf<String, CoachPlanEntity>()
        val inserted = mutableListOf<CoachTaskEntity>()
        fun seedPlan(plan: CoachPlanEntity) { plans[plan.id] = plan }

        override suspend fun insertPlan(plan: CoachPlanEntity) { plans[plan.id] = plan }
        override suspend fun insertTasks(tasks: List<CoachTaskEntity>) { inserted += tasks }
        override suspend fun findPlanById(id: String): CoachPlanEntity? = plans[id]

        override fun observeCurrentPlan(nowMillis: Long): Flow<CoachPlanEntity?> = error("unused")
        override suspend fun currentPlan(nowMillis: Long) = error("unused")
        override suspend fun recentPlans(limit: Int) = error("unused")
        override fun observeTasksForPlan(planId: String): Flow<List<CoachTaskEntity>> = error("unused")
        override suspend fun tasksForPlan(planId: String) = error("unused")
        override suspend fun setCompleted(id: String, ts: Long?) = error("unused")
        override suspend fun moveTask(id: String, newDay: Int?) = error("unused")
        override suspend fun markSkipped(id: String, skipped: Boolean) = error("unused")
        override suspend fun adherenceForPlan(planId: String) = error("unused")
        override suspend fun listAllPlans() = error("unused")
        override suspend fun listAllTasks() = error("unused")
        override suspend fun findTaskById(id: String) = error("unused")
    }
}
