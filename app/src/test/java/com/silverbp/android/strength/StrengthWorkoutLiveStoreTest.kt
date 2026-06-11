package com.silverbp.android.strength

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the [StrengthWorkoutLiveStore.start] live-session guard.
 *
 * Regression target: start() used to unconditionally replace the live state,
 * so re-entering the strength flow silently wiped every logged set of an
 * in-progress (or Finished-but-unsaved) workout.
 */
class StrengthWorkoutLiveStoreTest {

    private val bench = ExerciseCatalogItem(
        id = "st_barbell_bench_press", name = "槓鈴臥推", bodyPart = BodyPart.UpperBody,
        muscleGroups = listOf("胸部"), description = "",
    )
    private val squat = ExerciseCatalogItem(
        id = "st_squat", name = "深蹲", bodyPart = BodyPart.LowerBody,
        muscleGroups = listOf("腿部"), description = "",
    )

    private fun set(number: Int) = SetLog(
        id = "s$number", exerciseId = bench.id, setNumber = number, reps = 10, weightKg = 50.0,
    )

    @Test
    fun `start refuses while a session is Running and keeps logged sets`() {
        val store = StrengthWorkoutLiveStore()
        assertTrue(store.start(listOf(bench), startedAtMillis = 100L))
        store.logSet(bench.id, set(1))
        val before = store.flow.value!!

        assertFalse(store.start(listOf(squat), startedAtMillis = 200L))

        val after = store.flow.value!!
        assertEquals(before, after)
        assertEquals(1, after.totalSets)
        assertEquals(StrengthRunState.Running, after.runState)
    }

    @Test
    fun `start refuses while a Finished snapshot awaits the summary`() {
        val store = StrengthWorkoutLiveStore()
        assertTrue(store.start(listOf(bench), startedAtMillis = 100L))
        store.logSet(bench.id, set(1))
        store.snapshotAndFinish(endedAtMillis = 200L)

        assertFalse(store.start(listOf(squat), startedAtMillis = 300L))

        val after = store.flow.value!!
        assertEquals(StrengthRunState.Finished, after.runState)
        assertEquals(1, after.totalSets)
    }

    @Test
    fun `clear then start begins a fresh session`() {
        val store = StrengthWorkoutLiveStore()
        assertTrue(store.start(listOf(bench), startedAtMillis = 100L))
        val firstId = store.flow.value!!.id
        store.clear()

        assertTrue(store.start(listOf(squat), startedAtMillis = 200L))

        val fresh = store.flow.value!!
        assertNotEquals(firstId, fresh.id)
        assertEquals(StrengthRunState.Running, fresh.runState)
        assertEquals(listOf(squat.id), fresh.exercises.map { it.exercise.id })
        assertEquals(0, fresh.totalSets)
    }
}
