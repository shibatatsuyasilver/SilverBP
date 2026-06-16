package com.silverbp.android.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * Validates the CONTENTS of the bundled bulk-nutrition asset
 * (`assets/nutrition/foods.v1.json.gz`) — not just that the generator ran. The
 * single most important assertion is the sodium invariant: every row must carry
 * a real sodium value, because the Coach diet rollup sums `sodiumMg` per day.
 */
class BulkNutritionStoreTest {

    private val store = BulkNutritionStore.fromGzip(FileInputStream(assetFile()))

    @Test fun `loads the expected order of magnitude of foods`() {
        // ~2,118 TFDA foods today; guard a sane band, not an exact count.
        assertTrue("unexpected record count ${store.recordCount()}",
            store.recordCount() in 1500..4000)
    }

    @Test fun `every record has a finite non-negative sodium and kcal`() {
        val bad = store.allRecords().filter {
            !it.sodiumMgPer100g.isFinite() || it.sodiumMgPer100g < 0.0 ||
                !it.kcalPer100g.isFinite() || it.kcalPer100g < 0.0
        }
        assertTrue("records with bad sodium/kcal: ${bad.take(5).map { it.canonicalName }}", bad.isEmpty())
    }

    @Test fun `no bulk record collides with a curated canonical or alias`() {
        val curatedKeys = NutritionDatabase.records
            .flatMap { it.matchKeys }
            .map { NutritionDatabase.normalize(it) }
            .toSet()
        val collisions = store.allRecords()
            .filter { NutritionDatabase.normalize(it.canonicalName) in curatedKeys }
            .map { it.canonicalName }
        assertEquals("bulk canonicals colliding with curated: $collisions", emptyList<String>(), collisions)
    }

    @Test fun `no bulk alias is an over-broad bare-noun category word`() {
        val blocklist = setOf(
            "vegetable", "vegetables", "greens", "egg", "eggs", "rice", "tofu",
            "meat", "soup", "fish", "fruit", "juice", "coffee", "bread", "soda",
        )
        val offenders = store.allRecords().flatMap { rec ->
            rec.aliases.filter { NutritionDatabase.normalize(it) in blocklist }
                .map { "${rec.canonicalName}:$it" }
        }
        assertEquals("bulk records with bare-noun aliases: $offenders", emptyList<String>(), offenders)
    }
}

/** Resolve the bundled asset whether tests run from the module dir or repo root. */
internal fun assetFile(): File {
    val candidates = listOf(
        "src/main/assets/nutrition/foods.v1.json.gz",
        "app/src/main/assets/nutrition/foods.v1.json.gz",
    )
    return candidates.map { File(it) }.firstOrNull { it.exists() }
        ?: error("bundled nutrition asset not found; cwd=${File(".").absolutePath}")
}
