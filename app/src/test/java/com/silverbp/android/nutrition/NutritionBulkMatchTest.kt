package com.silverbp.android.nutrition

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.FileInputStream

/**
 * Exercises [NutritionDatabase.match] with the bulk long-tail layer LOADED, to
 * prove (a) the curated layer still wins so common dishes keep their hand-tuned
 * entries (and the 滷味 false-match fix holds), and (b) the bulk layer actually
 * adds coverage for foods the curated set never had.
 */
class NutritionBulkMatchTest {

    @Before fun setUp() {
        NutritionDatabase.bulk = BulkNutritionStore.fromGzip(FileInputStream(assetFile()))
    }

    @After fun tearDown() {
        // Reset shared static state so curated-only tests aren't affected.
        NutritionDatabase.bulk = null
    }

    @Test fun `curated braised-pork fix still holds with bulk loaded`() {
        assertSame(
            curated("滷肉"),
            NutritionDatabase.match("滷味", "Braised Meat/Vegetables"),
        )
    }

    @Test fun `common dishes still resolve to the curated instance, not bulk`() {
        // Each scores 1.0 against a curated canonical/alias; curated wins ties.
        assertSame(curated("白飯"), NutritionDatabase.match("白飯", "white rice"))
        assertSame(curated("雞肉飯"), NutritionDatabase.match("雞肉飯", "chicken rice"))
        assertSame(curated("滷肉飯"), NutritionDatabase.match("滷肉飯", "braised pork rice"))
        assertSame(curated("燙青菜"), NutritionDatabase.match("青菜", "green vegetables"))
        assertSame(curated("牛肉麵"), NutritionDatabase.match("牛肉麵", "beef noodle"))
    }

    @Test fun `bulk layer adds coverage for common international foods`() {
        // Realistic single-word names the vision model might emit for foods the
        // curated TW set never had — all come from the USDA bulk layer. (We test
        // realistic short queries, not USDA's verbose "Noun, modifier, …" rows,
        // which the model never emits verbatim.)
        val foods = listOf(
            "oatmeal", "quinoa", "hummus", "lentils", "almonds",
            "kale", "mango", "avocado", "edamame", "granola",
        )
        val resolved = foods.count { NutritionDatabase.match(it, it) != null }
        assertTrue("only $resolved/${foods.size} international foods resolved via bulk",
            resolved >= 8)
    }

    @Test fun `a known TFDA ingredient resolves through the bulk layer`() {
        // Onion is not a curated record; it must come from bulk.
        val hit = NutritionDatabase.match("洋蔥", "onion")
        assertNotNull("onion should resolve via bulk", hit)
        assertTrue("onion match should mention 洋蔥: ${hit?.canonicalName}",
            hit!!.canonicalName.contains("洋蔥"))
    }

    @Test fun `match stays fast on a typical meal with bulk loaded`() {
        val meal = listOf(
            "雞肉飯" to "chicken rice", "青菜" to "green vegetables",
            "洋蔥" to "onion", "鯖魚" to "mackerel", "蝦" to "shrimp",
        )
        repeat(50) { meal.forEach { (n, e) -> NutritionDatabase.match(n, e) } } // warm
        val iters = 300
        val start = System.nanoTime()
        repeat(iters) { meal.forEach { (n, e) -> NutritionDatabase.match(n, e) } }
        val msPerRecompose = (System.nanoTime() - start) / 1e6 / iters
        println("avg match() time for a 5-item recompose: ${"%.3f".format(msPerRecompose)} ms")
        // Generous bound — catches O(n^2) regressions, not micro-noise.
        assertTrue("recompose match too slow: $msPerRecompose ms", msPerRecompose < 25.0)
    }

    private fun curated(canonical: String): NutritionRecord =
        NutritionDatabase.records.first { it.canonicalName == canonical }
}
