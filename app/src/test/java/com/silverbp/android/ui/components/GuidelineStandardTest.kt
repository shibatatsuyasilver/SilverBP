package com.silverbp.android.ui.components

import com.silverbp.android.core.BpCategory
import com.silverbp.android.core.GuidelineClassifier
import com.silverbp.android.core.HypertensionGuideline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the premise behind collapsing four guidelines into two [GuidelineStandard]
 * options: guidelines grouped together must classify identically (otherwise the
 * merge would silently change a reading's category), and every guideline must map
 * to exactly one standard whose representative is in-group.
 */
class GuidelineStandardTest {

    @Test fun `every guideline maps to a standard that contains it`() {
        HypertensionGuideline.entries.forEach { g ->
            assertTrue(g in GuidelineStandard.of(g).members)
        }
    }

    @Test fun `each standard's representative is one of its members`() {
        GuidelineStandard.entries.forEach { std ->
            assertTrue(std.representative in std.members)
        }
    }

    @Test fun `standards partition all guidelines with no overlap`() {
        val grouped = GuidelineStandard.entries.flatMap { it.members }
        assertEquals(HypertensionGuideline.entries.size, grouped.size)
        assertEquals(HypertensionGuideline.entries.toSet(), grouped.toSet())
    }

    @Test fun `guidelines within a standard classify identically across the BP range`() {
        for (std in GuidelineStandard.entries) {
            val classifiers = std.members.map { GuidelineClassifier(it) }
            for (sys in 80..200 step 1) {
                for (dia in 40..130 step 1) {
                    val categories: Set<BpCategory> = classifiers.map { it.classify(sys, dia) }.toSet()
                    assertEquals(
                        "Members of $std diverge at $sys/$dia: $categories",
                        1,
                        categories.size,
                    )
                }
            }
        }
    }
}
