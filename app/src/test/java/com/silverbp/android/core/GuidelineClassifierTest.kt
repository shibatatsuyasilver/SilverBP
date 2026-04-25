package com.silverbp.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GuidelineClassifierTest {

    private val taiwan = GuidelineClassifier(HypertensionGuideline.Taiwan2022)
    private val esh = GuidelineClassifier(HypertensionGuideline.Esh2023)

    @Test fun `taiwan normal under 120 over 80`() {
        assertEquals(BpCategory.Normal, taiwan.classify(115, 75))
    }

    @Test fun `taiwan elevated 120 to 129 systolic`() {
        assertEquals(BpCategory.Elevated, taiwan.classify(125, 78))
    }

    @Test fun `taiwan stage1 at 130 over 80`() {
        assertEquals(BpCategory.Stage1, taiwan.classify(132, 82))
    }

    @Test fun `taiwan stage2 at 140 over 90`() {
        assertEquals(BpCategory.Stage2, taiwan.classify(145, 92))
    }

    @Test fun `taiwan crisis at 180 over 120`() {
        assertEquals(BpCategory.HypertensiveCrisis, taiwan.classify(185, 125))
    }

    @Test fun `taiwan hypotension under 90 over 60`() {
        assertEquals(BpCategory.Hypotension, taiwan.classify(85, 55))
    }

    @Test fun `esh stage1 cutoff is 140 over 90`() {
        // ESH 2023 doesn't classify 130/85 as stage1 — that's elevated.
        assertEquals(BpCategory.Elevated, esh.classify(135, 85))
        assertEquals(BpCategory.Stage1, esh.classify(145, 92))
    }

    @Test fun `esh stage2 at 160 over 100`() {
        assertEquals(BpCategory.Stage2, esh.classify(165, 102))
    }
}
