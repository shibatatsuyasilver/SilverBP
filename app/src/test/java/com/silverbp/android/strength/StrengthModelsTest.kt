package com.silverbp.android.strength

import com.silverbp.android.core.db.toDomain
import com.silverbp.android.core.db.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the strength enum `raw` round-trips and the catalog/set mapper
 * entity↔domain round-trips. Mirrors the JVM-only test style used elsewhere
 * (no Android dependencies → runs on the unit-test source set).
 */
class StrengthModelsTest {

    @Test
    fun `BodyPart raw round-trips`() {
        for (bp in BodyPart.entries) {
            assertEquals(bp, BodyPart.fromRaw(bp.raw))
        }
    }

    @Test
    fun `DifficultyFeedback raw round-trips`() {
        for (d in DifficultyFeedback.entries) {
            assertEquals(d, DifficultyFeedback.fromRaw(d.raw))
        }
    }

    @Test
    fun `ExerciseCatalogItem entity-domain round-trip preserves muscle groups`() {
        val item = ExerciseCatalogItem(
            id = "st_barbell_bench_press",
            name = "槓鈴臥推",
            bodyPart = BodyPart.UpperBody,
            muscleGroups = listOf("胸部", "三頭肌", "肩膀"),
            description = "平躺槓鈴推舉。",
            isFavorite = true,
        )

        val back = item.toEntity(createdAt = 1L, updatedAt = 2L).toDomain()

        assertEquals(item, back)
        assertEquals(listOf("胸部", "三頭肌", "肩膀"), back.muscleGroups)
    }

    @Test
    fun `SetLog entity-domain round-trip preserves null weight`() {
        val set = SetLog(
            id = "set1",
            exerciseId = "st_push_up",
            setNumber = 1,
            reps = 12,
            weightKg = null,
            isCompleted = true,
            skipped = false,
            notes = "暖身組",
        )

        val back = set.toEntity(workoutSessionId = "w1", createdAt = 9L).toDomain()

        assertEquals(set, back)
        assertNull(back.weightKg)
    }

    @Test
    fun `session toDomain reassembles items in set-number order using catalog`() {
        val bench = ExerciseCatalogItem(
            id = "st_barbell_bench_press", name = "槓鈴臥推", bodyPart = BodyPart.UpperBody,
            muscleGroups = listOf("胸部"), description = "",
        )
        val session = StrengthWorkoutSession(
            id = "w1", startedAt = 100L, endedAt = 200L, note = "n",
            difficulty = DifficultyFeedback.JustRight,
        )
        val sessionEntity = session.toEntity(createdAt = 1L, updatedAt = 1L)
        // Insert out of order to prove sorting by setNumber.
        val setEntities = listOf(
            SetLog("s2", bench.id, setNumber = 2, reps = 8, weightKg = 60.0)
                .toEntity(workoutSessionId = "w1", createdAt = 1L),
            SetLog("s1", bench.id, setNumber = 1, reps = 10, weightKg = 50.0)
                .toEntity(workoutSessionId = "w1", createdAt = 1L),
        )

        val domain = sessionEntity.toDomain(setEntities, mapOf(bench.id to bench))

        assertEquals(DifficultyFeedback.JustRight, domain.difficulty)
        assertEquals(1, domain.items.size)
        val (item, sets) = domain.items.first()
        assertEquals(bench, item)
        assertEquals(listOf(1, 2), sets.map { it.setNumber })
    }

    @Test
    fun `seed list is non-empty and spans all body parts with unique ids`() {
        val items = StrengthSeed.items
        assertEquals(items.size, items.map { it.id }.toSet().size) // unique ids
        for (bp in BodyPart.entries) {
            assert(items.any { it.bodyPart == bp }) { "no seed item for $bp" }
        }
    }
}
