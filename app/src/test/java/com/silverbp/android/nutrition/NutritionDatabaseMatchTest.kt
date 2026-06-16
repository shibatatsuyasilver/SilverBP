package com.silverbp.android.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Guards [NutritionDatabase.match] against the substring false-match class that
 * mislabeled a real 便當 photo: the model's vague "滷味 / Braised Meat/Vegetables"
 * label substring-matched the alias "vegetable" inside "vegetableS" and logged
 * braised pork as 48-kcal boiled greens. The fix trims over-broad English
 * category aliases (vegetable/egg/spring roll/rice/tofu/...) and adds the missing
 * 便當小菜 records (豆皮/滷肉/筍絲/酸菜) so labels resolve to the right food.
 */
class NutritionDatabaseMatchTest {

    // These cases assert the CURATED-ONLY behaviour; ensure no bulk layer leaked
    // in from another test class sharing the JVM.
    @Before fun curatedOnly() { NutritionDatabase.bulk = null }

    private fun canonical(name: String, nameEn: String? = null): String? =
        NutritionDatabase.match(name, nameEn)?.canonicalName

    // ---- the photo bug: braised pork must NOT become boiled greens ----

    @Test fun `vague braised label resolves to braised pork, not boiled greens`() {
        // Was: 燙青菜 (48 kcal) via "vegetable" substring of "vegetables".
        assertEquals("滷肉", canonical("滷味", "Braised Meat/Vegetables"))
    }

    @Test fun `specific braised pork labels resolve to 滷肉`() {
        assertEquals("滷肉", canonical("滷肉片", "braised pork slices"))
        assertEquals("滷肉", canonical("滷肉", "braised pork"))
    }

    @Test fun `new lunchbox-side records are reachable`() {
        assertEquals("豆皮", canonical("豆皮", "braised tofu skin"))
        assertEquals("豆皮", canonical("腐皮"))
        assertEquals("筍絲", canonical("筍絲", "shredded bamboo shoots"))
        assertEquals("酸菜", canonical("酸菜", "pickled mustard greens"))
    }

    // ---- legit matches must still pass (no regression) ----

    @Test fun `legit matches still resolve`() {
        assertEquals("燙青菜", canonical("青菜", "green vegetables"))
        assertEquals("炒飯", canonical("炒飯", "fried rice"))
        assertEquals("白飯", canonical("白飯", "white rice"))
        assertEquals("雞肉飯", canonical("雞肉飯", "chicken rice"))
        assertEquals("滷肉飯", canonical("滷肉飯", "braised pork rice"))
        assertEquals("香蕉", canonical("香蕉", "banana"))
        // egg dishes keep their specific record despite dropping the bare "egg" alias
        assertEquals("煎蛋", canonical("番茄炒蛋", "scrambled egg with tomato"))
    }

    // ---- the dropped bare-noun aliases no longer create false matches ----

    @Test fun `bare-noun substring false matches are gone`() {
        // Each previously matched a staple via a 3-5 char English category alias.
        assertNull(canonical("蛋花湯", "egg drop soup"))   // was -> 水煮蛋 via "egg"
        assertNull(canonical("咖哩飯", "curry rice"))      // was -> 白飯 via "rice"
        assertNull(canonical("春捲", "spring rolls"))      // was -> 潤餅 via "spring roll"
    }

    // ---- structural invariant: every record self-resolves ----

    @Test fun `every record resolves to itself by canonical name`() {
        for (record in NutritionDatabase.records) {
            val hit = NutritionDatabase.match(record.canonicalName)
            assertNotNull("no match for canonical ${record.canonicalName}", hit)
            assertEquals(
                "canonical ${record.canonicalName} resolved to ${hit?.canonicalName}",
                record.canonicalName,
                hit?.canonicalName,
            )
        }
    }
}
