package com.silverbp.android.coach

import com.silverbp.android.core.db.toDomain
import com.silverbp.android.core.db.toTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-trip for the CoachTask domain ↔ entity mappers, pinning the v14
 * skip/move fields ([CoachTask.skipped] / [CoachTask.movedDayOffset]).
 */
class CoachTaskMapperTest {

    private fun fixture() = CoachTask(
        id = "task-001",
        planId = "plan-001",
        dayOffset = 2,
        module = LifestyleModule.Exercise,
        title = "快走 30 分鐘",
        targetValue = 30.0,
        targetUnit = "min",
        intensity = TaskIntensity.Moderate,
        safetyHold = false,
        completedAtMillis = null,
    )

    @Test
    fun default_skip_move_round_trip() {
        val task = fixture()
        val back = task.toTaskEntity().toDomain()
        assertEquals(task, back)
        assertFalse(back.skipped)
        assertNull(back.movedDayOffset)
    }

    @Test
    fun skipped_and_moved_survive_round_trip() {
        val task = fixture().copy(skipped = true, movedDayOffset = 4)
        val entity = task.toTaskEntity()
        assertEquals(true, entity.skipped)
        assertEquals(4, entity.movedDayOffset)

        val back = entity.toDomain()
        assertEquals(task, back)
        assertEquals(true, back.skipped)
        assertEquals(4, back.movedDayOffset)
    }
}
