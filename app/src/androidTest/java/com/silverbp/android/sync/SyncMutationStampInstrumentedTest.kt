package com.silverbp.android.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.silverbp.android.coach.CoachRepository
import com.silverbp.android.core.db.CoachPlanEntity
import com.silverbp.android.core.db.CoachTaskEntity
import com.silverbp.android.core.db.MemberEntity
import com.silverbp.android.core.db.SilverBpDatabase
import com.silverbp.android.core.member.MemberRepository
import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.HlcClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Real-Room verification of the S2 mutation HLC-stamping fix (QA #3). A local
 * field mutation that isn't a full upsert — archiving a member, completing a
 * coach task — must bump the row's `hlcUpdatedAt`, otherwise the source's
 * incremental `recordsSince` skips it and the edit never reaches a paired
 * device (the LWW gate also rejects an equal-HLC inbound copy).
 *
 * The JVM unit tests stub `LocalSyncWriter.stamp` as a no-op, so only an
 * instrumented test against real Room proves the row's HLC actually changes.
 */
@RunWith(AndroidJUnit4::class)
class SyncMutationStampInstrumentedTest {

    private lateinit var db: SilverBpDatabase
    private lateinit var writer: RoomLocalSyncWriter

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SilverBpDatabase::class.java).build()
        writer = RoomLocalSyncWriter(db.localSyncMutationDao(), HlcClock(nodeId = 0x1234L))
    }

    @After fun tearDown() = db.close()

    @Test fun archiving_a_member_bumps_its_hlc_so_the_archive_can_sync() = runBlocking {
        val id = UUID.randomUUID()
        db.memberDao().upsert(member(id.toString()))
        assertEquals("pre-sync sentinel", "0", db.memberDao().findById(id.toString())!!.hlcUpdatedAt)

        MemberRepository(db.memberDao(), writer).archive(id)

        val row = db.memberDao().findById(id.toString())!!
        assertTrue("member should be archived", row.archived)
        assertEquals("HLC must be a 32-char packed string", Hlc.HEX_LEN, row.hlcUpdatedAt.length)
        assertNotEquals("HLC must move off the pre-sync sentinel", "0".repeat(Hlc.HEX_LEN), row.hlcUpdatedAt)
    }

    @Test fun completing_a_coach_task_bumps_its_hlc_so_the_completion_can_sync() = runBlocking {
        val plan = CoachPlanEntity(
            id = "plan-1", weekStart = 0L, generatedAt = 0L,
            ruleVersion = 1, phaseRaw = "hold", goalsJson = "[]",
        )
        val task = CoachTaskEntity(
            id = "task-1", planId = "plan-1", dayOffset = 0, moduleRaw = "exercise",
            title = "Walk", targetValue = null, targetUnit = null, intensityRaw = "light",
            safetyHold = false, completedAt = null,
        )
        db.coachPlanDao().insertPlanWithTasks(plan, listOf(task))
        assertEquals("pre-sync sentinel", "0", taskById("task-1").hlcUpdatedAt)

        coachRepo().setTaskCompleted("task-1", completedAtMillis = 123L)

        val row = taskById("task-1")
        assertEquals("completion is recorded", 123L, row.completedAt)
        assertEquals(Hlc.HEX_LEN, row.hlcUpdatedAt.length)
        assertNotEquals("0".repeat(Hlc.HEX_LEN), row.hlcUpdatedAt)
    }

    private suspend fun taskById(id: String): CoachTaskEntity =
        db.coachPlanDao().tasksForPlan("plan-1").first { it.id == id }

    private fun coachRepo() = CoachRepository(
        plans = db.coachPlanDao(),
        sleeps = db.sleepDao(),
        diets = db.dietDao(),
        doses = db.medicationDoseDao(),
        medicationSchedules = db.medicationScheduleDao(),
        medications = db.medicationDao(),
        localSync = writer,
    )

    private fun member(id: String) = MemberEntity(
        id = id,
        displayName = "Tester",
        isOwner = false,
        birthYear = 1990,
        hasDiabetes = false,
        hasCKD = false,
        hasASCVD = false,
        guideline = "tw2022",
        colorIndex = 0,
        sortOrder = 0,
        archived = false,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
